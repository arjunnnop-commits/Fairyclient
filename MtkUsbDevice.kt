package com.fairyteam.fairyclient.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * Thin wrapper around the Android USB host APIs that finds the bulk IN/OUT
 * endpoints on a MediaTek BROM / Preloader device and exposes simple
 * read/write primitives. No root required — this uses UsbManager directly,
 * same as any other USB-host accessory app.
 */
class MtkUsbDevice(
    private val manager: UsbManager,
    private val device: UsbDevice
) {
    companion object {
        const val MEDIATEK_VID = 0x0E8D
        // Known PIDs seen in BROM / Preloader mode across chipsets.
        // Not exhaustive — BROM is detected primarily by VID + class, the
        // PID list is only used to prioritize which interface to claim.
        val KNOWN_BROM_PIDS = setOf(0x0003, 0x2000, 0x2001, 0x0004)
        private const val TIMEOUT_MS = 5000
    }

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null

    val isSupported: Boolean
        get() = device.vendorId == MEDIATEK_VID

    fun hasPermission(): Boolean = manager.hasPermission(device)

    fun open(): Boolean {
        val conn = manager.openDevice(device) ?: return false
        connection = conn

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // MediaTek BROM/Preloader exposes a vendor-specific bulk interface.
            if (iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC ||
                iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA
            ) {
                var inEp: UsbEndpoint? = null
                var outEp: UsbEndpoint? = null
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT) outEp = ep
                }
                if (inEp != null && outEp != null) {
                    if (conn.claimInterface(iface, true)) {
                        usbInterface = iface
                        epIn = inEp
                        epOut = outEp
                        return true
                    }
                }
            }
        }
        return false
    }

    fun close() {
        usbInterface?.let { connection?.releaseInterface(it) }
        connection?.close()
        connection = null
    }

    /** Raw bulk write. Returns bytes written, or -1 on failure. */
    fun write(data: ByteArray, timeoutMs: Int = TIMEOUT_MS): Int {
        val conn = connection ?: return -1
        val ep = epOut ?: return -1
        return conn.bulkTransfer(ep, data, data.size, timeoutMs)
    }

    /** Raw bulk read into a buffer of the given length. Returns bytes read, or -1. */
    fun read(length: Int, timeoutMs: Int = TIMEOUT_MS): ByteArray? {
        val conn = connection ?: return null
        val ep = epIn ?: return null
        val buf = ByteArray(length)
        val n = conn.bulkTransfer(ep, buf, length, timeoutMs)
        if (n < 0) return null
        return buf.copyOf(n)
    }
}
