package com.shilapi.xcertplay.update

import android.content.Context
import android.os.Bundle
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateChannelTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private var originalMetaData: Bundle? = null

    @Before fun saveMetaData() {
        originalMetaData = appInfo().metaData
    }

    @After fun clear() {
        UpdateChannelStore.clearForTest(context)
        appInfo().metaData = originalMetaData
    }

    @Test fun stableIsTheFallbackWithoutBuildMetadataAndTheChoicePersists() {
        assertEquals(UpdateChannel.STABLE, UpdateChannelStore.load(context))

        UpdateChannelStore.save(context, UpdateChannel.BETA)

        assertEquals(UpdateChannel.BETA, UpdateChannelStore.load(context))
    }

    @Test fun theBuildManifestProvidesTheDefaultUntilTheUserChooses() {
        appInfo().metaData = Bundle().apply {
            putString(UpdateChannelStore.BUILD_CHANNEL_META_DATA, "beta")
        }

        assertEquals(UpdateChannel.BETA, UpdateChannelStore.load(context))

        UpdateChannelStore.save(context, UpdateChannel.STABLE)
        assertEquals(UpdateChannel.STABLE, UpdateChannelStore.load(context))
    }

    @Test fun eachChannelUsesItsOwnLatestManifest() {
        assertTrue(UpdateClient.releasesUrl(UpdateChannel.STABLE).endsWith("/android/stable/latest"))
        assertTrue(UpdateClient.releasesUrl(UpdateChannel.BETA).endsWith("/android/beta/latest"))
    }

    private fun appInfo() = requireNotNull(
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)?.applicationInfo,
    )
}
