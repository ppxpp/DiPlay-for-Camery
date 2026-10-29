package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.InterruptedIOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[25],manifest=Config.NONE)
class PioneerBluetoothWaitTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Test fun alreadyConnectedContinuesWithoutWaiting() {
        var queries=0
        PioneerBluetoothWait.await(app,{ queries++;true },{false},{fail("must not wait")},{})
        assertEquals(1,queries)
    }
    @Test fun notificationTriggersASelectedDeviceStateRecheck() {
        var connected=false
        var queries=0
        PioneerBluetoothWait.await(app,{ queries++;connected },{false},{
            connected=true
            app.sendBroadcast(Intent("jp.pioneer.ceam.bluetooth.adapter.action.CONNECTION_STATE_CHANGED"))
            shadowOf(Looper.getMainLooper()).idle()
        },{})
        assertEquals(2,queries)
    }
    @Test fun notificationAloneCannotConfirmConnection() {
        var queries=0
        try {
            PioneerBluetoothWait.await(app,{ queries++;false },{queries>=2},{
                app.sendBroadcast(Intent("jp.pioneer.ceam.bluetooth.adapter.action.AUTO_CONNECT_SUCCESS"))
                shadowOf(Looper.getMainLooper()).idle()
            },{})
            fail("must cancel rather than accepting a broadcast")
        } catch(expected: InterruptedIOException) { assertEquals(2,queries) }
    }
    @Test fun timeoutFailsTheBluetoothNode() {
        try {
            PioneerBluetoothWait.await(app,{false},{false},{},{},timeoutMillis=0)
            fail("must time out")
        } catch(expected: WirelessStartupException) {
            assertEquals(WirelessStartupFailure.BLUETOOTH_NOT_CONNECTED,expected.reason)
        }
    }
}
