package com.shilapi.xcertplay.orchestration.progress

/** A task has its own lifecycle; phases do not pretend overlapping work is sequential. */
enum class ConnectionTaskState { PENDING, RUNNING, COMPLETE, REUSED, RETRYING, FAILED, CANCELLED, SKIPPED }
enum class ConnectionChannel { VPN, LWIP, NATIVE_NCM, WIRELESS, FRAMEWORK }
enum class ConnectionTask(val id: String, val step: ConnectionStep) {
    ROOT("root", ConnectionStep.PREPARATION), CONFIG("config", ConnectionStep.PREPARATION),
    DETECT("device-detect", ConnectionStep.PREPARATION), COMPONENTS("components", ConnectionStep.PREPARATION),
    BUFFERS("buffers", ConnectionStep.PREPARATION),
    PERMISSION_CHECK("permission-check", ConnectionStep.PERMISSIONS),
    PERMISSION_REQUEST("permission-request", ConnectionStep.PERMISSIONS), USB_PERMISSION("usb-permission", ConnectionStep.PERMISSIONS),
    WIRELESS_LINK("wireless-link", ConnectionStep.DEVICE),
    USB_OPEN("usb-open", ConnectionStep.DEVICE), USB_INTERFACES("usb-interfaces", ConnectionStep.DEVICE),
    PAIR("pair", ConnectionStep.CONTROL), AUTHENTICATE("authenticate", ConnectionStep.CONTROL),
    VPN_PERMISSION("vpn-permission", ConnectionStep.NETWORK), TUN("tun", ConnectionStep.NETWORK),
    TUN_INFO("tun-info", ConnectionStep.NETWORK), RULE_CHECK("rules-check", ConnectionStep.NETWORK),
    NETWORK_READY("network-ready", ConnectionStep.NETWORK), RULES("rules", ConnectionStep.NETWORK),
    DRIVER("driver", ConnectionStep.NETWORK), INTERFACE("interface", ConnectionStep.NETWORK),
    WIRELESS_NETWORK("wireless-network", ConnectionStep.NETWORK),
    STACK("stack", ConnectionStep.CHANNEL), IO("io-init", ConnectionStep.CHANNEL),
    BRIDGE("bridge", ConnectionStep.CHANNEL), CHANNEL_READY("channel-ready", ConnectionStep.CHANNEL),
    SESSION_AUTH("session-auth", ConnectionStep.SESSION),
    SESSION("session", ConnectionStep.SESSION), MEDIA("media", ConnectionStep.SESSION),
    VIDEO_RECEIVE("video-receive", ConnectionStep.VIDEO), VIDEO_DECODE("video-decode", ConnectionStep.VIDEO),
    VIDEO_RENDER("video-render", ConnectionStep.VIDEO);
}

data class ConnectionTaskSnapshot(val task: ConnectionTask, val state: ConnectionTaskState,
    val elapsedMillis: Long, val detail: String)

fun connectionTasks(channel: ConnectionChannel): List<ConnectionTask> = ConnectionTask.entries.filter { task ->
    when (task) {
        ConnectionTask.SESSION_AUTH -> channel != ConnectionChannel.WIRELESS
        ConnectionTask.ROOT -> channel == ConnectionChannel.VPN
        ConnectionTask.VPN_PERMISSION, ConnectionTask.TUN, ConnectionTask.TUN_INFO -> channel in setOf(ConnectionChannel.VPN, ConnectionChannel.FRAMEWORK)
        ConnectionTask.RULE_CHECK, ConnectionTask.NETWORK_READY, ConnectionTask.RULES -> channel == ConnectionChannel.VPN
        ConnectionTask.DRIVER, ConnectionTask.INTERFACE -> channel == ConnectionChannel.NATIVE_NCM
        ConnectionTask.WIRELESS_NETWORK -> channel == ConnectionChannel.WIRELESS
        ConnectionTask.STACK -> channel == ConnectionChannel.LWIP
        ConnectionTask.PERMISSION_CHECK, ConnectionTask.PERMISSION_REQUEST -> channel != ConnectionChannel.WIRELESS
        ConnectionTask.WIRELESS_LINK -> channel == ConnectionChannel.WIRELESS
        ConnectionTask.USB_PERMISSION, ConnectionTask.USB_OPEN, ConnectionTask.USB_INTERFACES -> channel != ConnectionChannel.WIRELESS
        ConnectionTask.IO, ConnectionTask.BUFFERS, ConnectionTask.BRIDGE -> channel in setOf(ConnectionChannel.VPN, ConnectionChannel.FRAMEWORK, ConnectionChannel.LWIP)
        else -> true
    }
}
