package com.shilapi.xcertplay.update

import java.io.IOException
import java.net.URL
import java.net.URLConnection

internal sealed interface UpdateLookupResult {
    data class Available(val release: UpdateRelease) : UpdateLookupResult
    data object UpToDate : UpdateLookupResult
    data object ChannelUnavailable : UpdateLookupResult
}

internal object UpdateReleaseLookup {
    fun latest(
        installedVersionCode: Long,
        userAgent: String,
        channel: UpdateChannel,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
        clientId: String? = null,
    ): UpdateLookupResult {
        val json = try {
            UpdateClient.fetchText(
                UpdateClient.releasesUrl(channel, clientId),
                "application/json",
                userAgent,
                openConnection,
            )
        } catch (notFound: HttpStatusException) {
            if (notFound.statusCode == 404) return UpdateLookupResult.ChannelUnavailable
            throw notFound
        }
        val release = UpdateCatalog.parse(json) ?: throw IOException("Malformed update manifest")
        return if (UpdateVersion.isNewer(release.versionCode, installedVersionCode)) {
            UpdateLookupResult.Available(release)
        } else {
            UpdateLookupResult.UpToDate
        }
    }
}
