package com.shilapi.xcertplay

/**
 * Which OS components belong in the full diagnostic archive.
 *
 * Bluetooth and Blueforce binaries are intentionally excluded: the report keeps
 * the small read-only Bluetooth state snapshot and filtered logs, but does not
 * copy the vendor Bluetooth stack, APKs, or compiled code.
 */
internal object DiagnosticComponentPolicy {
    private val excluded = Regex("(?i).*(blueforce|bluetooth).*")
    private val libraryName = Regex("(?i).*(pioneer|ceam|wifi|tether).*(\\.so|\\.jar)$")
    private val packageName = Regex("(?i).*(pioneer|ceam|wifi|tether).*")

    val explicitFiles: List<String> = listOf(
        "/system/framework/framework.jar",
        "/system/framework/services.jar",
        "/system/bin/hostapd",
        "/system/bin/wpa_supplicant",
        "/system/bin/dnsmasq",
    )

    fun isSelectedLibrary(name: String): Boolean =
        !excluded.matches(name) && libraryName.matches(name)

    fun isSelectedPackage(name: String): Boolean =
        !excluded.matches(name) && packageName.matches(name)
}
