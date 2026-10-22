package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Process
import android.os.SystemClock
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Fixed USB authorization actions shared by diagnostics and the Pioneer connection worker. */
object PioneerUsbPermissionProbe {
    private const val PREF = "usb_permission_probe_hold"
    private val actions = setOf("STATUS", "HOLD", "OFF", "INSPECT", "GRANT", "CLEAR_DEFAULT", "SET_DEFAULT")
    fun holdsRequests(context: Context): Boolean = PioneerUsbPolicy.matchesDevice() &&
        context.getSharedPreferences("pioneer-debug", 0).getBoolean(PREF, false)

    fun status(context: Context): JSONObject {
        val usb = context.getSystemService(UsbManager::class.java)
        val devices = usb?.deviceList?.values.orEmpty().filter { it.vendorId == 0x05ac }
        val last = File(context.filesDir, "pioneer-debug/usb-permission-probe.json")
        return JSONObject().put("holdRequests", holdsRequests(context)).put("package", context.packageName)
            .put("appUid", context.applicationInfo.uid).put("appPid", Process.myPid())
            .put("devices", JSONArray(devices.map { describe(usb!!, it) }))
            .put("lastProbe", runCatching { if (last.length() in 1..32768) JSONObject(last.readText()) else JSONObject() }.getOrDefault(JSONObject()))
    }

    fun run(context: Context, action: String, node: String? = null): JSONObject {
        require(action in actions) { "Unsupported USB permission probe action" }
        val app = context.applicationContext
        if (action == "STATUS") return status(app)
        return PioneerDebugUpdateGuard.withCacheMaintenance { execute(app, action, node) }
    }

    /** Connection worker only: initial native-NCM authorization may already be done by module prewarm. */
    fun grantForConnection(context: Context, device: UsbDevice, stillCurrent: () -> Boolean): JSONObject {
        check(PioneerUsbPolicy.matchesDevice() && !holdsRequests(context)) { "Automatic USB grant unavailable or held" }
        val app = context.applicationContext
        val usb = app.getSystemService(UsbManager::class.java)
        val identity = PioneerUsbPermissionHelper.identity(device)
        return execute(app, "AUTO_GRANT", device.deviceName) {
            stillCurrent() && !holdsRequests(app) && runCatching {
                usb.deviceList[device.deviceName]?.let {
                    it.deviceId == device.deviceId && PioneerUsbPermissionHelper.identity(it) == identity
                } == true
            }.getOrDefault(false)
        }
    }

