package com.shilapi.xcertplay.pioneer

import android.system.OsConstants
import android.system.StructStat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class PioneerVpnFirewallHeartbeatTest {
    private fun stat(links: Long = 1, uid: Int = 10123, mode: Int = OsConstants.S_IFREG or 0x180,
                     size: Long = 12) = StructStat(0, 1, mode, links, uid, uid, 0, size, 0, 0, 0, 4096, 1)

    @Test fun openHeartbeatRemainsValidWhenAtomicReplacementUnlinksItsInode() {
        PioneerVpnFirewallHelper.requireHeartbeatFile(stat(links = 1), 10123)
        PioneerVpnFirewallHelper.requireHeartbeatFile(stat(links = 0), 10123)
        // Immutable requests must still reject an unlinked inode.
        assertThrows(IOException::class.java) {
            PioneerVpnPermissionHelper.requireFile(stat(links = 0), false, 10123)
        }
    }

    @Test fun heartbeatStillRejectsHardLinksWrongOwnerTypeModeAndUnboundedContents() {
        for (invalid in listOf(stat(links = 2), stat(uid = 10124),
            stat(mode = OsConstants.S_IFDIR or 0x180), stat(mode = OsConstants.S_IFREG or 0x1a4),
            stat(size = 0), stat(size = 129))) {
            assertThrows(IOException::class.java) {
                PioneerVpnFirewallHelper.requireHeartbeatFile(invalid, 10123)
            }
        }
    }
}
