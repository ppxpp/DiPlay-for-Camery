package com.shilapi.xcertplay

import android.app.Activity
import android.content.*
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors

/** Manual-only lab; does not enable production navigation output. */
class NavigationHudLabActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var output: TextView
    private lateinit var json: EditText
    private lateinit var direction: Spinner
    private lateinit var category: Spinner
    private lateinit var guideState: Spinner
    private lateinit var naviState: Spinner
    private lateinit var arrival: Spinner
    private lateinit var source: Spinner
    private lateinit var roundabout: EditText
    private var directionNames = emptyList<String>()
    private lateinit var road: EditText
    private lateinit var distance: EditText
    private lateinit var observation: EditText
    private val entries = java.util.ArrayDeque<String>()
    private val worker = Executors.newSingleThreadExecutor()
    private var listening = false
    private var captureStatusAs: String? = null
    private lateinit var beginState: EditText
    private lateinit var endState: EditText
    private lateinit var beginDetails: EditText
    private lateinit var endDetails: EditText
    private var trial = "未选择对照组"
    private var ownsMeter = false // Worker-thread only.
    private val controllerClass get() = Class.forName("jp.pioneer.ceam.MeterService.MeterServiceController")
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatching {
                val fields = intent.extras?.let { bundle ->
                    bundle.keySet().sorted().joinToString { key ->
                        @Suppress("DEPRECATION") val value = bundle.get(key)
                        "$key=${value.toString().take(512)} (${value?.javaClass?.simpleName})"
                    }
                }
                record("RX ${intent.action} $fields")
                if (intent.getIntExtra("KEY_TYPE", -1) == 10019 && intent.hasExtra("EXTRA_STATE")) {
                    val target = captureStatusAs
                    if (target != null) {
                        val stateValue = intent.getIntExtra("EXTRA_STATE", -1)
                        val detailValue = intent.getIntExtra("EXTRA_STATUS_DETAILS", -1)
                        val stateInput = if (target == "开始") beginState else endState
                        val detailInput = if (target == "开始") beginDetails else endDetails
                        stateInput.setText(stateValue.toString())
                        detailInput.setText(detailValue.toString())
                        captureStatusAs = null
                        record("已捕获 $target 候选 state=$stateValue details=$detailValue；请确认对应导航事件，并非其他状态")
                    }
                }
            }.onFailure { record("RX 无法解析 $it") }
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = getString(com.shilapi.xcertplay.host.R.string.navigation_hud_lab)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(body) })
        text("导航 HUD 技术验证（实验性）\n驻车时手动测试。返回成功不代表屏幕已显示。采集内容可能包含道路与位置。")
        button("开始采集高德输出广播") { startCapture() }
        button("停止采集") { stopCapture() }
        text("广播实验：JSON 值支持整数、布尔和字符串。示例不含完整状态机，TPS 是否接收尚未验证。ICON 与 NEW_ICON 不保证相同。")
        json = edit("广播字段 JSON", """{"KEY_TYPE":10001,"TYPE":1,"CUR_ROAD_NAME":"测试当前路","NEXT_ROAD_NAME":"测试下一路","ICON":2,"NEW_ICON":2,"SEG_REMAIN_DIS":300}""")
        button("发送一次高德兼容广播") { sendProtocol() }
        text("Meter 直连实验：先探测系统常量，再选择方向。与广播路径独立。")
        text("方向类别：按系统常量名称筛选，不猜测数字语义")
        category = spinner(listOf("全部", "直行", "左转", "右转", "掉头", "分岔/匝道", "环岛", "目的地/途经点"))
        direction = spinner(emptyList())
        category.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { filterDirections() }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        text("引导样式 / 导航状态 / 到达事件 / 数据来源（探测后填充）")
        guideState = spinner(emptyList())
        naviState = spinner(emptyList())
        arrival = spinner(emptyList())
        source = spinner(emptyList())
        roundabout = edit("环岛出口编号（0–9；0 为测试默认值）", "0")
        road = edit("下一道路", "测试路")
        distance = edit("距离（米）", "300")
        button("探测 Meter 接口与导航常量") { probe() }
        button("发送一次 Meter 导航") { pushMeter() }
        button("加载停止引导状态（不自动发送）") {
            select(naviState, "METER_NAVI_STATE_NOTINGUIDE")
            select(guideState, "METER_GUIDE_STATE_INVALID")
            record("已选择停止状态；点击发送进行独立验证，和 onMapExit 分开测试")
        }
        button("清除本页 Meter 输出") { task { clearMeter() } }
        text("高德状态通知：已确认 KEY_TYPE=10019，含 EXTRA_STATE 和 EXTRA_STATUS_DETAILS；具体状态值尚未确认，需从高德采集后填写。")
        val autoState = edit("EXTRA_STATE（使用实测值，不预填开始/结束编号）", "")
        val details = edit("EXTRA_STATUS_DETAILS", "-1")
        button("加载高德状态通知（不自动发送）") {
            val state = autoState.text.toString().toIntOrNull()
            val detail = details.text.toString().toIntOrNull()
            if (state == null || detail == null) record("请填写已确认的整数状态值")
            else json.setText(JSONObject().put("KEY_TYPE", 10019).put("EXTRA_STATE", state)
                .put("EXTRA_STATUS_DETAILS", detail).toString())
        }
        text("开始/结束依赖对照实验（标准广播路径）\n先采集或填写已确认的状态值。按钮只发送状态通知，不会启动或停止高德自身导航，也不会调用 Meter，避免混淆两条路径。")
        beginState = edit("开始 EXTRA_STATE（从高德实测填写）", "")
        beginDetails = edit("开始 EXTRA_STATUS_DETAILS", "-1")
        endState = edit("结束 EXTRA_STATE（从高德实测填写）", "")
        endDetails = edit("结束 EXTRA_STATUS_DETAILS", "-1")
        button("采集下一条状态为开始候选") { armStatusCapture("开始") }
        button("采集下一条状态为结束候选") { armStatusCapture("结束") }
        button("取消状态候选采集") { captureStatusAs = null; record("已取消候选采集") }
        button("发送开始通知") { sendStatus(true) }
        button("发送结束通知（不调用 onMapExit）") { sendStatus(false) }
        button("A组：仅发送当前引导报文") {
            trial = "A组：仅引导"
            if (guidanceReady()) sendProtocol()
        }
        button("B组：开始通知 → 当前引导报文") {
            trial = "B组：开始+引导"
            if (guidanceReady() && sendStatus(true)) sendProtocol()
        }
        text("对照步骤：先发结束并人工确认两屏已清除，再运行A组、记录；再次结束并确认清除，再运行B组、记录。若结束无效，两组初始状态不同，不能判断依赖。开始与引导连续发送，不保证接收端处理顺序；可使用单独按钮等待后再发引导复核。")
        text("退出页面不自动发送广播结束；仅尝试清除本页 Meter 输出。")
        observation = edit("实测：仪表图标 / HUD 图标 / 清除结果", "")
        button("记录实测结果") { record("OBSERVATION [$trial] ${observation.text}") }
        button("复制记录") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                ClipData.newPlainText("Navigation HUD lab", entries.joinToString("\n")))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
        output = TextView(this).apply { setTextIsSelectable(true); textSize = 12f }
        body.addView(output)
        record("实验页打开，未发送任何导航数据")
    }
    private fun text(value: String) { body.addView(TextView(this).apply { text = value; textSize = 16f }) }
    private fun edit(label: String, initial: String) = EditText(this).apply {
        hint = label; setText(initial); body.addView(this)
    }
    private fun button(label: String, action: () -> Unit) {
        body.addView(Button(this).apply { text = label; setOnClickListener { action() } })
    }
    private fun spinner(names: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@NavigationHudLabActivity, android.R.layout.simple_spinner_dropdown_item, names)
        body.addView(this)
    }
    private fun select(view: Spinner, name: String) {
        val index = (0 until view.count).firstOrNull { view.getItemAtPosition(it) == name }
        if (index == null) record("系统未提供 $name") else view.setSelection(index)
    }
    private fun filterDirections() {
        val tokens = when (category.selectedItemPosition) {
            1 -> listOf("STRAIGHT")
            2 -> listOf("LEFT")
            3 -> listOf("RIGHT")
            4 -> listOf("UTURN", "U_TURN", "TURNBACK", "TURN_BACK")
            5 -> listOf("BRANCH", "RAMP", "KEEP", "FORK", "MERGE")
            6 -> listOf("ISLAND", "ROUND")
            7 -> listOf("DEST", "VIA", "ARRIVE")
            else -> emptyList()
        }
        val names = directionNames.filter { name -> tokens.isEmpty() || tokens.any { name.contains(it) } }
        direction.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
    }
    private fun record(value: String) {
        Log.i("NavigationHudLab", value)
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            entries.addLast("${System.currentTimeMillis()} $value")
            while (entries.size > 250) entries.removeFirst()
            output.text = entries.joinToString("\n")
        }
    }
    private fun task(action: () -> Unit) {
        worker.execute { runCatching(action).onFailure { record("ERROR ${it.cause ?: it}") } }
    }
    private fun startCapture() {
        if (listening) return
        try {
            val filter = IntentFilter("AUTONAVI_STANDARD_BROADCAST_SEND")
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, filter)
            }
            listening = true
            record("开始监听；RX 来源未经认证，也包含本页 TX；未采集 JSON/Binder 通道")
        } catch (error: Exception) { record("监听失败 $error") }
    }
    private fun stopCapture() {
        if (listening) { unregisterReceiver(receiver); listening = false; record("停止采集") }
    }
    private fun armStatusCapture(target: String) {
        startCapture()
        if (listening) {
            captureStatusAs = target
            record("等待下一条10019作为$target 候选；请在高德操作并核对，候选不自动发送")
        }
    }
    private fun guidanceReady(): Boolean = runCatching {
        require(JSONObject(json.text.toString()).getInt("KEY_TYPE") == 10001) {
            "请先将广播编辑框恢复为 KEY_TYPE=10001 的引导报文"
        }
        true
    }.getOrElse { record("对照组未发送：$it"); false }

    private fun sendStatus(begin: Boolean): Boolean {
        // Prevent our own TX from being recorded as an independently observed AMap status.
        captureStatusAs = null
        val stateInput = if (begin) beginState else endState
        val detailInput = if (begin) beginDetails else endDetails
        val value = stateInput.text.toString().toIntOrNull()
        val detail = detailInput.text.toString().toIntOrNull()
        if (value == null || detail == null) {
            record("未发送：请先采集或填写确认的${if (begin) "开始" else "结束"}状态整数")
            return false
        }
        return runCatching {
            sendBroadcast(Intent("AUTONAVI_STANDARD_BROADCAST_SEND")
                .putExtra("KEY_TYPE", 10019).putExtra("EXTRA_STATE", value)
                .putExtra("EXTRA_STATUS_DETAILS", detail))
            record("TX ${if (begin) "开始" else "结束"} state=$value details=$detail [$trial]；仅已提交，依赖及清除待实测")
            true
        }.getOrElse { record("状态发送失败 $it"); false }
    }
    private fun sendProtocol() {
        runCatching {
            val fields = JSONObject(json.text.toString())
            require(fields.length() in 1..64) { "字段数量须为 1–64" }
            val intent = Intent("AUTONAVI_STANDARD_BROADCAST_SEND")
            fields.keys().forEach { key ->
                when (val value = fields.get(key)) {
                    is Int -> intent.putExtra(key, value)
                    is Boolean -> intent.putExtra(key, value)
                    is String -> { require(value.length <= 1024); intent.putExtra(key, value) }
                    else -> error("$key 仅支持32位整数、布尔、字符串")
                }
            }
            captureStatusAs = null
            sendBroadcast(intent)
            record("TX broadcast $fields；已提交，TPS接收及屏幕显示未确认")
        }.onFailure { record("广播失败 $it") }
    }
    private fun probe() = task {
        val type = controllerClass
        type.getMethod("getInstance").invoke(null)
        val fields = type.fields.filter { it.name.startsWith("METER_") && java.lang.reflect.Modifier.isStatic(it.modifiers) }
        fields.sortedBy { it.name }.forEach { record("CONST ${it.name}=${it.get(null)}") }
        val names = fields.map { it.name }.filter { it.startsWith("METER_GUIDE_DIRECTION_") }.sorted()
        runOnUiThread {
            if (!isDestroyed) {
                directionNames = names
                filterDirections()
                fun populate(view: Spinner, prefix: String, default: String) {
                    view.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                        fields.map { it.name }.filter { it.startsWith(prefix) }.sorted())
                    select(view, default)
                }
                populate(guideState, "METER_GUIDE_STATE_", "METER_GUIDE_STATE_TBT")
                populate(naviState, "METER_NAVI_STATE_", "METER_NAVI_STATE_INGUIDE")
                populate(arrival, "METER_ARRIVE_EVENT_TYPE_", "METER_ARRIVE_EVENT_TYPE_INVALID")
                populate(source, "METER_NAVI_INFO_TYPE_", "METER_NAVI_INFO_TYPE_AUTONAVI")
            }
        }
        record("Meter 单例可用；方向数=${names.size}；尚未下发")
    }
    private fun pushMeter() {
        val selected = direction.selectedItem?.toString() ?: return record("请先探测并选择方向")
        val meters = distance.text.toString().toIntOrNull()?.takeIf { it in 0..1_000_000 }
            ?: return record("距离须为 0–1000000 米")
        val selectedGuide = guideState.selectedItem?.toString() ?: return record("请先探测引导状态")
        val selectedNavi = naviState.selectedItem?.toString() ?: return record("请先探测导航状态")
        val selectedArrival = arrival.selectedItem?.toString() ?: return record("请先探测到达事件")
        val selectedSource = source.selectedItem?.toString() ?: return record("请先探测来源")
        val exit = roundabout.text.toString().toIntOrNull()?.takeIf { it in 0..9 }
            ?: return record("环岛出口须为 0–9")
        val nextRoad = road.text.toString().take(128)
        task {
            val ctl = controllerClass
            val controller = ctl.getMethod("getInstance").invoke(null)
            val infoType = Class.forName("jp.pioneer.ceam.MeterService.MeterNaviInfo")
            val info = infoType.getConstructor().newInstance()
            fun number(setter: String, constant: String) {
                infoType.getMethod(setter, Int::class.javaPrimitiveType).invoke(info, ctl.getField(constant).getInt(null))
            }
            number("setNaviInfoType", selectedSource)
            number("setNaviState", selectedNavi)
            number("setGuideState", selectedGuide)
            number("setGuideDir", selected)
            number("setArriveEventType", selectedArrival)
            number("setMapDrawingMode", "METER_MAP_DRAWING_MODE_INVALID")
            infoType.getMethod("setRoundAboutNum", Int::class.javaPrimitiveType).invoke(info, exit)
            infoType.getMethod("setVehicleDir", Float::class.javaPrimitiveType).invoke(info, 0f)
            infoType.getMethod("setNextRdName", String::class.java).invoke(info, nextRoad)
            infoType.getMethod("setNextRdDistance", Int::class.javaPrimitiveType).invoke(info, meters)
            ownsMeter = true
            val result = ctl.getMethod("setNaviInfo", infoType).invoke(controller, info)
            record("TX Meter $selected road=$nextRoad meters=$meters accepted=$result state=$selectedNavi guide=$selectedGuide arrival=$selectedArrival source=$selectedSource exit=$exit；到达事件是否下发及仪表/HUD待实测")
        }
    }
    private fun clearMeter() {
        if (!ownsMeter) { record("本页未发送 Meter，跳过全局 onMapExit"); return }
        val ctl = controllerClass
        val result = ctl.getMethod("onMapExit").invoke(ctl.getMethod("getInstance").invoke(null))
        if (result == true) ownsMeter = false
        record("onMapExit=$result；屏幕清除待确认")
    }
    override fun onDestroy() {
        stopCapture()
        worker.execute { runCatching { clearMeter() }.onFailure { Log.e("NavigationHudLab", "清理失败", it) } }
        worker.shutdown()
        super.onDestroy()
    }
}