    private fun execute(app: Context, action: String, node: String?, stillCurrent: () -> Boolean = { true }): JSONObject {
        check(stillCurrent()) { "USB connection cancelled" }

        if (action == "HOLD" || action == "OFF") {
            if (action == "HOLD") check(PioneerUsbPolicy.matchesDevice()) { "Verified Pioneer firmware required" }
            check(app.getSharedPreferences("pioneer-debug", 0).edit().putBoolean(PREF, action == "HOLD").commit())
            PioneerDebugRuntime.log(app, "USB_PERMISSION_PROBE holdRequests=${action == "HOLD"}; existing grants unchanged")
            return status(app)
        }
        check(PioneerUsbPolicy.matchesDevice()) { "Verified Pioneer firmware required" }
        PioneerNativeNcmPrewarm.requireSupportedFirmware() // Catalog check only; no driver load.
        val usb = app.getSystemService(UsbManager::class.java) ?: error("USB service unavailable")
        val device = selectDevice(usb.deviceList.values.toList(), node)
        val before = usb.hasPermission(device)
        if (action == "GRANT" && before) {
            return save(app, JSONObject().put("action", action).put("permissionBefore", true)
                .put("permissionAfter", true).put("verdict", "ALREADY_GRANTED_NOT_A_PROOF"))
        }
        val identity = PioneerUsbPermissionHelper.identity(device)
        val token = UUID.randomUUID().toString()
        val root = File(app.filesDir, "usb-permission-probe").apply { mkdirs() }
        Os.chmod(root.path, 0x1c0)
        val directory = File(root, token).apply { check(mkdir()) }
        Os.chmod(directory.path, 0x1c0)
        val unified = action == "AUTO_GRANT" && PioneerWiredBackend.usesVehicleAdaptation(app) &&
            PioneerWiredBackend.mode(app) == PioneerWiredBackend.Mode.VPN
        if (unified) {
            PioneerConnectionTasks.update("permission-request", "RUNNING", "waiting-root")
            PioneerRootSession.ready(app, stillCurrent)
            PioneerConnectionTasks.update("permission-request", "RUNNING")
        }
        val deadline = SystemClock.elapsedRealtime() + 15000
        val boot = File("/proc/sys/kernel/random/boot_id").readText().trim()
        val appStart = File("/proc/self/stat").readText().substringAfterLast(") ").split(' ')[19]
        val request = JSONObject().put("token", token).put("action", action).put("package", app.packageName)
            .put("uid", app.applicationInfo.uid).put("appPid", Process.myPid()).put("appStart", appStart)
            .put("boot", boot).put("requestedAtMs", SystemClock.elapsedRealtime()).put("deadline", deadline).put("node", device.deviceName)
            .put("deviceId", device.deviceId).put("vid", device.vendorId).put("pid", device.productId)
            .put("identityHash", identity)
        val input = File(directory, "request.json"); input.writeText(request.toString()); Os.chmod(input.path, 0x180)
        val report = JSONObject().put("token", token).put("action", action).put("permissionBefore", before)
            .put("appUid", app.applicationInfo.uid).put("identityHash", identity).put("startedAt", System.currentTimeMillis())
        val dispatchError = AtomicReference<Throwable?>()
        val command = "CLASSPATH=${quote(app.applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
            "com.shilapi.xcertplay.pioneer.PioneerUsbPermissionHelper ${quote(directory.path)} </dev/null >/dev/null 2>&1 &"
        val started = SystemClock.elapsedRealtime()
        Thread({ runCatching {
            if (unified) PioneerRootSession.submit(app, directory, stillCurrent) else dispatch(command)
        }.onFailure(dispatchError::set) }, "usb-permission-probe-launch")
            .apply { isDaemon = true; start() }
        try {
            var helper: JSONObject? = null
            while (SystemClock.elapsedRealtime() < deadline + 500) {
                check(stillCurrent()) { "USB connection cancelled" }
                val output = File(directory, "result.json")
                helper = runCatching { if (output.length() in 1..32768) JSONObject(output.readText()) else null }.getOrNull()
                if (helper != null) break
                dispatchError.get()?.let { throw IllegalStateException("Pioneer service rejected probe dispatch", it) }
                Thread.sleep(50)
            }
            if (helper == null) error("No helper result before deadline; root capability is unconfirmed")
            check(helper.getString("token") == token) { "Probe token mismatch" }
            report.put("helper", helper)
            if (action == "AUTO_GRANT") {
                check(helper.optInt("targetUid", -1) == app.applicationInfo.uid &&
                    helper.optString("identityHash") == identity && helper.optInt("helperUid", -1) == 0 &&
                    helper.optString("code") == "COMPLETED" && helper.optBoolean("targetPermissionVerified")) { "USB_TARGET_PERMISSION_UNCONFIRMED" }
                report.put("permissionAfter", true).put("verdict", "AUTO_GRANT_VERIFIED")
            } else {
            val current = usb.deviceList[device.deviceName]
            val unchanged = current != null && current.deviceId == device.deviceId &&
                runCatching { PioneerUsbPermissionHelper.identity(current) == identity }.getOrDefault(false)
            report.put("sameDeviceInstance", unchanged)
            val after = unchanged && usb.hasPermission(current!!)
            report.put("permissionAfter", after)
            var opened = false
            if (action in setOf("GRANT", "AUTO_GRANT") && unchanged && after && helper.optString("code") == "COMPLETED") {
                usb.openDevice(current!!)?.let { connection ->
                    try { opened = true } finally { connection.close() }
                }
                report.put("appOpenSucceeded", opened) // Ordinary app process, no claim or I/O.
            }
            report.put("verdict", verdict(action, before, unchanged, after, opened, helper))
            }
        } catch (failure: Throwable) {
            File(directory, "cancel").writeText("cancel")
            report.put("verdict", "ERROR_OR_UNCONFIRMED").put("error", "${failure.javaClass.simpleName}: ${failure.message}")
        } finally {
            File(directory, "cancel").writeText("cancel")
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
        }
        File(directory, "app-report.json").writeText(report.toString())
        return save(app, report)
    }

