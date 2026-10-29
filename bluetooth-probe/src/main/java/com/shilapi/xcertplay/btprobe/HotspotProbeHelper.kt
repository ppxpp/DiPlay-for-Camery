package com.shilapi.xcertplay.btprobe

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.wifi.WifiConfiguration
import android.os.IBinder
import android.os.Process
import android.os.ResultReceiver
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Root-only helper, launched as app_process by pioneer.pfilesdoperator.
 * It drives the vendor SoftAP, injects the vendor `frequency` band selector,
 * installs the CARNET rules for bcm1, and writes a fixed result file for the probe UI.
 */
object HotspotProbeHelper {
    private const val MAX_LINES = 6000
    private const val MAX_OUTPUT = 30_000
    private const val RULE_TAG = "btprobe-hotspot"

    @JvmStatic
    fun main(args: Array<String>) {
        val lines = mutableListOf<String>()
        val log: (String) -> Unit = { message ->
            if (lines.size < MAX_LINES) lines += message
        }
        val output = args.getOrNull(2)?.let(::File)
        try {
            val action = args.getOrNull(0)?.let(HotspotProbeAction::valueOf)
                ?: error("missing action")
            val packageName = args.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?: "com.tencent.wecarflow"
            check(Process.myUid() == 0) { "helper is not root: uid=${Process.myUid()}" }
            check(output != null) { "missing output file" }
            log("uid=${Process.myUid()} pid=${Process.myPid()} action=${action.name} package=$packageName")
            when (action) {
                HotspotProbeAction.DIAG -> diag(log)
                HotspotProbeAction.START -> start(packageName, log)
                HotspotProbeAction.FIVE_GHZ -> fiveGhz(log)
                HotspotProbeAction.FIREWALL -> firewall(log)
                HotspotProbeAction.STATUS -> status(log)
                HotspotProbeAction.STOP -> stop(log)
            }
            write(output, lines, "OK")
        } catch (failure: Throwable) {
            log("ERROR ${failure.javaClass.simpleName}: ${failure.message}")
            if (output != null) runCatching { write(output, lines, "ERROR") }
        }
        System.exit(0)
    }

    private fun start(packageName: String, log: (String) -> Unit) {
        val wifi = wifi()
        log("apState before=${apState(wifi)}")
        log("apConfig ssid=${apSsid(wifi)}")
        val connectivity = connectivity()
        val method = connectivity.javaClass.methods.firstOrNull {
            it.name == "startTethering" && it.parameterTypes.size == 4 &&
                it.parameterTypes[0] == Integer.TYPE
        } ?: connectivity.javaClass.methods.firstOrNull {
            it.name == "startTethering" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Integer.TYPE
        } ?: error("IConnectivityManager.startTethering missing")
        log("call ${method.name} params=${method.parameterTypes.size} package=$packageName")
        if (method.parameterTypes.size == 4) {
            method.invoke(connectivity, 0, ResultReceiver(null), false, packageName)
        } else {
            method.invoke(connectivity, 0, ResultReceiver(null), false)
        }
        awaitApState(wifi, 13, 15_000, log)
        logShell(log, "ip link", "ip link")
        logShell(log, "bcm1 address", "ip -4 addr show bcm1; ip -6 addr show bcm1")
        logShell(log, "supplicant", "ps | grep wpa_supplicant")
        logShell(log, "dnsmasq", "ps | grep dnsmasq")
    }

    private fun stop(log: (String) -> Unit) {
        removeFirewall(log)
        val connectivity = connectivity()
        val method = connectivity.javaClass.methods.firstOrNull {
            it.name == "stopTethering" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Integer.TYPE
        }
        if (method != null) {
            log("call stopTethering type=0")
            runCatching { method.invoke(connectivity, 0) }
                .onFailure { log("stopTethering failed: ${it.message}") }
        } else {
            log("stopTethering(int) missing; fallback setWifiApEnabled(false)")
        }
        val wifi = wifi()
        if (apState(wifi) != 11) {
            runCatching {
                Class.forName("android.net.wifi.IWifiManager")
                    .getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(wifi, null, false)
                log("fallback setWifiApEnabled(false) invoked")
            }.onFailure { log("fallback setWifiApEnabled failed: ${it.message}") }
        }
        awaitApState(wifi, 11, 8_000, log)
        log("apState after=${apState(wifi)}")
    }

