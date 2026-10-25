package com.shilapi.xcertplay.transport

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.util.Log
import java.lang.reflect.InvocationTargetException

/** CarLife-style best-effort grant, subject to the firmware's MANAGE_USB enforcement. */
internal object IphoneUsbSilentGrant {
    private const val TAG = "IphoneUsbSilentGrant"

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun grant(context: Context, usbManager: UsbManager, device: UsbDevice, diagnostic: (String) -> Unit = {}): Boolean {
        // Only grant the current app access to the Apple device selected by IphoneUsbHost.
        if (device.vendorId != IphoneUsbMatcher.APPLE_VENDOR_ID) return false
        try {
            diagnostic("隐藏接口：获取 USB Binder，准备 grantDevicePermission，uid=${context.applicationInfo.uid}")
            val binder = Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .invoke(null, Context.USB_SERVICE) as? IBinder ?: return false
            val service = Class.forName("android.hardware.usb.IUsbManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, binder) ?: return false
            Class.forName("android.hardware.usb.IUsbManager")
                .getDeclaredMethod("grantDevicePermission", UsbDevice::class.java, Integer.TYPE)
                .invoke(service, device, context.applicationInfo.uid)
            // A successful Binder invocation alone does not establish usable device access.
            val granted = usbManager.hasPermission(device)
            diagnostic("隐藏接口调用完成；hasPermission=$granted")
            Log.i(TAG, "Direct system USB grant verified=$granted")
            return granted
        } catch (error: Exception) {
            val cause = if (error is InvocationTargetException) error.targetException else error
            diagnostic("隐藏接口失败：${cause.javaClass.name}: ${cause.message}；回退原权限流程")
            Log.d(TAG, "Direct system USB grant unavailable: ${cause.javaClass.simpleName}; using existing permission flow")
            return false
        }
    }
}
