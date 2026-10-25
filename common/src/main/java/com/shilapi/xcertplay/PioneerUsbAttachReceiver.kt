package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.IntentCompat
import com.shilapi.xcertplay.pioneer.PioneerDebugRuntime
import com.shilapi.xcertplay.pioneer.PioneerUsbLaunchPolicy

/** Android 7 verified Pioneer only. The regular connection flow still owns authorization. */
class PioneerUsbAttachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED || !PioneerUsbLaunchPolicy.enabled(context)) return
        val attached = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
        if (attached.vendorId != 0x05ac) return
        val live = context.getSystemService(UsbManager::class.java)?.deviceList?.get(attached.deviceName) ?: return
        if (live.deviceId != attached.deviceId || live.vendorId != attached.vendorId || live.productId != attached.productId) return
        runCatching {
            context.startActivity(Intent(context, CarPlayHostActivity::class.java)
                .setAction(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, live)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            PioneerDebugRuntime.log(context, "USB_LAUNCH_POLICY attached broadcast opened host")
        }.onFailure { PioneerDebugRuntime.log(context, "USB_LAUNCH_POLICY launch failed ${it.javaClass.simpleName}") }
    }
}