    private fun fiveGhz(log: (String) -> Unit) {
        val iface = requireApInterface(log)
        check(apState(wifi()) == 13) { "厂商热点未启用" }
        var supplicant = Supplicant.open(log) ?: error("supplicant socket unavailable")
        try {
            val before = supplicant.command("IFNAME=$iface STATUS")
            log("AP before=$before")
            if (isFiveGhz(before)) { ensureApRoute(iface,log); log("AP already reports 5GHz; kernel frequency requires independent audit"); return }
            check(supplicant.command("IFNAME=$iface SET_NETWORK 0 frequency 5") == "OK")
            // SELECT on an already running AP does not restart the radio. A disable/enable cycle
            // is required. This firmware may take longer than a control-socket timeout to stop AP.
            try { log("AP disable=${supplicant.command("IFNAME=$iface DISABLE_NETWORK 0")}") }
            catch (failure: java.io.IOException) { log("AP disable reply timeout; reopen socket and verify state (${failure.javaClass.simpleName})") }
            supplicant.close()
            supplicant = Supplicant.open(log) ?: error("cannot reopen supplicant socket")
            val deadline = System.currentTimeMillis() + 30_000
            var idle = false
            while (System.currentTimeMillis() < deadline) {
                val state = supplicant.command("IFNAME=$iface STATUS")
                if (state.lineSequence().any { it == "wpa_state=DISCONNECTED" || it == "wpa_state=INACTIVE" }) { idle = true; break }
                Thread.sleep(500)
            }
            check(idle) { "AP未停止；不继续修改频率" }
            check(supplicant.command("IFNAME=$iface SET_NETWORK 0 frequency 5") == "OK")
            check(supplicant.command("IFNAME=$iface ENABLE_NETWORK 0") == "OK")
            check(supplicant.command("IFNAME=$iface SELECT_NETWORK 0") == "OK")
            Thread.sleep(2000)
            val current = supplicant.command("IFNAME=$iface STATUS")
            log("AP after=$current")
            check(current.contains("mode=AP") && current.contains("wpa_state=COMPLETED") && isFiveGhz(current)) {
                "AP未报告5GHz；以实际状态为准"
            }
            ensureApRoute(iface,log)
            log("5GHz AP supplicant verified; exact hardware channel requires independent nl80211 audit")
        } finally { supplicant.close() }
    }

    private fun ensureApRoute(iface: String, log: (String) -> Unit) {
        check(iface == "bcm1") { "unsupported AP interface" }
        val address = Regex("inet ([0-9.]+)/([0-9]+)").find(sh("ip -4 addr show dev $iface"))
            ?: error("AP缺少IPv4地址")
        val bytes = java.net.InetAddress.getByName(address.groupValues[1]).address
        val bits = address.groupValues[2].toInt()
        check(bytes.size == 4 && bits in 8..30) { "unsupported AP subnet" }
        val host = bytes.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
        val network = host and ((0xffffffffL shl (32-bits)) and 0xffffffffL)
        val prefix = (3 downTo 0).joinToString(".") { ((network ushr (it*8)) and 255).toString() } + "/$bits"
        val expected = "$prefix dev $iface"
        if (!sh("ip -4 route show table local_network").contains(expected)) {
            log("恢复频段切换时丢失的AP本地路由 prefix=$prefix iface=$iface")
            log(sh("/system/bin/ndc network route add local $iface $prefix"))
        }
        check(sh("ip -4 route show table local_network").contains(expected)) { "AP本地路由恢复失败" }
        log("AP本地网络路由已核实 $expected")
    }

    private fun firewall(log: (String) -> Unit) {
        val iface = requireApInterface(log)
        log("AP interface=$iface")
        ensureApRoute(iface,log)
        for (binary in listOf("/system/bin/iptables", "/system/bin/ip6tables")) {
            for (chain in listOf("CARNET_IN", "CARNET_OUT")) {
                val direction = if (chain == "CARNET_IN") "-i" else "-o"
                val comment = "$RULE_TAG-$chain"
                val rule = "$binary -t filter -C $chain $direction $iface " +
                    "-m comment --comment $comment -j ACCEPT"
                val existing = sh(rule)
                log("$rule\n$existing")
                if (!existing.contains("[exit=0]")) {
                    val insert = "$binary -t filter -I $chain 1 $direction $iface " +
                        "-m comment --comment $comment -j ACCEPT"
                    log("$insert\n${sh(insert)}")
                    log("verify\n${sh(rule)}")
                }
            }
        }
        logShell(log, "CARNET_IN", "/system/bin/iptables -nvL CARNET_IN")
        logShell(log, "CARNET_OUT", "/system/bin/iptables -nvL CARNET_OUT")
        logShell(log, "CARNET_IN6", "/system/bin/ip6tables -nvL CARNET_IN")
        logShell(log, "CARNET_OUT6", "/system/bin/ip6tables -nvL CARNET_OUT")
    }

