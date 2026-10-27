package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.Context
import android.os.Process
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream

/** This backend never replaces a standard Bluetooth path unless explicitly selected. */
object PioneerVehicleWireless {
    data class Phone(val name: String, val address: String)
    fun supported(): Boolean = com.shilapi.xcertplay.pioneer.CamryAdaptation.enabled()
    fun phones(): List<Phone> {
        PioneerBlueforceNative.verifyRuntime()
        check(supported()) { "Camry A-navigation adaptation is disabled" }
        val adapter=PioneerAdapter().apply { initialize() }
        return adapter.paired().map { Phone(it.name,it.address) }
    }
    fun preflight(context: Context, target: String?, cancelled: AtomicBoolean, log: (String) -> Unit) {
        check(supported()) { "Camry A-navigation adaptation is disabled" }
        com.shilapi.xcertplay.media.PioneerNativeOpusEncoder.configure(context)
        PioneerBlueforceNative.verifyRuntime()
        val adapter=PioneerAdapter().apply { initialize() }
        val known=adapter.paired()
        val selected=if(target.isNullOrBlank()) known.singleOrNull() else known.singleOrNull {
            PioneerBlueforceNative.sameAddress(it.address,target)
        }
        if(selected==null) throw com.shilapi.xcertplay.network.WirelessStartupException(
            com.shilapi.xcertplay.network.WirelessStartupFailure.BLUETOOTH_NOT_CONNECTED,
            "请在原厂蓝牙设置中配对并连接已选 iPhone，再重试无线 CarPlay")
        adapter.requireConnected(selected)
        PioneerBlueforceNative.verifyRuntime()
        val baseline=PioneerCallbackRepair(context,cancelled,log).audit()
        val active=baseline.ports.filter { it.connected || it.lowerStatus!=0 }
        if(active.isNotEmpty()) {
            check(active.size==1 && active.single().index==0 && active.single().connected &&
                active.single().lowerStatus==0 && PioneerBlueforceNative.sameAddress(active.single().address,selected.address) &&
                PioneerCallbackRepair.ownsPreviousPort(context,selected.address,active.single().uuid)) {
                "Vehicle serial port is already in use by an unconfirmed owner"
            }
            // Actual disconnect is performed by prepareBaseline, after all ownership checks.
        }
    }
    private val lock=Any()
    private val leases=RetryableLeaseOwner<Connection>()
    fun connect(context: Context, target: String?, cancelled: AtomicBoolean, log: (String) -> Unit): Connection = synchronized(lock) {
        check(supported()) { "Camry A-navigation adaptation is disabled" }
        // A failed close retains ownership. Retrying first retries that same lease,
        // rather than stealing it or permanently blocking every future connection.
        leases.prepare { it.close() }
        val adapter=PioneerAdapter().apply { initialize() }
        val phones=adapter.paired()
        val device=if(target.isNullOrBlank()) phones.singleOrNull() else phones.singleOrNull {
            PioneerBlueforceNative.sameAddress(it.address,target)
        }
        checkNotNull(device) { "Select an iPhone paired in the original vehicle Bluetooth settings" }
        adapter.requireConnected(device) // Recheck before claiming or changing a serial/profile lease.
        val connection=PioneerSerialConnection(context,adapter,cancelled,log,device)
        val lease=Connection(connection,(adapter.query("getAddress") as? String).orEmpty(),device.address,log)
        check(lease.bluetoothAddress.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) &&
            lease.bluetoothAddress!="02:00:00:00:00:00" && lease.bluetoothAddress!="00:00:00:00:00:00") { "Invalid vehicle Bluetooth address" }
        leases.claim(lease)
        try { lease.attach(connection.connect());lease }
        catch(failure: Throwable) {
            try { leases.release(lease) { connection.close() } } catch(cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
    class Connection internal constructor(private val serial: PioneerSerialConnection,
        val bluetoothAddress: String, val phoneAddress: String, private val log: (String) -> Unit) : Closeable {
        private val bootstrapClosed=AtomicBoolean(false)
        private lateinit var source: BlockingDuplexByteStream
        internal fun attach(stream: BlockingDuplexByteStream) { source=stream }
        val stream: BlockingDuplexByteStream = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) = source.send(data)
            override fun recv(maxBytes: Int,timeoutMillis: Long)=source.recv(maxBytes,timeoutMillis)
            override fun close() {
                if(bootstrapClosed.compareAndSet(false,true)) {
                    source.close()
                    // Never wait for vendor Binder callbacks under the controller's session lock.
                    Thread({ runCatching { serial.closeBootstrap() }.onFailure {
                        log("Pioneer bootstrap release pending: ${it.javaClass.simpleName}")
                    } },"pioneer-wireless-bootstrap-release").apply { isDaemon=true;start() }
                }
            }
        }
        override fun close() = synchronized(lock) {
            leases.release(this) { serial.close() }
        }
    }
}
