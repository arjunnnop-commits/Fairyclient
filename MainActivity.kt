package com.fairyteam.fairyclient.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fairyteam.fairyclient.R
import com.fairyteam.fairyclient.brom.FlashController
import com.fairyteam.fairyclient.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager

    private var daFile: File? = null
    private var bootImgFile: File? = null
    private var vendorBootImgFile: File? = null
    private var pendingDevice: UsbDevice? = null

    private val usbPermissionAction = "com.fairyteam.fairyclient.USB_PERMISSION"

    // Temporary file-picking approach: ActivityResultContracts.OpenDocument
    // lets the user pick any file via the system picker (no storage
    // permission needed), and we copy it into the app's own cache dir so
    // File-based APIs elsewhere (BromProtocol/DaSession) can read it with a
    // plain java.io.File. This is deliberately simple/temporary — swap for
    // a proper SAF-backed flow (keeping the content:// Uri, using
    // ContentResolver streams directly) if DA/image files get large enough
    // that the copy step becomes slow or you want persisted access.
    private val pickDaLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { daFile = copyUriToCache(it, "da.bin"); appendLog("DA file selected: ${daFile?.name} (${daFile?.length()} bytes)") }
    }
    private val pickBootLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { bootImgFile = copyUriToCache(it, "boot.img"); appendLog("boot.img selected: ${bootImgFile?.length()} bytes") }
    }
    private val pickVendorBootLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vendorBootImgFile = copyUriToCache(it, "vendor_boot.img"); appendLog("vendor_boot.img selected: ${vendorBootImgFile?.length()} bytes") }
    }

    /** Copies a picked content:// Uri into cacheDir so File-based code can read it. */
    private fun copyUriToCache(uri: Uri, targetName: String): File? {
        return try {
            val out = File(cacheDir, targetName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            out
        } catch (e: Exception) {
            appendLog("Failed to read picked file: ${e.message}")
            null
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != usbPermissionAction) return
            synchronized(this) {
                val device: UsbDevice? =
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    appendLog("USB permission granted for ${device.deviceName}.")
                    pendingDevice = device
                    binding.btnStartFlash.isEnabled = true
                } else {
                    appendLog("USB permission denied.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter(usbPermissionAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }

        binding.btnDetect.setOnClickListener { detectDevice() }
        binding.btnPickDa.setOnClickListener { pickDaLauncher.launch(arrayOf("*/*")) }
        binding.btnPickBoot.setOnClickListener { pickBootLauncher.launch(arrayOf("*/*")) }
        binding.btnPickVendorBoot.setOnClickListener { pickVendorBootLauncher.launch(arrayOf("*/*")) }

        binding.btnStartFlash.isEnabled = false
        binding.btnStartFlash.setOnClickListener { startFlash() }

        appendLog("Fairyclient ready. Put the device into BROM mode and tap Detect.")
    }

    private fun detectDevice() {
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 0x0E8D }
        if (device == null) {
            appendLog("No MediaTek device found. Make sure it's connected via OTG in BROM mode.")
            return
        }
        appendLog("Found device: ${device.deviceName} (VID=${device.vendorId}, PID=${device.productId})")

        if (usbManager.hasPermission(device)) {
            pendingDevice = device
            binding.btnStartFlash.isEnabled = true
            appendLog("Already have USB permission.")
            return
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(usbPermissionAction), flags)
        usbManager.requestPermission(device, pi)
        appendLog("Requesting USB permission…")
    }

    private fun startFlash() {
        val device = pendingDevice
        val da = daFile
        if (device == null) {
            appendLog("No device permission yet — tap Detect first.")
            return
        }
        if (da == null) {
            appendLog("Pick a DA file for this chip first.")
            return
        }
        binding.btnStartFlash.isEnabled = false

        val controller = FlashController(usbManager, device) { line -> appendLog(line) }
        lifecycleScope.launch {
            val ok = controller.forceFlashBootImages(da, bootImgFile, vendorBootImgFile)
            appendLog(if (ok) "Flash sequence finished successfully." else "Flash sequence failed — see log above.")
            binding.btnStartFlash.isEnabled = true

            // Your original DA is only needed for this one recovery session —
            // wipe it from cache now instead of leaving it on disk.
            if (da.exists() && da.delete()) {
                appendLog("Temporary DA file cleared from app cache.")
            }
            daFile = null
        }
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            binding.consoleText.append(line + "\n")
            binding.consoleScroll.post {
                binding.consoleScroll.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        // Safety net: if the app is closed before a flash runs (or the user
        // picked a DA and backed out), still wipe it — the DA should never
        // outlive the app process.
        daFile?.let { if (it.exists()) it.delete() }
    }
}
