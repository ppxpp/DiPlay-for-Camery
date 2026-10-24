package com.shilapi.xcertplay.network

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.system.Os
import com.shilapi.xcertplay.pioneer.PioneerUsbPolicy
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import com.shilapi.xcertplay.transport.UsbBindingSnapshot
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One kernel-network lease. There is deliberately no fallback to VPN or lwIP. */
internal class NativeNcmLease private constructor(
    private val appContext: Context,
    private val directory: File,
    private val token: String,
    private val isCurrent: () -> Boolean,
    private val log: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) : Closeable {
    lateinit var binding: NativeNcmBinding
        private set
    lateinit var hostMac: ByteArray
        private set
    private val closing = AtomicBoolean()
    private val heartbeat = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "native-ncm-lease").apply { isDaemon = true }
    }
    @Volatile private var interfaceName: String? = null

    private fun state(): JSONObject? = runCatching {
        val file = File(directory, "status.json")
        check(file.length() in 1..8192)
        JSONObject(file.readText()).also { check(it.getString("token") == token) }
    }.getOrNull()

    private fun renew() {
        val next = File(directory, "heartbeat.tmp")
        next.writeText(SystemClock.elapsedRealtime().toString())
        check(next.renameTo(File(directory, "heartbeat"))) { "Could not renew native NCM lease" }
    }

    private fun launch(context: Context, device: UsbDevice, address: String) {
        com.shilapi.xcertplay.pioneer.PioneerConnectionTasks.update("driver", "RUNNING")
        val (bus, number) = UsbBindingSnapshot.parseDeviceName(device.deviceName)
            ?: throw IOException("Invalid iPhone USB device path")
        val config = IphoneCarPlayConfiguration.find(device) ?: throw IOException("CarPlay USB configuration missing")
        val function = NcmFunctionDiscovery.find(config) ?: throw IOException("NCM USB function missing")
        val deadline = SystemClock.elapsedRealtime() + PREPARE_TIMEOUT_MILLIS
        File(directory, "request.json").writeText(JSONObject()
            .put("bus", bus).put("device", number).put("product", device.productId)
            .put("configuration", config.id).put("control", function.control.id).put("data", function.data.id)
            .put("apk", context.applicationInfo.sourceDir)
            .put("appPid", Process.myPid()).put("appStart", appProcessStart())
            .put("address", address).put("requestedAtMs", SystemClock.elapsedRealtime()).put("deadline", deadline).put("boot", bootIdentity()).toString())
        renew()
        val dispatchError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        Thread({
            runCatching {
                if (dispatchWarmWorker(context)) return@runCatching
                val command = "CLASSPATH=${quote(context.applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
                    "com.shilapi.xcertplay.pioneer.PioneerNativeNcmHelper ${quote(directory.path)} </dev/null >/dev/null 2>&1 &"
                invokeRoot(command)
            }.onFailure {
                log("NATIVE_NCM_DISPATCH_FAILED ${it.javaClass.simpleName}: ${it.message}")
                dispatchError.set(it)
            }
        }, "native-ncm-launch").apply { isDaemon = true; start() }
        while (SystemClock.elapsedRealtime() < deadline && isCurrent() && !Thread.currentThread().isInterrupted) {
            dispatchError.get()?.let { throw IOException("Pioneer native network service rejected launch", it) }
            val status = state()
            when (status?.optString("state")) {
                "READY" -> {
                    val name = status.getString("interface")
                    val index = status.getInt("index")
                    check(name.matches(Regex("[A-Za-z0-9_.-]{1,15}"))) { "Invalid native NCM interface name" }
                    val iface = NetworkInterface.getByName(name)
                    check(iface != null && iface.index == index) { "Native NCM interface changed" }
                    val local = Inet6Address.getByAddress(null, InetAddress.getByName(status.getString("address")).address, index)
                    check(iface.inetAddresses.toList().any { it.address.contentEquals(local.address) }) {
                        "Native NCM address not available"
                    }
                    val discoveryStarted = SystemClock.elapsedRealtime()
                    val network = awaitNetwork(context, name, status.optString("mode") == "BORROWED")
                    val discoveryMillis = SystemClock.elapsedRealtime() - discoveryStarted
                    check(status.optString("mode") != "BORROWED" || network != null) { "Managed NCM Android network is unavailable" }
                    val candidate = NativeNcmBinding(local, index, network)
                    val routeStarted = SystemClock.elapsedRealtime()
                    awaitRoute(candidate)
                    val routeMillis = SystemClock.elapsedRealtime() - routeStarted
                    com.shilapi.xcertplay.pioneer.PioneerConnectionTasks.update("driver", "COMPLETE")
                    com.shilapi.xcertplay.pioneer.PioneerConnectionTasks.update("interface", "COMPLETE")
                    com.shilapi.xcertplay.pioneer.PioneerConnectionTasks.update("components", "COMPLETE")
                    binding = candidate
                    interfaceName = name
                    hostMac = status.getString("mac").split(':').map { it.toInt(16).toByte() }.toByteArray()
                    require(hostMac.size == 6)
                    log(
                        "NATIVE_NCM_ATTACHED interface=$name index=$index address=${local.hostAddress} " +
                            "network=${network != null} route=CONFIRMED; manual backend, no VPN/lwIP " +
                            "networkDiscoveryMs=$discoveryMillis routeVerifyMs=$routeMillis " +
                            "helperStartupMs=${status.optLong("helperStartupMs", -1)} preparationTiming=${status.optJSONObject("preparationTiming")}",
                    )
                    startTrafficDiagnostics(name)
                    heartbeat.scheduleWithFixedDelay({
                        if (!closing.get()) try {
                            if (!isCurrent()) { close(); return@scheduleWithFixedDelay }
                            val current = state()
                            if (current?.optString("state") != "READY" ||
                                SystemClock.elapsedRealtime() - current.optLong("updated", 0) !in 0..3_000) throw IOException("Native NCM stopped: ${current?.optString("error")}")
                            if (!networkStillCurrent()) throw IOException("Native NCM Android network changed")
                            renew()
                        } catch (error: Throwable) {
                            if (!closing.get()) {
                                // Cleanup can replace the helper status, so capture it first.
                                val failed = state()
                                log("NATIVE_NCM_LEASE_FAILURE reason=${error.message} " +
                                    "helperState=${failed?.optString("state")} helperError=${failed?.optString("error")} " +
                                    "helperAgeMs=${failed?.let { SystemClock.elapsedRealtime() - it.optLong("updated", 0) }}")
                                runCatching { close() }.onFailure { log("NATIVE_NCM_RELEASE_FAILURE ${it.message}") }
                                onError(error)
                            }
                        }
                    }, 1, 1, TimeUnit.SECONDS)
                    return
                }
                "FAILED", "CLOSED", "RELEASE_FAILED" -> throw IOException("Native NCM: ${status.optString("error")}")
            }
            renew()
            Thread.sleep(50)
        }
        throw IOException("Native NCM preparation cancelled or timed out; select the other backend manually if needed")
    }

    /** Only a failed connect before submitting any bytes may use a newly launched root worker. */
    private fun dispatchWarmWorker(context: Context): Boolean {
        if (!com.shilapi.xcertplay.pioneer.PioneerNativeNcmStartupPolicy.warmWorkerEnabled(context)) {
            log("NATIVE_NCM_WARM_DISPATCH disabled; launching native worker")
            return false
        }
        val root = File(context.filesDir, "native-ncm")
        val boot = bootIdentity()
        val start = appProcessStart()
        val candidates = root.listFiles().orEmpty().filter {
            it.isFile && it.name.matches(Regex("prewarm-[0-9a-f-]{36}\\.json")) && it.length() in 1..8192
        }.mapNotNull { runCatching { JSONObject(it.readText()) }.getOrNull() }
            .filter {
                val worker = it.optJSONObject("warmWorker")
                it.optString("state") == "READY" && it.optString("boot") == boot &&
                    worker?.optInt("appPid") == Process.myPid() && worker.optString("appStart") == start
            }.sortedByDescending { it.optLong("updated") }
        for (status in candidates) {
            val name = status.getJSONObject("warmWorker").optString("socket")
            if (!name.matches(Regex("diplay-ncm-[0-9a-f-]{36}"))) continue
            android.net.LocalSocket().use { socket ->
                try {
                    socket.connect(android.net.LocalSocketAddress(name, android.net.LocalSocketAddress.Namespace.ABSTRACT))
                } catch (_: IOException) { return@use }
                socket.soTimeout = 2000
                val peer = socket.peerCredentials
                check(peer.uid == 0 && peer.pid == status.optInt("pid")) { "Native warm worker identity changed" }
                if (closing.get() || !isCurrent()) throw IOException("Native warm dispatch cancelled")
                // Once submitted, never dispatch a second helper, even if the ACK is lost.
                com.shilapi.xcertplay.pioneer.NativeNcmWarmProtocol.submitTask(socket.outputStream, directory.canonicalPath)
                com.shilapi.xcertplay.pioneer.NativeNcmWarmProtocol.requireAccepted(socket.inputStream)
                log("NATIVE_NCM_WARM_DISPATCH accepted rootPid=${peer.pid}; reused prewarm process")
                return true
            }
        }
        log("NATIVE_NCM_WARM_DISPATCH unavailable; launching native worker")
        return false
    }

    /**
     * Borrowed networks must become visible to ConnectivityManager before their sockets are bound.
     * A CONFIGURED netd local network need not be exposed there at all. Inspect it once, then let
     * awaitRoute prove app-UID routing instead of spending a second waiting for an optional object.
     */
    private fun awaitNetwork(context: Context, name: String, required: Boolean): android.net.Network? {
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        if (connectivity == null) {
            if (required) throw IOException("Connectivity service unavailable for native NCM")
            return null
        }
        val end = SystemClock.elapsedRealtime() + NETWORK_DISCOVERY_TIMEOUT_MILLIS
        do {
            if (closing.get() || !isCurrent()) throw IOException("Native NCM preparation cancelled")
            val matches = connectivity.allNetworks.orEmpty().filter {
                runCatching { connectivity.getLinkProperties(it)?.interfaceName == name }.getOrDefault(false)
            }
            if (matches.size == 1) return matches.single()
            if (matches.size > 1) throw IOException("Multiple Android networks own the native NCM interface")
            if (!required) return null
            renew()
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < end)
        if (required) throw IOException("Native NCM Android network did not become ready")
        return null
    }

    /** Verify app-UID route selection before advertising AirPlay, as b819 does. */
    private fun awaitRoute(binding: NativeNcmBinding) {
        val end = SystemClock.elapsedRealtime() + ROUTE_VERIFY_TIMEOUT_MILLIS
        var failure: Throwable? = null
        do {
            if (closing.get() || !isCurrent()) throw IOException("Native NCM preparation cancelled")
            try {
                binding.verifyRoute()
                return
            } catch (error: Throwable) {
                failure = error
                renew()
                Thread.sleep(100)
            }
        } while (SystemClock.elapsedRealtime() < end)
        throw IOException("APP_ROUTE_UNCONFIRMED: ${failure?.message ?: "no route"}", failure)
    }

    /** b819 lease monitor: a managed Network must remain the same object on the same interface. */
    private fun networkStillCurrent(): Boolean {
        val network = binding.network ?: return true
        val name = interfaceName ?: return false
        val connectivity = appContext.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return connectivity.allNetworks.any {
            it == network && connectivity.getLinkProperties(it)?.interfaceName == name
        }
    }

    /**
     * b819's startup monitor exposed raw interface counters for the first 45 seconds. Logging the
     * same deltas separates a network problem from an AirPlay handshake problem on the head unit.
     */
    private fun startTrafficDiagnostics(name: String) {
        val started = SystemClock.elapsedRealtime()
        var previous: Map<String, Long>? = null
        heartbeat.scheduleWithFixedDelay({
            if (closing.get()) return@scheduleWithFixedDelay
            val elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed > TRAFFIC_DIAGNOSTIC_MILLIS) return@scheduleWithFixedDelay
            val current = runCatching { readStatistics(name) }.getOrNull() ?: return@scheduleWithFixedDelay
            val old = previous
            val counters = STATISTIC_NAMES.joinToString(" ") { counter ->
                val value = current[counter] ?: -1
                val delta = if (old == null || value < (old[counter] ?: 0)) -1 else value - (old[counter] ?: 0)
                "$counter=$value delta=$delta"
            }
            log("NATIVE_NCM_STATS elapsedMs=$elapsed $counters")
            previous = current
        }, 0, TRAFFIC_DIAGNOSTIC_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun readStatistics(name: String): Map<String, Long> =
        STATISTIC_NAMES.associateWith { counter ->
            val file = File("/sys/class/net/$name/statistics/$counter")
            check(file.length() in 1..32) { "Native NCM statistic unavailable" }
            file.readText().trim().toLong()
        }

    override fun close() {
        // Reentry must re-confirm cleanup: a previous close may have thrown after cancellation.
        // Returning merely because cancellation started would release the outer session guard.
        if (closing.compareAndSet(false, true)) heartbeat.shutdown()
        File(directory, "stop").writeText(token)
        // Preparation may still be in flight. Keep its cancellation marker and journal on disk.
        val end = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < end) {
            val state = state()?.optString("state")
            if (state == "CLOSED" || state == "FAILED") { log("NATIVE_NCM_RELEASED token=$token"); return }
            if (state == "RELEASE_FAILED") throw IOException("Native NCM cleanup failed; inspect status.json before reconnecting")
            try { Thread.sleep(50) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        throw IOException("Native NCM release not confirmed; no automatic backend switch")
    }

    companion object {
        private const val PREPARE_TIMEOUT_MILLIS = 40_000L
        private const val NETWORK_DISCOVERY_TIMEOUT_MILLIS = 5_000L
        private const val ROUTE_VERIFY_TIMEOUT_MILLIS = 3_000L
        private const val TRAFFIC_DIAGNOSTIC_MILLIS = 45_000L
        private const val TRAFFIC_DIAGNOSTIC_INTERVAL_MILLIS = 2_000L
        private val STATISTIC_NAMES = listOf(
            "rx_packets", "tx_packets", "rx_bytes", "tx_bytes",
            "rx_errors", "tx_errors", "rx_dropped", "tx_dropped",
        )

        /** Preload the module without claiming a USB interface or changing routes/firewall rules. */
        fun prewarm(context: Context, eligible: () -> Boolean, log: (String) -> Unit) {
            val root = File(context.filesDir, "native-ncm").apply { mkdirs() }
            val result = File(root, "prewarm-" + UUID.randomUUID() + ".json")
            val cancel = File(result.path + ".cancel")
            try {
                if (!eligible()) return
                Os.chmod(root.path, 0x1c0)
                val deadline = SystemClock.elapsedRealtime() + 20_000
                val apk = context.applicationInfo.sourceDir
                val boot = bootIdentity()
                val processStart = File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[19]
                val command = "CLASSPATH=${quote(apk)} /system/bin/app_process " +
                    "/system/bin com.shilapi.xcertplay.pioneer.PioneerNativeNcmPrewarm " +
                    "${quote(result.path)} ${quote(apk)} ${quote(boot)} ${Process.myPid()} $deadline ${quote(processStart)} ${if (com.shilapi.xcertplay.pioneer.PioneerUsbPermissionProbe.holdsRequests(context)) "" else "USB_GRANT"} " +
                    "</dev/null >/dev/null 2>&1 &"
                val service = rootService() // A missing service cannot dispatch a delayed loader.
                val pending = File(result.path + ".request")
                pending.writeText(JSONObject().put("state", "DISPATCH_PENDING").put("boot", boot)
                    .put("deadline", deadline).toString())
                check(pending.renameTo(result)) { "Could not publish module prewarm request" }
                // Publish the dispatch lifetime before calling Binder, which can return late.
                Thread({
                    runCatching { invokeRoot(command, service) }.onFailure {
                        log("NATIVE_NCM_PREWARM dispatch failed: ${it.message}")
                    }
                }, "native-ncm-prewarm-launch").apply { isDaemon = true; start() }
                val started = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (!eligible()) cancel.writeText("cancel")
                    val status = runCatching { JSONObject(result.readText()) }.getOrNull()
                    when (status?.optString("state")) {
                        "READY" -> {
                            log("NATIVE_NCM_PREWARM ready usbPermission=${status.optJSONObject("usbPermission")} moduleLoaded=true elapsedMs=${status.optLong("updated") - started} " +
                                "firmware=${status.optString("firmware")} kernel=${status.optString("kernel")}")
                            return
                        }
                        "FAILED" -> {
                            throw IOException("Native NCM prewarm: ${status.optString("error")}")
                        }
                        "UNCERTAIN" -> throw IOException("Native NCM module loader still running; restart head unit")
                    }
                    Thread.sleep(100)
                }
                cancel.writeText("cancel")
                throw IOException("Native NCM prewarm completion unconfirmed; retry after helper exits")
            } catch (error: Throwable) {
                log("NATIVE_NCM_PREWARM deferred: ${error.message ?: error.javaClass.simpleName}")
            }
        }

        /** A timed-out root worker must not race a subsequent userspace USB claim. */
        fun requirePrewarmFinished(context: Context, currentBoot: String = bootIdentity()) {
            val root = File(context.filesDir, "native-ncm")
            for (result in root.listFiles().orEmpty().filter {
                it.isFile && it.name.matches(Regex("prewarm-[0-9a-f-]{36}\\.json"))
            }) {
                if (result.length() !in 1..8192) throw IOException("Invalid native NCM prewarm journal")
                // Capture before opening the file: a stale pending inode must never become
                // "expired" while root has already replaced it with LOADING and started insmod.
                val observedAt = SystemClock.elapsedRealtime()
                val status = runCatching { JSONObject(result.readText()) }.getOrNull()
                    ?: throw IOException("Unreadable native NCM prewarm journal")
                val boot = status.optString("boot")
                if (boot.isNotEmpty() && boot != "unknown" && currentBoot != "unknown" && boot != currentBoot) continue
                when (status.optString("state")) {
                    "READY", "FAILED" -> Unit
                    // The helper persists LOADING before any module mutation and checks the
                    // absolute deadline again inside the loader. An expired, undispatched
                    // request cannot mutate the kernel even if Binder returns very late.
                    "DISPATCH_PENDING" -> if (status.optLong("deadline", Long.MAX_VALUE) > observedAt)
                        throw IOException("Native NCM prewarm dispatch still pending")
                    else -> throw IOException("Native NCM module prewarm unconfirmed; wait for helper or restart head unit")
                }
            }
        }

        private class ReleasePending : IOException("Native NCM worker is still releasing the USB network")

        /** Wait off the UI thread for an orderly release, without retrying the whole USB handshake. */
        fun awaitReleased(context: Context, isCurrent: () -> Boolean, log: (String) -> Unit) {
            val started = SystemClock.elapsedRealtime()
            val deadline = started + 8_000
            var waited = false
            while (true) {
                if (!isCurrent() || Thread.currentThread().isInterrupted)
                    throw IOException("Native NCM release wait cancelled")
                try {
                    requireReleased(context)
                    if (waited) log("NATIVE_NCM_RELEASE_FENCE ready elapsedMs=${SystemClock.elapsedRealtime() - started}")
                    return
                } catch (pending: ReleasePending) {
                    if (SystemClock.elapsedRealtime() >= deadline) throw pending
                    if (!waited) log("NATIVE_NCM_RELEASE_FENCE waiting for previous worker")
                    waited = true
                    Thread.sleep(50)
                }
            }
        }

        /** Refuse either wired backend until the previous native worker has released its lease. */
        fun requireReleased(context: Context, currentBoot: String = bootIdentity()) {
            val root = File(context.filesDir, "native-ncm")
            val directories = root.listFiles().orEmpty().filter { it.isDirectory }
            // Cancel first, then acquire the worker's physical lock. A delayed worker checks
            // stop again under this same lock before publishing PREPARING or mutating anything.
            directories.forEach { File(it, "stop").writeText(it.name) }
            fun checkJournals() {
                for (dir in directories) {
                    val file = File(dir, "status.json")
                    val status = runCatching { JSONObject(file.readText()) }.getOrNull()
                    val request = runCatching { JSONObject(File(dir, "request.json").readText()) }.getOrNull()
                    val previousBoot = request?.optString("boot")?.let {
                        it.isNotEmpty() && it != "unknown" && currentBoot != "unknown" && it != currentBoot
                    } == true
                    if (previousBoot) continue
                    val journalFile = File(dir, "firewall-journal.json")
                    if (journalFile.exists()) {
                        val journal = runCatching { JSONObject(journalFile.readText()) }.getOrNull()
                        if (journal?.optString("state") != "RELEASED" ||
                            journal.optString("pending") != "none" || journal.optBoolean("unresolved", true))
                            throw IOException("Previous native firewall mutation is unconfirmed; restart the head unit before reconnecting")
                    }
                    if (!file.exists()) continue
                    if (status?.optString("state") !in listOf("CLOSED", "FAILED")) throw IOException(
                        "Previous native NCM cleanup is unconfirmed; wait for release or restart the head unit")
                }
            }
            fun pruneCompleted() {
                NativeLeaseRetention.prune(root, isSafelyCompleted = ::isSafelyCompleted)
            }
            val lockFile = File(root, "owner.lock")
            if (!lockFile.exists()) { checkJournals(); pruneCompleted(); return }
            java.io.RandomAccessFile(lockFile, "rw").use { file ->
                file.channel.use { channel ->
                    val lock = channel.tryLock() ?: throw ReleasePending()
                    try {
                        checkJournals()
                        pruneCompleted()
                    } finally {
                        lock.release()
                    }
                }
            }
        }

        private fun isSafelyCompleted(directory: File): Boolean {
            val statusFile = File(directory, "status.json")
            if (!statusFile.isFile || statusFile.length() !in 1..8192) return false
            val status = runCatching { JSONObject(statusFile.readText()) }.getOrNull() ?: return false
            if (status.optString("state") !in listOf("CLOSED", "FAILED")) return false
            val journalFile = File(directory, "firewall-journal.json")
            if (!journalFile.exists()) return true
            if (journalFile.length() !in 1..8192) return false
            val journal = runCatching { JSONObject(journalFile.readText()) }.getOrNull() ?: return false
            return journal.optString("state") == "RELEASED" &&
                journal.optString("pending") == "none" &&
                !journal.optBoolean("unresolved", true)
        }

        private fun bootIdentity(): String = runCatching {
            File("/proc/sys/kernel/random/boot_id").readText().trim().also { check(it.isNotEmpty()) }
        }.getOrDefault("unknown")

        private fun appProcessStart(): String =
            File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[19]

        fun open(context: Context, device: UsbDevice, address: String, isCurrent: () -> Boolean,
                 log: (String) -> Unit, onError: (Throwable) -> Unit): NativeNcmLease {
            check(PioneerUsbPolicy.matchesDevice()) { "Native NCM is available only on the verified Pioneer head unit" }
            requireReleased(context)
            val root = File(context.filesDir, "native-ncm").apply { mkdirs() }
            val lock = File(root, "owner.lock")
            if (!lock.exists()) lock.createNewFile()
            Os.chmod(lock.path, 0x180)
            val token = UUID.randomUUID().toString().replace("-", "")
            val dir = File(root, token).apply { check(mkdir()) }
            Os.chmod(dir.path, 0x1c0)
            val lease = NativeNcmLease(context.applicationContext, dir, token, isCurrent, log, onError)
            try { lease.launch(context, device, address); return lease }
            catch (error: Throwable) {
                runCatching { lease.close() }.onFailure { error.addSuppressed(it) }
                throw error
            }
        }

        private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        private fun rootService(): IBinder =
            Class.forName("android.os.ServiceManager").getMethod("checkService", String::class.java)
                .invoke(null, "pioneer.pfilesdoperator") as? IBinder
                ?: throw IOException("Pioneer system network service unavailable")

        private fun invokeRoot(command: String, binder: IBinder = rootService()) {
            val bytes = (command + '\u0000').toByteArray(Charsets.UTF_8)
            require(bytes.size <= 4096)
            val encoded = ByteBuffer.allocate(((bytes.size + 3) and -4) + 12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(Process.myPid()).putInt(bytes.size).putInt(0).put(bytes).array()
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.unmarshall(encoded, 0, encoded.size); data.setDataPosition(0)
                check(binder.transact(14, data, reply, 0) && reply.dataSize() == 4) { "Pioneer root command rejected" }
                reply.setDataPosition(0)
                check(reply.readInt() == 0) { "Pioneer root command failed" }
            } finally { data.recycle(); reply.recycle() }
        }
    }
}
