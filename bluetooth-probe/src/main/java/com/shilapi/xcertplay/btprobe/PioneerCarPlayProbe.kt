package com.shilapi.xcertplay.btprobe

import android.content.Context
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.network.ExistingWifiManager
import com.shilapi.xcertplay.transport.*
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class CarPlayProbeSettings(val ssid: String, val password: String, val localHotspot: Boolean = false)

/** Real receiver endpoint + DiPlay's existing Bluetooth/bootstrap and Wi-Fi tunnel control clients.
 * No payload hex logging: Wi-Fi credentials and authentication material pass through this stream.
 */
internal class PioneerCarPlayProbe(
    private val context: Context,
    private val settings: CarPlayProbeSettings,
    private val cancelled: AtomicBoolean,
    private val log: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val runtimeReady = AtomicBoolean(false)
    private val verifying = AtomicBoolean(false)
    private val subscribed = AtomicBoolean(false)
    private val sessions = CopyOnWriteArrayList<AirPlaySession>()
    private val controls = CopyOnWriteArrayList<Iap2Session>()
    private val executor = Executors.newCachedThreadPool()
    private val media = CarPlayMediaEngine(object : MediaSink {})
    private var server: ServerSocket? = null
    private var bonjour: CarPlayBonjour? = null
    private var hotspot: com.shilapi.xcertplay.network.WirelessHotspotManager? = null
    private lateinit var endpoint: Iap2WirelessCarPlayEndpoint
    private lateinit var identification: Iap2IdentificationConfig
    private lateinit var mfi: com.shilapi.xcertplay.mfi.MfiAuthenticator
    private var endNanos = Long.MAX_VALUE

    fun prepare(adapter: PioneerAdapter) {
        check(settings.localHotspot || settings.ssid.isNotBlank()) { "请填写车机当前连接的 Wi-Fi 名称和密码" }
        mfi = ProbeAuthentication.load(context, log)
        val identity = ProbeAuthentication.identity(context)
        val pairings = ProbeAuthentication.pairings(context)
        val bluetoothMac = adapter.query("getAddress")?.toString() ?: error("无法读取车机蓝牙地址")
        check(bluetoothMac != "02:00:00:00:00:00" && bluetoothMac != "00:00:00:00:00:00") { "车机蓝牙地址无效" }
        val manager: com.shilapi.xcertplay.network.WirelessHotspotManager = if (settings.localHotspot) {
            val wifi = context.getSystemService(Context.WIFI_SERVICE)
            val configuration = wifi.javaClass.getMethod("getWifiApConfiguration").invoke(wifi)
                as? android.net.wifi.WifiConfiguration ?: error("无法读取车机热点配置")
            check((wifi.javaClass.getMethod("getWifiApState").invoke(wifi) as Int) == 13) { "请先启动并验证车机5GHz热点" }
            log("使用车机自身热点接口 bcm1；凭据只用于协议交换")
            com.shilapi.xcertplay.network.ManualHotspotManager(context,
                configuration.SSID, configuration.preSharedKey,
                com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO, 0,
                com.shilapi.xcertplay.orchestration.ManualHotspotSecurity.WPA2,
                onDiagnostic={ log("车机热点：$it") }, isCancelled={ cancelled.get() }, preferredInterface="bcm1")
        } else ExistingWifiManager(context, settings.ssid, settings.password,
            onDiagnostic = { log("现有 Wi-Fi：$it") },
            onNetworkChanged = { log("现有 Wi-Fi 已变化，本轮连接将停止"); cancelled.set(true) })
        hotspot = manager
        val network = manager.start(15000)
        val wireless = Iap2WirelessIdentification(bluetoothMac, network.ssid)
        identification = Iap2IdentificationConfig(name="DiPlay Bluetooth Probe", modelIdentifier="DiPlay",
            manufacturer="xcertplay", serialNumber=identity.pairingId, firmwareVersion="0.9.0",
            hardwareVersion="Pioneer", wireless=wireless)
        val host = checkNotNull(network.hostAddress) { "现有 Wi-Fi 没有可用本地地址" }
        check(!cancelled.get()) { "测试已停止" }
        val deviceBytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        deviceBytes[0] = ((deviceBytes[0].toInt() and 0xfc) or 2).toByte()
        val deviceId = deviceBytes.joinToString(":") { "%02X".format(it.toInt() and 255) }
        // Existing LAN requires the Pioneer forwarded port. Dedicated AP has explicit firewall rules
        // and advertises its real dynamic port, avoiding the navigation service on 8721.
        val listener = ServerSocket().also { server = it; it.reuseAddress=true
            it.bind(InetSocketAddress(host, if (settings.localHotspot) 0 else 8721)); it.soTimeout=500 }
        val config = AirPlayConfig(deviceName="DiPlay Bluetooth Probe", deviceId=deviceId,
            btMac=bluetoothMac, sourceVersion="950.7.1", main=AirPlayDisplayConfig(1280,720),
            port=listener.localPort, pinInboundPorts=!settings.localHotspot, disableAudioOutput=true, microphone=false)
        endpoint = Iap2WirelessCarPlayEndpoint(network.ssid,network.passphrase,network.channel,network.security,
            listOf(checkNotNull(host.hostAddress)),listener.localPort,deviceId,identity.publicKeyHex,
            config.sourceVersion, network.accessPointBssid)
        media.setIapTunnelHandler { stream ->
            if (closed.get() || cancelled.get() || !verifying.get()) false else {
                executor.execute { runTunnel(stream) }; true
            }
        }
        executor.execute {
            while (!closed.get()) try {
                val socket = listener.accept()
                if (!socket.inetAddress.isLoopbackAddress && socket.inetAddress != socket.localAddress)
                    log("Wi-Fi TCP 已接入 peer=${socket.inetAddress.hostAddress}")
                val session = AirPlaySession(socket,config,identity,pairings,mfi,
                    object : AirPlaySessionListener {
                        override fun onSessionActive(session: AirPlaySession) { log("Wi-Fi AirPlay 会话已激活（非画面验收）") }
                        override fun onSessionEnded(session: AirPlaySession) { sessions.remove(session) }
                        override fun onTransportError(message: String) { log("Wi-Fi transport error=$message") }
                        // Existing session logs may contain decoded request data: only emit stage labels.
                        override fun onDebugLog(message: String) {
                            if (message.startsWith("AirPlay iAP SETUP")) log("Wi-Fi 收到 type130 iAP tunnel SETUP")
                        }
                    },media)
                sessions.add(session)
                if (closed.get()) session.close() else session.start()
            } catch (_: SocketTimeoutException) { /* bounded poll */ }
            catch (failure: Exception) { if (!closed.get()) { log("Wi-Fi listener failure=${failure.javaClass.simpleName}"); break } }
        }
        bonjour = CarPlayBonjour(context,config,identity,host.hostAddress,
            useInterfaceMdns=true).also { it.start() }
        log("真实接收端已监听 address=${host.hostAddress} port=${listener.localPort} " +
            "channel=${network.channel}；请关闭其他 DiPlay 会话，并保持车机和 iPhone 在同一 Wi-Fi")
    }

    fun verify(stream: BlockingDuplexByteStream) {
        endNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        verifying.set(true)
        val channel = Iap2Session.openWireless(stream,"pioneer-bluetooth",::safeTrace)
        controls.add(channel)
        // Cancellation also interrupts identification/MFi recv, rather than waiting for its timeout.
        executor.execute {
            try {
                while (!closed.get() && !cancelled.get() && System.nanoTime()<endNanos) Thread.sleep(100)
                channel.close()
                controls.filter { it !== channel }.forEach { it.close() }
            } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        var ready = false
        var result: Iap2WirelessControlResult? = null
        try {
            ready = channel.awaitReady(15000)
            check(ready) { "iAP2 链路未就绪" }
            hotspot!!.validateReady()
            result = Iap2WirelessControlClient(channel,Iap2MfiAuthenticationClient(mfi)).run(
                identification, endpoint, timeoutMillis=90000,
                onReady={ subscribed.set(true); log("蓝牙身份识别、MFi AA05、订阅均已完成") },
                beforeStartSession={ hotspot!!.validateReady() },
                onStartSessionSent={ log("蓝牙 CarPlay StartSession 已发送") },
                onProgress={ log(it) })
            // Give a successful Bluetooth delivery a bounded window to produce an authenticated
            // Wi-Fi runtime tunnel; a TCP accept alone is insufficient.
            while (!runtimeReady.get() && !cancelled.get() && System.nanoTime()<endNanos) Thread.sleep(200)
        } finally {
            val evidence = BluetoothBootstrapEvidence(ready,subscribed.get(),
                result?.wifiConfigurationsSent ?: 0,result?.carPlayStartSessionsSent ?: 0,
                result?.transportNotificationSeen ?: false,result?.postTransportWiFiConfigurationsSent ?: 0,
                result?.wirelessCarPlayAvailableSeen ?: false,runtimeReady.get())
            if (cancelled.get()) log("用户取消测试：本次结果只代表取消前已观察到的阶段")
            log("蓝牙阶段结果 $evidence")
            log("bluetoothControlComplete=${evidence.bluetoothControlComplete} " +
                "wifiRuntimeAuthenticated=${evidence.wifiRuntimeAuthenticated} " +
                "bluetoothWorkVerified=${evidence.bluetoothWorkVerified}")
            log("仅当 bluetoothWorkVerified=true 才确认蓝牙控制步骤及真实 Wi-Fi 接管均通过；画面/音频另行验收")
            verifying.set(false)
            channel.close(); controls.remove(channel)
        }
    }
    private fun runTunnel(stream: BlockingDuplexByteStream) {
        if (closed.get() || cancelled.get()) { stream.close(); return }
        val channel = Iap2Session.openTunnel(stream,"probe-wifi-tunnel",::safeTrace)
        controls.add(channel)
        try {
            check(!closed.get() && !cancelled.get()) { "测试已停止" }
            check(channel.awaitReady(15000)) { "Wi-Fi tunnel link not ready" }
            Iap2WirelessControlClient(channel,Iap2MfiAuthenticationClient(mfi)).run(
                identification,endpoint,timeoutMillis=90000,
                onReady={ runtimeReady.set(true); log("Wi-Fi tunnel 身份识别及 MFi AA05 完成：真实交接已确认") },
                onProgress={ log("Wi-Fi tunnel $it") })
        } catch (failure: Exception) { if (!closed.get() && !cancelled.get()) log("Wi-Fi tunnel 未完成：${failure.javaClass.simpleName}: ${failure.message}") }
        finally { channel.close(); controls.remove(channel) }
    }
    private fun safeTrace(text: String) {
        // Frame header only, never TLVs/raw body (Wi-Fi password, certificates/signatures).
        log(text.substringBefore('\n').substringBefore(" FAILED:") )
    }
    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        runCatching { bonjour?.close() }; runCatching { server?.close() }
        media.setIapTunnelHandler(null)
        controls.forEach { runCatching { it.close() } }
        sessions.forEach { runCatching { it.close() } }
        runCatching { hotspot?.close() }; executor.shutdownNow()
    }
}
