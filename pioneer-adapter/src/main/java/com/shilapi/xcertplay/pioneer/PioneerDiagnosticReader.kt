package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.os.SystemClock
import java.io.File

/** Read-only, bounded diagnostic commands. Never uses the Bluetooth JNI adapter or repairs state. */
object PioneerDiagnosticReader {
    private val commands = linkedMapOf(
        "identity" to "id",
        "logcat" to "logcat -b main -b system -b crash -d -t 2500 -v threadtime '*:S' 'DiPlay:I' 'DiPlayUpdate:I' 'Pioneer:I' 'AndroidRuntime:E' 'DEBUG:E' 'ActivityManager:W' 'WifiStateMachine:I' 'WifiService:I' 'wpa_supplicant:I' 'hostapd:I' 'dnsmasq:I' 'Blueforce:I' 'BlueforceService:I' 'BluetoothAdapter:I' 'BluetoothJni:I' 'Tethering:I' 'WifiController:I' 'ConnectivityService:I'",
        "interfaces" to "ip addr show",
        "routes" to "ip route show table all",
        "rules" to "ip rule show",
        "firewall" to "iptables-save",
        "firewall6" to "ip6tables-save",
        "services" to "service list",
        "processes" to "ps",
        "wifi_link" to "iw dev",
    )

    data class Item(val name: String, val status: String, val text: String)

    @Synchronized fun collect(context: Context): List<Item> {
        if (!CamryAdaptation.enabled()) return listOf(Item("root", "unavailable", "Camry adaptation disabled"))
        val directory = File(context.filesDir, "diagnostic-root-${java.util.UUID.randomUUID()}")
        check(directory.mkdir())
        return try {
            val script = File(directory, "read.sh")
            // Precreate app-owned files. Root redirection preserves ownership; no private system files are copied.
            commands.keys.forEach { name -> File(directory, "$name.txt").writeText(""); File(directory, "$name.rc").writeText("") }
            val done = File(directory, "done").apply { writeText("") }
            val root = PioneerRootDispatcher.quote(directory.absolutePath)
            script.writeText(buildString {
                append("#!/system/bin/sh\nulimit -f 4096 || exit 1\ncd $root || exit 1\n")
                commands.forEach { (name, command) ->
                    append("( $command ) > $name.txt 2>&1 &\np=\$!\n")
                    append("( sleep 3; kill \$p 2>/dev/null ) &\nw=\$!\n")
                    append("wait \$p\nr=\$?\nkill \$w 2>/dev/null\necho \$r > $name.rc\n")
                }
                append("echo complete > done\n")
            })
            val dispatchFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val command = "/system/bin/sh ${PioneerRootDispatcher.quote(script.absolutePath)} </dev/null >/dev/null 2>&1 &"
            Thread({ runCatching { PioneerRootDispatcher.dispatch(command) }.onFailure(dispatchFailure::set) }, "diagnostic-root-launch")
                .apply { isDaemon = true; start() }
            val deadline = SystemClock.elapsedRealtime() + 40_000
            while (done.length() == 0L && dispatchFailure.get() == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            // Paths are never reused. Once collection ends, remove raw data even on timeout;
            // a delayed launcher then cannot open its script or create files in the deleted directory.
            val identity = File(directory, "identity.txt").readText().take(512)
            val isRoot = Regex("\\buid=0\\b").containsMatchIn(identity)
            commands.keys.map { name ->
                val file = File(directory, "$name.txt")
                val rc = File(directory, "$name.rc").readText().trim()
                val text = file.inputStream().use { input ->
                    val buffer = ByteArray(minOf(file.length(), 2L * 1024 * 1024).toInt())
                    var count = 0
                    while (count < buffer.size) { val n = input.read(buffer, count, buffer.size - count); if (n < 0) break; count += n }
                    String(buffer, 0, count, Charsets.UTF_8)
                }
                Item(name, when {
                    dispatchFailure.get() != null -> "unavailable"
                    rc.isEmpty() -> "timeout"
                    !isRoot -> "permission_denied"
                    rc != "0" -> "command_failed"
                    file.length() > 2L * 1024 * 1024 -> "truncated"
                    text.isBlank() -> "empty"
                    else -> "success"
                }, text)
            }
        } finally { directory.deleteRecursively() }
    }
}