    private fun removeFirewall(log: (String) -> Unit) {
        val iface = findApInterface(log)
        if (iface == null || iface == "wlan0") {
            log("remove firewall skipped: no dedicated AP interface")
            return
        }
        for (binary in listOf("/system/bin/iptables", "/system/bin/ip6tables")) {
            for (chain in listOf("CARNET_IN", "CARNET_OUT")) {
                val direction = if (chain == "CARNET_IN") "-i" else "-o"
                val comment = "$RULE_TAG-$chain"
                val rule = "$binary -t filter -D $chain $direction $iface " +
                    "-m comment --comment $comment -j ACCEPT"
                log("$rule\n${sh(rule)}")
            }
        }
    }

    private fun status(log: (String) -> Unit) {
        val wifi = wifi()
        log("apState=${apState(wifi)} apSsid=${apSsid(wifi)}")
        logShell(log, "ip link", "ip link")
        logShell(log, "addresses", "ip -4 addr; ip -6 addr")
        logShell(log, "arp", "ip neigh")
        logShell(log, "wlan chanspecs", "wl -i wlan0 chanspecs")
        logShell(log, "country channels", "wl -i wlan0 channels_in_country CN")
        logShell(log, "bcm1 chanspec", "wl -i bcm1 chanspec")
        logShell(log, "dnsmasq", "ps | grep dnsmasq")
        logShell(log, "supplicant", "ps | grep wpa_supplicant")
        logShell(log, "CARNET_IN", "/system/bin/iptables -S CARNET_IN")
        logShell(log, "CARNET_OUT", "/system/bin/iptables -S CARNET_OUT")
        logShell(log, "CARNET_IN6", "/system/bin/ip6tables -S CARNET_IN")
        logShell(log, "CARNET_OUT6", "/system/bin/ip6tables -S CARNET_OUT")
        val supplicant = Supplicant.open(log)
        if (supplicant != null) {
            try {
                val iface = findApInterface(log)
                if (iface != null) log("supplicant STATUS=${supplicant.command("IFNAME=$iface STATUS")}")
            } finally {
                supplicant.close()
            }
        }
    }

    private fun diag(log: (String) -> Unit) {
        status(log)
        logShell(log, "DHCP leases", "cat /data/misc/dhcp/dnsmasq.leases")
        logShell(log, "properties", "getprop | grep -E 'wifi|wlan|product.version'")
        logShell(log, "wifi config", "ls -l /data/misc/wifi")
        logShell(log, "sockets", "ls -l /data/misc/wifi/sockets")
    }

    private fun awaitApState(wifi: Any, target: Int, timeoutMillis: Long, log: (String) -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var last = -1
        while (System.currentTimeMillis() < deadline) {
            val state = runCatching { apState(wifi) }.getOrDefault(-1)
            if (state != last) {
                log("apState=$state")
                last = state
            }
            if (state == target) return
            Thread.sleep(200)
        }
        log("WARN apState did not reach $target; last=$last")
    }

    private fun wifi(): Any = Class.forName("android.net.wifi.IWifiManager\$Stub")
        .getMethod("asInterface", IBinder::class.java)
        .invoke(null, service("wifi") ?: error("wifi service missing"))
        ?: error("wifi interface null")

    private fun connectivity(): Any = Class.forName("android.net.IConnectivityManager\$Stub")
        .getMethod("asInterface", IBinder::class.java)
        .invoke(null, service("connectivity") ?: error("connectivity service missing"))
        ?: error("connectivity interface null")

    private fun service(name: String): IBinder? = Class.forName("android.os.ServiceManager")
        .getMethod("getService", String::class.java)
        .invoke(null, name) as? IBinder

    private fun apState(wifi: Any): Int = Class.forName("android.net.wifi.IWifiManager")
        .getMethod("getWifiApEnabledState")
        .invoke(wifi) as Int

    private fun apSsid(wifi: Any): String = runCatching {
        val config = Class.forName("android.net.wifi.IWifiManager")
            .getMethod("getWifiApConfiguration")
            .invoke(wifi) ?: return "null"
        config.javaClass.getField("SSID").get(config) as? String ?: "?"
    }.getOrDefault("error")

    private fun apPsk(wifi: Any): String = runCatching {
        val config = Class.forName("android.net.wifi.IWifiManager")
            .getMethod("getWifiApConfiguration")
            .invoke(wifi) ?: return ""
        config.javaClass.getField("preSharedKey").get(config) as? String ?: ""
    }.getOrDefault("")

