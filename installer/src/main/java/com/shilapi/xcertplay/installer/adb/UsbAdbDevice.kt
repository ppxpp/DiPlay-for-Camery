package com.shilapi.xcertplay.installer.adb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

data class UsbAdbDevice(
    val usbDevice: UsbDevice,
    val usbInterface: UsbInterface,
    val inEndpoint: UsbEndpoint,
    val outEndpoint: UsbEndpoint,
) {
    val label: String
        get() = usbDevice.productName?.takeIf { it.isNotBlank() } ?: "USB 设备"
}

fun findAdbDevices(context: Context): List<UsbAdbDevice> {
    val manager = context.getSystemService(UsbManager::class.java)
    val result = mutableListOf<UsbAdbDevice>()

    manager.deviceList.values.forEach { device ->
        for (interfaceIndex in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(interfaceIndex)
            if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_VENDOR_SPEC ||
                usbInterface.interfaceSubclass != 0x42 ||
                usbInterface.interfaceProtocol != 0x01
            ) {
                continue
            }

            var inEndpoint: UsbEndpoint? = null
            var outEndpoint: UsbEndpoint? = null
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                    inEndpoint = endpoint
                } else {
                    outEndpoint = endpoint
                }
            }

            if (inEndpoint != null && outEndpoint != null) {
                result += UsbAdbDevice(
                    usbDevice = device,
                    usbInterface = usbInterface,
                    inEndpoint = inEndpoint,
                    outEndpoint = outEndpoint,
                )
            }
        }
    }

    return result
}

fun describeUsbDevices(context: Context): String {
    val manager = context.getSystemService(UsbManager::class.java)
    if (manager.deviceList.isEmpty()) return "USB 总线上没有设备"

    return buildString {
        manager.deviceList.values.forEach { device ->
            appendLine("${device.deviceName}  ${device.productName ?: "unknown"}")
            appendLine("  VID:PID %04x:%04x".format(device.vendorId, device.productId))
            for (interfaceIndex in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(interfaceIndex)
                appendLine(
                    "  iface $interfaceIndex class=${usbInterface.interfaceClass} " +
                        "sub=${usbInterface.interfaceSubclass} proto=${usbInterface.interfaceProtocol}",
                )
                for (endpointIndex in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(endpointIndex)
                    appendLine(
                        "    ep $endpointIndex type=${endpoint.type} dir=${endpoint.direction} " +
                            "max=${endpoint.maxPacketSize}",
                    )
                }
            }
        }
    }
}
