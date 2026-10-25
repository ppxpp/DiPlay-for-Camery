package com.shilapi.xcertplay.update

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29, 35])
class UpdateAvailabilityTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @After fun clear() = UpdateAvailability.clearAllForTest(context)

    @Test fun persistsAReleaseThatIsNewerThanTheInstalledBuild() {
        val release = release("v0.3.0", 100L)
        UpdateAvailability.save(context, release, UpdateChannel.STABLE)

        assertEquals(release, UpdateAvailability.available(context, 99L, UpdateChannel.STABLE))
    }

    @Test fun removesAReleaseAfterTheAppCatchesUp() {
        UpdateAvailability.save(context, release("v0.3.0", 100L), UpdateChannel.STABLE)

        assertNull(UpdateAvailability.available(context, 100L, UpdateChannel.STABLE))
        assertNull(UpdateAvailability.available(context, 101L, UpdateChannel.STABLE))
    }

    @Test fun ignoresAndClearsAReleaseFromAnotherChannel() {
        UpdateAvailability.save(context, release("v0.3.0-beta", 100L), UpdateChannel.BETA)

        assertNull(UpdateAvailability.available(context, 99L, UpdateChannel.STABLE))
    }

    @Test fun backgroundChecksDefaultOnAndPersistTheChoice() {
        assertTrue(UpdateAvailability.backgroundChecksEnabled(context))
        UpdateAvailability.saveBackgroundChecksEnabled(context, false)
        assertFalse(UpdateAvailability.backgroundChecksEnabled(context))
    }

    @Test fun reportShowsStateWithRelativeAges() {
        UpdateChannelStore.save(context, UpdateChannel.STABLE)
        assertEquals(
            "Update check: background=on; channel=stable; lastSuccess=never; cachedRelease=none; " +
                "lastResult=none (never); lastInstall=none (never)",
            UpdateAvailability.report(context, 10_000_000L),
        )
        UpdateAvailability.save(context, release("v0.3.0", 100L), UpdateChannel.STABLE)
        UpdateAvailability.recordAttempt(context, 10_000_000L - 5 * 60_000)
        UpdateAvailability.recordResult(context, 10_000_000L - 60_000, "no validated network (attempt 2)")
        UpdateAvailability.recordInstall(context, 10_000_000L - 2 * 60_000, "chooser uri=public installers=2")
        UpdateAvailability.saveBackgroundChecksEnabled(context, false)
        assertEquals(
            "Update check: background=off; channel=stable; lastSuccess=5 min ago; cachedRelease=v0.3.0; " +
                "lastResult=no validated network (attempt 2) (1 min ago); " +
                "lastInstall=chooser uri=public installers=2 (2 min ago)",
            UpdateAvailability.report(context, 10_000_000L),
        )
    }

    @Test fun checksAtMostOncePerDayButRecoversFromAClockReset() {
        val now = 10L * UpdateAvailability.CHECK_INTERVAL_MILLIS
        assertTrue(UpdateAvailability.shouldCheck(context, now))
        UpdateAvailability.recordAttempt(context, now)

        assertFalse(UpdateAvailability.shouldCheck(context, now + UpdateAvailability.CHECK_INTERVAL_MILLIS - 1))
        assertTrue(UpdateAvailability.shouldCheck(context, now + UpdateAvailability.CHECK_INTERVAL_MILLIS))
        assertTrue(UpdateAvailability.shouldCheck(context, now - 1))
    }

    @Test fun channelChangeResetsTheBackgroundCheckCadence() {
        val now = 10L * UpdateAvailability.CHECK_INTERVAL_MILLIS
        UpdateAvailability.recordAttempt(context, now)
        assertFalse(UpdateAvailability.shouldCheck(context, now + 1))

        UpdateAvailability.resetForChannelChange(context)

        assertTrue(UpdateAvailability.shouldCheck(context, now + 1))
    }

    private fun release(tag: String, versionCode: Long) = UpdateRelease(
        tagName = tag,
        versionCode = versionCode,
        apkName = "DiPlay-$tag.apk",
        apkUrl = "https://example.com/DiPlay-$tag.apk",
        checksumsUrl = "https://example.com/SHA256SUMS.txt",
    )
}
