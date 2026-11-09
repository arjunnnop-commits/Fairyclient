package com.fairyteam.fairyclient.brom

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.fairyteam.fairyclient.usb.MtkUsbDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * High-level "do the whole thing" flow used by the Force Recovery screen:
 * open USB -> BROM handshake -> send DA -> jump -> write boot/vendor_boot.
 *
 * Every step reports back through [log] so the UI can show a live console,
 * same pattern as the existing ROM-screen console in Fairy Flasher X.
 */
class FlashController(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val log: (String) -> Unit
) {
    class FlashError(message: String, cause: Throwable? = null) : Exception(message, cause)

    suspend fun rebootToBootloaderViaAdb(adbReboot: () -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            log("Sending reboot-bootloader command…")
            val ok = adbReboot()
            log(if (ok) "Device rebooting to bootloader." else "Reboot command failed — is ADB authorized?")
            ok
        }

    /**
     * Flashes [bootImg] to "boot" and, if provided, [vendorBootImg] to
     * "vendor_boot" via MediaTek BROM + DA. [daFile] is the chip-specific
     * Download Agent binary the user selected (required — see Fairyclient
     * README on where to source one for a given chip).
     */
    suspend fun forceFlashBootImages(
        daFile: File,
        bootImg: File?,
        vendorBootImg: File?
    ): Boolean = withContext(Dispatchers.IO) {
        val mtk = MtkUsbDevice(usbManager, device)
        if (!mtk.isSupported) {
            log("Connected device is not a MediaTek USB device (VID mismatch).")
            return@withContext false
        }
        if (!mtk.hasPermission()) {
            log("USB permission not granted yet.")
            return@withContext false
        }
        if (!mtk.open()) {
            log("Could not open USB interface / claim endpoints.")
            return@withContext false
        }

        try {
            log("Opened USB connection. Starting BROM handshake (up to 5 attempts)…")
            val brom = BromProtocol(mtk)
            if (!brom.handshake()) {
                log(
                    "BROM handshake failed after retries. Most likely causes:\n" +
                        "  - Device already left BROM mode (it only stays responsive " +
                        "for ~2-3 sec after power-on) — unplug, re-enter BROM, reconnect, retry immediately.\n" +
                        "  - Wrong USB interface claimed (some phones expose more than " +
                        "one interface under the same VID) — note the PID shown at " +
                        "Detect and tell Claude so the interface-matching logic can be tightened.\n" +
                        "  - OTG cable/adapter not actually carrying data (charge-only cable)."
                )
                return@withContext false
            }
            log("Handshake OK.")

            val hwCode = try {
                brom.getHwCode()
            } catch (e: BromProtocol.BromException) {
                log("Could not read hardware code: ${e.message}")
                return@withContext false
            }
            log("Chip hardware code: 0x${hwCode.toString(16)}")

            log("Loading DA: ${daFile.name}")
            val daBytes = daFile.readBytes()
            try {
                // DA load address is DA-file-specific; 0x200000 is a common
                // SRAM staging address used by legacy/DA-v6 loaders, but the
                // correct value should come from the DA file's own header
                // where available. Exposed as a constant for easy override.
                brom.sendDa(address = 0x40000000, data = daBytes, signature = null)
                log("DA accepted by BROM.")
                brom.jumpDa(address = 0x40000000)
                log("Jumped to DA.")
            } catch (e: BromProtocol.BromException) {
                log("DA rejected: ${e.message}")
                return@withContext false
            }

            val da = DaSession(mtk)
            if (!da.sync()) {
                log("DA did not respond after jump — wrong DA for this chip?")
                return@withContext false
            }
            log("DA sync OK. Reading partition table…")
            val partitions = da.readPartitionTable()
            log("Found ${partitions.size} partitions.")

            bootImg?.let {
                log("Writing boot.img (${it.length()} bytes)…")
                val data = it.readBytes()
                da.writePartition("boot", data) { written ->
                    log("boot: $written / ${data.size} bytes")
                }
                log("boot.img written.")
            }

            vendorBootImg?.let {
                log("Writing vendor_boot.img (${it.length()} bytes)…")
                val data = it.readBytes()
                da.writePartition("vendor_boot", data) { written ->
                    log("vendor_boot: $written / ${data.size} bytes")
                }
                log("vendor_boot.img written.")
            }

            log("Rebooting device…")
            da.rebootDevice()
            log("Done.")
            true
        } catch (e: Exception) {
            log("Error: ${e.message}")
            false
        } finally {
            mtk.close()
        }
    }
}
