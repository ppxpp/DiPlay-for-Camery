package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.pioneer.PioneerUsbLaunchPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25])
class PioneerUsbAttachReceiverTest {
    private val app = RuntimeEnvironment.getApplication()
    private val alias = ComponentName(app.packageName, "com.shilapi.xcertplay.IphoneUsbAttachActivity")
    private val receiver = ComponentName(app, PioneerUsbAttachReceiver::class.java)

    private fun pioneer() {
        ShadowBuild.setModel("SABRESD_MX6DQ")
        ShadowBuild.setDevice("tamago")
    }

    @Test fun genericDevicesKeepNormalSystemSelection() {
        ShadowBuild.setModel("other")
        PioneerUsbLaunchPolicy.rootReady(app)
        assertFalse(PioneerUsbLaunchPolicy.enabled(app))
        assertNotEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, app.packageManager.getComponentEnabledSetting(alias))
        assertFalse(app.packageManager.getReceiverInfo(receiver, PackageManager.MATCH_DISABLED_COMPONENTS).enabled)
    }

    @Test fun rootReadyEnablesBroadcastAndRemovesOnlyAppleSelectionEntry() {
        pioneer()
        assertFalse(PioneerUsbLaunchPolicy.enabled(app))
        PioneerUsbLaunchPolicy.rootReady(app)
        assertTrue(PioneerUsbLaunchPolicy.enabled(app))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, app.packageManager.getComponentEnabledSetting(alias))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, app.packageManager.getComponentEnabledSetting(receiver))
        assertTrue(app.packageManager.getActivityInfo(ComponentName(app, CarPlayHostActivity::class.java), 0).enabled)
        PioneerUsbLaunchPolicy.rootReady(app)
        assertTrue(PioneerUsbLaunchPolicy.enabled(app))
    }

    @Test fun receiverStartsExistingHostWithoutUsbPermissionButRejectsStaleOrOtherDevices() {
        pioneer()
        PioneerUsbLaunchPolicy.rootReady(app)
        val usb = app.getSystemService(UsbManager::class.java)
        val phone = device(0x05ac, "/dev/bus/usb/001/002")
        val broadcast = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, phone)
        val entry = PioneerUsbAttachReceiver()
        entry.onReceive(app, broadcast)
        assertNull(shadowOf(app).nextStartedActivity)
        shadowOf(usb).addOrUpdateUsbDevice(phone, false)
        entry.onReceive(app, broadcast)
        val launched = shadowOf(app).nextStartedActivity
        assertEquals(ComponentName(app, CarPlayHostActivity::class.java), launched.component)
        assertEquals(UsbManager.ACTION_USB_DEVICE_ATTACHED, launched.action)
        assertFalse(usb.hasPermission(phone))
        val other = device(0x1a86, "/dev/bus/usb/001/003")
        shadowOf(usb).addOrUpdateUsbDevice(other, true)
        entry.onReceive(app, Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, other))
        assertNull(shadowOf(app).nextStartedActivity)
        shadowOf(usb).removeUsbDevice(phone)
        entry.onReceive(app, broadcast)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    private fun device(vendor: Int, node: String): UsbDevice = ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
        ReflectionHelpers.setField(this, "mName", node)
        ReflectionHelpers.setField(this, "mVendorId", vendor)
        ReflectionHelpers.setField(this, "mProductId", 0x12a8)
    }
}