    private fun findApInterface(log: (String) -> Unit): String? {
        val links = sh("ip link").lineSequence()
            .mapNotNull { Regex("^\\d+:\\s+([^:@]+)").find(it)?.groupValues?.get(1)?.trim() }
            .toList()
        log("interfaces=$links")
        return links.firstOrNull { it == "bcm1" }
            ?: links.firstOrNull { it.startsWith("bcm") }
            ?: links.firstOrNull { it.startsWith("p2p") }
            ?: links.firstOrNull { it == "wlan0" }
    }

    private fun requireApInterface(log: (String) -> Unit): String {
        val iface = findApInterface(log) ?: error("bcm1 AP interface not found; start the vendor hotspot first")
        check(iface != "wlan0") { "bcm1 absent; refusing to modify station wlan0" }
        return iface
    }

    private fun isFiveGhz(text: String): Boolean {
        if (text.contains("5GHz") || text.contains("5 GHz")) return true
        val freq = Regex("freq=(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (freq != null) return freq >= 5_000
        val channel = Regex("channel\\s+(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        return channel != null && channel > 14
    }

    private fun logShell(log: (String) -> Unit, label: String, command: String) {
        log("### $label")
        log("$ $command")
        log(sh(command))
    }

    private fun sh(command: String, timeoutSeconds: Long = 12): String {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val reader = java.util.concurrent.Executors.newSingleThreadExecutor()
        val result = reader.submit<String> { process.inputStream.bufferedReader().use { it.readText() }.take(MAX_OUTPUT) }
        return try {
            val text = result.get(timeoutSeconds, TimeUnit.SECONDS)
            process.waitFor()
            "$text\n[exit=${process.exitValue()}]"
        } catch (_: java.util.concurrent.TimeoutException) {
            process.destroy()
            result.cancel(true)
            "[TIMEOUT]"
        } finally { reader.shutdownNow() }
    }

    private fun write(output: File, lines: List<String>, result: String) {
        output.parentFile?.mkdirs()
        val text = lines.joinToString("\n") + "\nRESULT: $result\n"
        val temp = File(output.path + ".tmp")
        FileOutputStream(temp).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        runCatching { Os.chmod(temp.path, 0x180) } // 0666
        if (!temp.renameTo(output)) {
            FileOutputStream(output).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
        runCatching { Os.chmod(output.path, 0x180); Os.chown(output.path, output.parentFile!!.let { Os.stat(it.path).st_uid }, -1) }
    }

    private class Supplicant private constructor(private val socket: LocalSocket, private val log: (String) -> Unit) {
        private val input = socket.inputStream
        private val output = socket.outputStream

        fun command(command: String): String {
            log("supplicant command=${command.substringBefore(" psk ")}")
            output.write(command.toByteArray(Charsets.UTF_8))
            output.flush()
            val buffer = ByteArray(8192)
            val count = input.read(buffer)
            check(count > 0) { "supplicant returned no response" }
            return String(buffer, 0, count, Charsets.UTF_8).trim()
        }

        fun close() = runCatching { socket.close() }

        companion object {
            fun open(log: (String) -> Unit): Supplicant? {
                val candidates = listOf(
                    Triple("/data/misc/wifi/sockets/wpa_wlan0_cmd", LocalSocketAddress.Namespace.FILESYSTEM, "fs:wpa_wlan0_cmd"),
                    Triple("/data/misc/wifi/sockets/wpa_wlan0", LocalSocketAddress.Namespace.FILESYSTEM, "fs:wpa_wlan0"),
                    Triple("wpa_wlan0_cmd", LocalSocketAddress.Namespace.ABSTRACT, "abstract:wpa_wlan0_cmd"),
                    Triple("wpa_wlan0", LocalSocketAddress.Namespace.ABSTRACT, "abstract:wpa_wlan0"),
                )
                for ((name, namespace, label) in candidates) {
                    try {
                        val socket = LocalSocket(LocalSocket.SOCKET_DGRAM)
                        socket.bind(LocalSocketAddress("btprobe-ap-${android.os.Process.myPid()}-${System.nanoTime()}", LocalSocketAddress.Namespace.ABSTRACT))
                        socket.connect(LocalSocketAddress(name, namespace))
                        socket.soTimeout = 10_000
                        log("supplicant socket=$label")
                        return Supplicant(socket, log)
                    } catch (failure: Throwable) {
                        log("supplicant socket $label failed ${failure.javaClass.simpleName}: ${failure.message}")
                    }
                }
                return null
            }
        }
    }
}
