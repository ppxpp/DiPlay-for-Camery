package com.shilapi.xcertplay.usbpermission

import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.hardware.usb.*
import android.os.*
import android.widget.*
import com.shilapi.xcertplay.transport.*
import com.shilapi.xcertplay.orchestration.WiredConfigurationAction
import java.io.Closeable
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Temporary manual probe. Shares the production USB transport, without starting CarPlay services. */
class UsbPermissionProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var usb: UsbManager
    private lateinit var host: IphoneUsbHost
    private lateinit var hidden: Switch
    private lateinit var output: TextView
    private lateinit var status: TextView
    private val registrations = mutableListOf<Closeable>()
    private val lines = ArrayDeque<String>()
    private var running = false
    private var destroyed = false
    private var generation = 0
    private var current: String? = null
    private var opening: String? = null
    private var transitions = 0
    private var session: Iap2UsbSession? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = device(intent) ?: return
            log("${intent.action}: ${describe(device)}")
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                if (current == device.deviceName) {
                    generation++
                    current = null; opening = null
                    session?.close(); session = null
                }
            } else if (running) scan()
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (running) {
                scan()
                handler.postDelayed(this, 1000)
            }
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        usb = getSystemService(UsbManager::class.java)
        host = IphoneUsbHost(this, usb, IphoneUsbMatcher.appleVendor(), onDiagnostic = ::log)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
        }
        root.addView(TextView(this).apply {
            text = "DiPlay USB权限验证（与 mobile 同包名）\n签名不同需先备份设置并卸载。对照测试前拔线重插；已授权时不会再次调用隐藏接口。\n仅验证 USB 建链，不启动完整 CarPlay；为隔离签名影响，本模块不使用 ADB 授权。"
        })
        hidden = Switch(this).apply { text = "先尝试隐藏接口 grantDevicePermission（默认关闭）" }
        root.addView(hidden)
        val buttons = LinearLayout(this)
        fun button(label: String, action: () -> Unit) {
            buttons.addView(Button(this).apply { text = label; setOnClickListener { action() } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        button("开始验证") { start() }
        button("停止") { stop() }
        button("复制日志") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("USB probe", lines.joinToString("\n")))
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
        }
        button("清空日志") { lines.clear(); output.text = "" }
        root.addView(buttons)
        status = TextView(this).apply { text = "未开始：先选开关，再点击开始验证" }
        root.addView(status)
        output = TextView(this).apply { textSize = 13f; setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        registrations += host.registerPermissionReceiver { result ->
            log("权限广播：$result")
            if (running && result is IphoneUsbHost.PermissionResult.Granted) advance(result.device)
            if (result is IphoneUsbHost.PermissionResult.Denied) status.text = "用户拒绝授权；重新开始可重试"
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_EXPORTED) else registerReceiver(receiver, filter)
        registrations += Closeable { unregisterReceiver(receiver) }
        identity()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        log("Activity 收到 ${intent.action}；系统可能在启动 Activity 前已经授予权限")
        if (running) scan()
    }
    private fun start() {
        stop()
        running = true
        hidden.isEnabled = false
        transitions = 0
        identity()
        log("开始：hidden=${hidden.isChecked}；ADB 回退关闭")
        log("当前设备：${host.listedDevices()}")
        poll.run()
    }
    private fun stop() {
        running = false
        generation++
        handler.removeCallbacks(poll)
        session?.close(); session = null
        current = null; opening = null
        if (::hidden.isInitialized) hidden.isEnabled = true
        if (::status.isInitialized) status.text = "已停止"
    }
    private fun scan() {
        val devices = host.discover()
        if (devices.isEmpty()) { status.text = "等待 iPhone USB 设备"; return }
        val device = devices.first()
        if (current != device.deviceName) {
            current = device.deviceName
            log("发现 ${describe(device)}；hasPermission=${usb.hasPermission(device)}")
            for (i in 0 until device.configurationCount) {
                val config = device.getConfiguration(i)
                log("configuration=${config.id} ${IphoneCarPlayConfiguration.describe(config)}")
            }
            runCatching {
                val result = host.requestPermission(device, allowAdbFallback = false,
                    attemptSilentGrant = hidden.isChecked, silentGrantDiagnostic = ::log)
                log("权限申请结果：$result")
                if (result is IphoneUsbHost.PermissionRequest.Requested) status.text = "等待系统权限结果（请记录是否出现弹窗）"
            }.onFailure { log("权限申请异常：$it"); status.text = "权限申请失败" }
        }
        if (usb.hasPermission(device) && opening != device.deviceName) advance(device)
    }
    private fun advance(device: UsbDevice) {
        if (!running || opening == device.deviceName) return
        opening = device.deviceName
        val token = generation
        val config = IphoneCarPlayConfiguration.find(device)
        log("权限已确认；CarPlay configuration=${config?.id}；active=${host.activeConfiguration(device)}")
        val action = WiredConfigurationAction.choose(config != null,
            config?.let(IphoneCarPlayConfiguration::isCarPlayMode) == true, transitions, 2)
        log("DiPlay 配置策略：${action.diagnostic}")
        if (action == WiredConfigurationAction.REJECT) {
            status.text = "未找到 CarPlay 配置，已达切换次数上限"
            return
        }
        if (action == WiredConfigurationAction.REQUEST_TRANSITION) {
            transitions++
            status.text = "发送 DiPlay 同款 CarPlay 切换请求；等待重新枚举"
            log("CarPlay vendor request 0x52/index=4；attempt=$transitions")
            host.requestCarPlayReenumerationAsync(device, worker) { result -> handler.post {
                if (destroyed || token != generation) return@post
                log("切换结果：$result")
                if (result is IphoneUsbHost.TransitionResult.Failed) {
                    log("切换失败：${result.error} cause=${result.error.cause}")
                    if (WiredConfigurationAction.fallbackAfterRejection(config != null,
                            config?.let(IphoneCarPlayConfiguration::isCarPlayMode) == true)) {
                        opening = null
                        advance(device)
                    } else status.text = "切换失败：${result.error.message}"
                }
            } }
        } else {
            status.text = "打开 USBMUX 并选择配置、占用接口"
            host.openIap2UsbSessionAsync(device, worker) { result -> handler.post {
                if (destroyed || token != generation) {
                    if (result is IphoneUsbHost.Iap2SessionResult.Connected) result.session.close()
                    return@post
                }
                when (result) {
                    is IphoneUsbHost.Iap2SessionResult.Connected -> {
                        session = result.session
                        log("成功：USB openDevice / setConfiguration / claimInterface；USBMUX 会话已保留，点击停止释放")
                        status.text = "USB 建链成功（不代表完整 CarPlay 成功）"
                    }
                    is IphoneUsbHost.Iap2SessionResult.Failed -> {
                        log("USBMUX 失败：${result.error} cause=${result.error.cause}")
                        status.text = "USBMUX 建链失败"
                    }
                }
            } }
        }
    }
    @Suppress("DEPRECATION")
    private fun identity() {
        fun certificate(pkg: String): String = packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            .signatures.orEmpty().joinToString(",") { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }
        runCatching {
            val own = certificate(packageName)
            val platform = certificate("android")
            log("Android=${Build.VERSION.RELEASE} SDK=${Build.VERSION.SDK_INT}；package=$packageName uid=${applicationInfo.uid} targetSdk=${applicationInfo.targetSdkVersion}")
            log("APK SHA256=$own")
            log("平台 android SHA256=$platform；与 APK 相同=${own == platform}")
            log("MANAGE_USB=${checkSelfPermission("android.permission.MANAGE_USB") == PackageManager.PERMISSION_GRANTED}；systemApp=${applicationInfo.flags and 1 != 0}")
        }.onFailure { log("身份检查失败：$it") }
    }
    private fun describe(device: UsbDevice) = "%04x:%04x %s id=%d".format(device.vendorId, device.productId, device.deviceName, device.deviceId)
    @Suppress("DEPRECATION")
    private fun device(intent: Intent): UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    private fun log(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { log(message) }; return }
        if (destroyed) return
        val line = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + " " + message
        android.util.Log.i("UsbPermissionProbe", line)
        lines.addLast(line)
        while (lines.size > 500) lines.removeFirst()
        if (::output.isInitialized) output.text = lines.joinToString("\n")
    }
    override fun onDestroy() {
        stop(); destroyed = true
        registrations.forEach { runCatching { it.close() } }
        worker.shutdown()
        super.onDestroy()
    }
}
