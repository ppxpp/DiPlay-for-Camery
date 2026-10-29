package com.shilapi.xcertplay.btprobe

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 1280×720 横屏：左侧步骤，右侧证据；设备选择和报告独立于主 DiPlay。 */
class ProbeActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private lateinit var report: ProbeReport
    private lateinit var output: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var devices: Spinner
    private lateinit var hotspotSsid: EditText
    private lateinit var originalProfile: EditText
    private lateinit var hotspotPassword: EditText
    private lateinit var status: TextView
    private val buttons = mutableListOf<Button>()
    private val stages = mutableListOf<TextView>()
    private val pioneer = PioneerAdapter()
    private var paired = emptyList<PairedDevice>()
    private var adapterAvailable = false
    private var busy = false
    private var receiverRegistered = false
    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val extras = runCatching { intent.extras?.keySet()?.joinToString { key ->
                "$key=${intent.extras?.get(key)}"
            } }.getOrElse { "extras 无法解码：${message(it)}" }
            record("蓝牙事件 ${intent.action} $extras")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        report = ProbeReport(this)
        PioneerBlueforceNative.configure(this, ::record)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        record("新测试会话 uid=${Process.myUid()} pid=${Process.myPid()}；历史日志仅供回溯")
        runCatching { pioneer.initialize() }.onSuccess { adapterAvailable = true }
            .onFailure { stage(0, "阻塞：没有可用先锋 SDK；${message(it)}") }
        registerEvents()
        refreshDevices()
        dispatchProbeIntent(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchProbeIntent(intent)
    }
    private fun dispatchProbeIntent(command: Intent) {
        val action = command.getStringExtra("probe_action") ?: return
        val handler = android.os.Handler(mainLooper)
        val task = object : Runnable {
            override fun run() {
                if (isDestroyed) return
                if (busy) { handler.postDelayed(this, 250); return }
                runTest {
                    record("ADB 技术验证 action=$action")
                    when (action) {
                        "wifi_hotspot_diag", "wifi_hotspot_start", "wifi_hotspot_5g", "wifi_hotspot_status", "wifi_hotspot_stop", "wifi_hotspot_firewall" -> {
                            val mode = when (action) {
                                "wifi_hotspot_firewall" -> HotspotProbeAction.FIREWALL
                                "wifi_hotspot_start" -> HotspotProbeAction.START
                                "wifi_hotspot_5g" -> HotspotProbeAction.FIVE_GHZ
                                "wifi_hotspot_stop" -> HotspotProbeAction.STOP
                                "wifi_hotspot_status" -> HotspotProbeAction.STATUS
                                else -> HotspotProbeAction.DIAG
                            }
                            record(RootCommand(this@ProbeActivity, ::record).run(mode))
                        }
                        "bluetooth" -> inspect(commandTarget(command))
                        "blueforce_callback_audit" -> PioneerCallbackRepair(this@ProbeActivity,cancelled,::record).audit()
                        "blueforce_callback_repair" -> PioneerCallbackRepair(this@ProbeActivity,cancelled,::record).repair()
                        "blueforce_restore_profile" -> PioneerSerialProbe(this@ProbeActivity,pioneer,cancelled,::record)
                            .restoreProfile(commandTarget(command),command.getIntExtra("profile_mask",-1))
                        "blueforce_observe" -> serial(commandTarget(command), false)
                        "blueforce_carplay", "blueforce_prepare_carplay", "blueforce_prepare_carplay_ap" -> fullCarPlay(commandTarget(command),
                            action.startsWith("blueforce_prepare_carplay"), commandSettings(command))
                        "blueforce_iap2" -> serial(commandTarget(command), true)
                        "blueforce_prepare_iap2" -> serial(commandTarget(command), true, true)
                        "audio_inventory" -> AudioChannelProbe(this@ProbeActivity, ::record, cancelled).inventory()
                        "audio" -> AudioChannelProbe(this@ProbeActivity, ::record, cancelled).play(
                            command.getIntExtra("source", 300), command.getIntExtra("stream", 401),
                            command.getStringExtra("token"), command.getIntExtra("focus_stream", -1),
                            command.getIntExtra("duration_ms", 8000), command.getBooleanExtra("restore", true))
                        else -> record("未知验证动作=$action")
                    }
                }
            }
        }
        handler.postDelayed(task, 500)
    }
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.rgb(12, 17, 27))
        }
        root.addView(label("CarPlay 蓝牙协商 · 分步验证", 23f))
        status = label("普通 APP · 先查接口与证据，再推进协议", 14f)
        root.addView(status)
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f))
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), dp(14), 0)
        }
        val leftScroll = ScrollView(this).apply { addView(left) }
        columns.addView(leftScroll, LinearLayout.LayoutParams(0, -1, 0.38f))
        devices = Spinner(this).apply { setBackgroundColor(Color.rgb(225, 231, 239)) }
        left.addView(devices, LinearLayout.LayoutParams(-1, dp(48)))
        button(left, "刷新已配对设备") { refreshDevices() }
        val titles = listOf("1. 适配器与现有连接", "2. CarPlay 能力发现 / EIR、SDP",
            "3. 配对 / SSP 安全属性", "4. 蓝牙 iAP2 数据通道", "5. iAP2 链路与 MFi", "6. 无线授权与 Wi-Fi 参数")
        titles.forEach { title ->
            val row = label("$title\n待验证", 15f).apply { setPadding(0, dp(5), 0, dp(3)) }
            stages.add(row)
            left.addView(row)
        }
        button(left, "读取步骤 1–4 的接口证据") { runSelected { inspect(it) } }
        val prefs = getSharedPreferences("probe-existing-wifi", MODE_PRIVATE)
        val defaults = ProbeNetworkDefaults.read(this)
        left.addView(label("CarPlay 验证：车机与 iPhone 连接同一 Wi-Fi", 15f))
        hotspotSsid = EditText(this).apply {
            hint="Wi-Fi 名称"; setText(prefs.getString("ssid", defaults.ssid)); setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY); isSingleLine=true
        }
        hotspotPassword = EditText(this).apply {
            hint="Wi-Fi 密码"; setText(prefs.getString("password", defaults.password))
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); isSingleLine=true
        }
        left.addView(hotspotSsid); left.addView(hotspotPassword)
        left.addView(label("先审计服务回调；主动验证会按需使用 DiPlay root 通道补注册",14f))
        button(left, "只读审计服务内部回调（root）") { runTest {
            PioneerCallbackRepair(this,cancelled,::record).audit()
        } }
        button(left, "按需补注册服务串口回调（root）") { runTest {
            PioneerCallbackRepair(this,cancelled,::record).repair()
        } }
        originalProfile=EditText(this).apply {
            hint="现场原 profile 掩码（上次为2）";setText("2");setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY);inputType=android.text.InputType.TYPE_CLASS_NUMBER;isSingleLine=true
        }
        left.addView(originalProfile)
        button(left, "清理目标残留并恢复原 profile（root）") {
            runCatching { selected() to originalProfile.text.toString().toInt() }.onSuccess { (device,mask) ->
                runTest { PioneerSerialProbe(this,pioneer,cancelled,::record).restoreProfile(device,mask) }
            }.onFailure { record("未恢复：${message(it)}") }
        }
        button(left, "完整蓝牙阶段：MFi → CarPlay → Wi-Fi 接管") { runFull(false) }
        button(left, "单设备临时配置 → 完整蓝牙阶段 → 恢复") { runFull(true) }
        button(left, "原生回调只读观察（20秒）") { runSelected { serial(it, false) } }
        button(left, "基础排查：仅验证 iAP2 link") { runSelected { serial(it, true) } }
        button(left, "基础排查：临时配置并验证 link") { runSelected { serial(it, true, true) } }
        button(left, "车机自身热点 → 完整蓝牙交接") { runSelected { fullCarPlay(it,true,CarPlayProbeSettings("","",true)) } }
        button(left, "车机热点：启动（root）") { runTest { record(RootCommand(this, ::record).run(HotspotProbeAction.START)) } }
        button(left, "车机热点：切换5GHz（root）") { runTest { record(RootCommand(this, ::record).run(HotspotProbeAction.FIVE_GHZ)) } }
        button(left, "车机热点：状态（root）") { runTest { record(RootCommand(this, ::record).run(HotspotProbeAction.STATUS)) } }
        button(left, "车机热点：放行专用AP接口（root）") { runTest { record(RootCommand(this, ::record).run(HotspotProbeAction.FIREWALL)) } }
        button(left, "车机热点：停止并清理（root）") { runTest { record(RootCommand(this, ::record).run(HotspotProbeAction.STOP)) } }
        button(left, "读取音源 / 音频通道清单") { runTest {
            AudioChannelProbe(this, ::record, cancelled).inventory()
        } }
        button(left, "USB 蓝牙模块测试页（OTG · HCI）") {
            startActivity(Intent(this, UsbBtProbeActivity::class.java))
        }
        button(left, "检查步骤 5–6 的前置条件") { runTest {
            stage(4, "先审计/补注册服务内部串口回调，再用 event500 + serialportWriteData 验证真实协议")
            stage(5, "完整验证复用 DiPlay MFi 和无线控制客户端，观察真实 Wi-Fi tunnel 接管")
            record("旧 IOSMgr onBTReceiveData 仅打印日志，已移除 77→65→87 连接测试入口")
        } }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        columns.addView(right, LinearLayout.LayoutParams(0, -1, 0.62f))
        val actions = LinearLayout(this)
        right.addView(actions)
        actions.addView(Button(this).apply { text = "停止测试"; setOnClickListener {
            cancelled.set(true)
            record("请求停止；已受理的本次候选连接由测试线程清理")
        } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(Button(this).apply { text = "导出报告"; setOnClickListener { share() } },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(Button(this).apply { text = "清空日志"; setOnClickListener {
            runCatching { report.clear(); output.text = report.read() }
                .onFailure { Toast.makeText(this@ProbeActivity, message(it), Toast.LENGTH_LONG).show() }
        } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        output = label(report.read(), 14f).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        logScroll = ScrollView(this).apply { addView(output) }
        right.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(228, 236, 248))
    }
    private fun button(parent: LinearLayout, title: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = title; isAllCaps = false
            setOnClickListener { action() }; buttons.add(this)
        }, LinearLayout.LayoutParams(-1, dp(50)))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun selected(): PairedDevice = paired.getOrNull(devices.selectedItemPosition)
        ?: error("请选择已配对设备")
    private fun runSelected(action: (PairedDevice) -> Unit) {
        // 在 UI 线程捕获选择结果，后台线程不读取 Spinner 状态。
        runCatching { selected() }.onSuccess { target -> runTest { action(target) } }
            .onFailure { record("未开始测试：${message(it)}") }
    }
    private fun refreshDevices() = runTest {
        check(adapterAvailable) { "这台设备没有可用先锋 SDK；需在先锋车机验证" }
        val found = pioneer.paired()
        runOnUiThread {
            paired = found
            devices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                found.map { it.toString() }.ifEmpty { listOf("没有已配对设备") })
        }
        record("读取已配对设备 ${found.size} 个：${found.joinToString()}")
        record("报告为本 APK 新 UID 的结果；不继承上一 APK 的权限验证结论")
    }
    private fun runTest(action: () -> Unit) {
        if (busy) return
        busy = true; cancelled.set(false)
        buttons.forEach { it.isEnabled = false }; devices.isEnabled = false
        listOf(hotspotSsid,hotspotPassword,originalProfile).forEach { it.isEnabled=false }
        status.text = "测试中 · 结果会自动保存"
        worker.execute {
            try { action() }
            catch (failure: Throwable) { record("测试未完成：${message(failure)}") }
            finally { runOnUiThread {
                if (!isDestroyed) {
                    busy = false
                    buttons.forEach { it.isEnabled = true }; devices.isEnabled = true
                    listOf(hotspotSsid,hotspotPassword,originalProfile).forEach { it.isEnabled=true }
                    status.text = "本轮结束 · 请查看各步骤证据"
                }
            } }
        }
    }
    private fun inspect(device: PairedDevice) {
        record("设备=${device.name} address=${device.address} handle=${device.handle}")
        record("fingerprint=${Build.FINGERPRINT}; uid=${Process.myUid()}")
        record("标准 Android Bluetooth enabled=${BluetoothAdapter.getDefaultAdapter()?.isEnabled}")
        for (name in listOf("getState", "isBtValid", "getSppEnableApple", "isSppConnectable")) {
            record("先锋 $name=${pioneer.query(name)}")
        }
        val connection = pioneer.query("getConnectionState", device)
        val profiles = pioneer.query("getProfileSetting", device)
        stage(0, "已读取：device=$connection 配置=$profiles；不推断 iAP2")
        val binder = PioneerBinder(::record)
        check(binder.firmwareKnown()) { "固件不同；已完成 Java 查询，停止原生探测" }
        binder.query(67, device.handle)
        stage(2, "已读取普通配对状态；SSP 模式、密钥类型、加密状态仍未知")
        val supported = binder.output(79, device.handle)
        val connected = binder.output(80, device.handle)
        val setting = binder.output(81, device.handle)
        record("profile 掩码：supported=$supported connected=$connected setting=$setting")
        stage(1, "待验证：profile 掩码不等于 EIR 或 iAP2 SDP 记录")
        for (index in 0..4) binder.query(78, device.handle, index)
        for (index in 0..8) { if (cancelled.get()) break; binder.query(134, index) }
        binder.query(141)
        iosMgrSweep()
        stage(5, "待验证：依赖 iAP2 与 MFi")
        record("SDK 查询接口清单：" + device.native.javaClass.methods.filter {
            it.name.contains("pair", true) || it.name.contains("uuid", true) || it.name.contains("service", true)
        }.joinToString { it.toString() })
    }
    private fun commandTarget(command: Intent): PairedDevice {
        val address = command.getStringExtra("target_address") ?: error("ADB 动作必须提供 target_address，不默认选第一台")
        return pioneer.paired().singleOrNull { PioneerBlueforceNative.sameAddress(it.address, address) }
            ?: error("目标不在当前配对列表中")
    }
    private fun runFull(prepare: Boolean) {
        runCatching {
            val target = selected()
            val settings = CarPlayProbeSettings(hotspotSsid.text.toString(), hotspotPassword.text.toString())
            check(settings.ssid.isNotBlank()) { "请填写车机当前连接的 Wi-Fi 名称" }
            getSharedPreferences("probe-existing-wifi", MODE_PRIVATE).edit().putString("ssid",settings.ssid)
                .putString("password",settings.password).apply()
            runTest { fullCarPlay(target,prepare,settings) }
        }.onFailure { record("未开始测试：${message(it)}") }
    }
    private fun commandSettings(command: Intent): CarPlayProbeSettings {
        if (command.getStringExtra("probe_action") == "blueforce_prepare_carplay_ap") return CarPlayProbeSettings("", "", true)
        val prefs=getSharedPreferences("probe-existing-wifi", MODE_PRIVATE)
        val defaults=ProbeNetworkDefaults.read(this)
        return CarPlayProbeSettings(command.getStringExtra("hotspot_ssid") ?: prefs.getString("ssid", defaults.ssid).orEmpty(),
            command.getStringExtra("hotspot_password") ?: prefs.getString("password", defaults.password).orEmpty())
    }
    private fun fullCarPlay(device: PairedDevice, prepare: Boolean, settings: CarPlayProbeSettings) {
        stage(4,"完整蓝牙测试：身份识别、MFi、Wi-Fi 配置、CarPlay StartSession")
        PioneerCarPlayProbe(this,settings,cancelled,::record).use { probe ->
            probe.prepare(pioneer)
            PioneerSerialProbe(this,pioneer,cancelled,::record).run(device,true,prepare,probe::verify)
        }
        stage(5,"本轮结束：以 bluetoothWorkVerified 和分阶段证据为准")
    }
    private fun serial(device: PairedDevice, active: Boolean, prepare: Boolean = false) {
        stage(3, "Blueforce 原生通道测试：target=${device.address} active=$active prepare=$prepare")
        PioneerSerialProbe(this,pioneer, cancelled, ::record).run(device, active, prepare)
        stage(4, "本轮结束；以 RX、TX 和 iap2Verified 记录为准，不能等同于完整 CarPlay")
    }
    private fun iosMgrSweep() {
        val ios = IosMgrProbe(::record)
        record(ios.availableDescription())
        for (code in listOf(75, 76, 67, 74, 70, 71, 72, 82)) ios.call(code, IosMgrProbe.DEV_BT)
        for (mode in 0..2) ios.call(79, mode)
        stage(3, "已读取 IOSMgr 原生状态；会话有无以 67/74 与 dumpsys IOSMgr 对照为准")
        stage(4, "IOSMgr 接收端仅日志；应使用新版 Blueforce 原始回调验证")
    }
    private fun stage(index: Int, evidence: String) {
        record("步骤 ${index + 1}：$evidence")
        runOnUiThread {
            val title = stages[index].text.toString().substringBefore('\n')
            stages[index].text = "$title\n$evidence"
        }
    }
    private fun record(message: String) {
        val saved = report.append(message)
        runOnUiThread {
            if (!isDestroyed) {
                output.text = saved
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }
    private fun registerEvents() {
        val filter = IntentFilter()
        runCatching {
            val type = Class.forName("jp.pioneer.ceam.bluetooth.BluetoothAdapter")
            for (field in type.fields.filter { it.name.startsWith("ACTION_") && it.type == String::class.java }) {
                (field.get(null) as? String)?.let { filter.addAction(it) }
            }
            if (filter.countActions() > 0) {
                registerReceiver(events, filter); receiverRegistered = true
                record("监听先锋 SDK 已声明的 ${filter.countActions()} 种状态事件；事件本身不证明加密属性")
            }
        }.onFailure { record("事件监听不可用：${message(it)}") }
    }
    private fun share() {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.reports", report.file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "导出蓝牙协商报告"))
        }.onFailure { Toast.makeText(this, message(it), Toast.LENGTH_LONG).show() }
    }
    private fun message(failure: Throwable): String {
        val actual = if (failure is InvocationTargetException) failure.targetException else failure
        return "${actual.javaClass.simpleName}: ${actual.message}"
    }
    override fun onDestroy() {
        cancelled.set(true)
        if (receiverRegistered) unregisterReceiver(events)
        worker.shutdown()
        super.onDestroy()
    }
}