    internal fun selectDevice(devices: List<UsbDevice>, node: String?): UsbDevice {
        val selected = devices.filter { it.vendorId == 0x05ac && (node == null || it.deviceName == node) }
        require(selected.size == 1) { "Expected one selected Apple device; specify its node if multiple are present" }
        return selected.single()
    }

    internal fun verdict(action: String, before: Boolean, unchanged: Boolean, after: Boolean,
                         opened: Boolean, helper: JSONObject): String = when {
        !unchanged -> "DEVICE_CHANGED_NO_PROOF"
        helper.optInt("helperUid", -1) != 0 || helper.optString("code") != "COMPLETED" -> "HELPER_FAILED"
        action == "AUTO_GRANT" && helper.optBoolean("grantCallSucceeded") && after && opened -> "AUTO_GRANT_VERIFIED"
        action == "AUTO_GRANT" -> "GRANT_NOT_VERIFIED"
        action == "GRANT" && before -> "ALREADY_GRANTED_NOT_A_PROOF"
        action == "GRANT" && helper.optBoolean("grantCallSucceeded") && after && opened -> "GRANT_VERIFIED"
        action == "GRANT" -> "GRANT_NOT_VERIFIED"
        action == "SET_DEFAULT" && helper.optBoolean("defaultSetVerified") -> "DEFAULT_SET_REPLUG_REQUIRED"
        action == "SET_DEFAULT" -> "DEFAULT_SET_NOT_CONFIRMED"
        action == "CLEAR_DEFAULT" && helper.optBoolean("defaultClearVerified") -> "DEFAULT_CLEARED_REPLUG_REQUIRED"
        action == "CLEAR_DEFAULT" -> "DEFAULT_CLEAR_NOT_CONFIRMED"
        else -> "ROOT_INSPECTION_COMPLETED"
    }

    private fun describe(usb: UsbManager, device: UsbDevice): JSONObject = JSONObject()
        .put("node", device.deviceName).put("deviceId", device.deviceId).put("vid", device.vendorId)
        .put("pid", device.productId).put("hasPermission", usb.hasPermission(device))
        .put("stableIdentityAvailable", runCatching { PioneerUsbPermissionHelper.identity(device); true }.getOrDefault(false))

    private fun save(context: Context, report: JSONObject): JSONObject {
        report.put("recordedAt", System.currentTimeMillis()).put("holdRequests", holdsRequests(context))
        val root = File(context.filesDir, "pioneer-debug").apply { mkdirs() }
        val pending = File(root, "usb-permission-probe.tmp"); pending.writeText(report.toString())
        check(pending.renameTo(File(root, "usb-permission-probe.json")))
        PioneerDebugRuntime.log(context, "USB_PERMISSION_PROBE action=${report.optString("action")} verdict=${report.optString("verdict")} " +
            "before=${report.opt("permissionBefore")} after=${report.opt("permissionAfter")} open=${report.opt("appOpenSucceeded")} elapsedMs=${report.opt("elapsedMs")}")
        return report
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun dispatch(command: String) = PioneerRootDispatcher.dispatch(command)
}
