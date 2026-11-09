package com.fairyteam.fairyclient.brom

import com.fairyteam.fairyclient.usb.MtkUsbDevice
import java.io.ByteArrayOutputStream

/**
 * MediaTek BROM (Boot ROM) low-level protocol.
 *
 * This reimplements the publicly documented handshake used by tools such as
 * SP Flash Tool / mtkclient: a 4-byte echo handshake followed by simple
 * register read/write and "send/jump DA" commands. BROM only stays
 * responsive for a couple of seconds after power-on in this mode, so the
 * handshake must be started immediately after the USB device is granted
 * permission and opened.
 *
 * NOTE on secure boot (SLA/DAA): on chips where the OEM has enabled
 * Secure Boot / Download Agent Authentication, BROM will refuse a DA that
 * isn't signed for that device, and this class will surface that as a
 * [BromException]. This app does not attempt to bypass SLA/DAA — doing so
 * is chip- and exploit-specific and must come from a DA/auth-bypass file
 * the user supplies (the same model SP Flash Tool / mtkclient use), not
 * from generic code here.
 */
class BromProtocol(private val usb: MtkUsbDevice) {

    class BromException(message: String) : Exception(message)

    companion object {
        // Handshake bytes BROM expects, and the echoed reply (each byte is
        // echoed back bitwise-inverted by the chip to prove liveness).
        private val HANDSHAKE_SEQ = byteArrayOf(0xA0.toByte(), 0x0A, 0x50, 0x05)

        private const val CMD_READ16 = 0xD0.toByte()
        private const val CMD_WRITE16 = 0xD1.toByte()
        private const val CMD_READ32 = 0xD4.toByte()
        private const val CMD_WRITE32 = 0xD5.toByte()
        private const val CMD_SEND_DA = 0xD7.toByte()
        private const val CMD_JUMP_DA = 0xD5.toByte()
        private const val CMD_GET_HW_CODE = 0xFD.toByte()
    }

    /**
     * Performs the initial handshake. Must be called within ~2-3 seconds of
     * the device enumerating in BROM mode, or the bootrom will have already
     * timed out / the SoC will have reset.
     *
     * Retries the whole 4-byte sequence up to [maxAttempts] times, since a
     * single dropped/garbled byte on the first attempt is the most common
     * cause of "protocol failed" and a fresh retry from byte 0 usually
     * recovers it as long as BROM hasn't already reset.
     */
    fun handshake(maxAttempts: Int = 5): Boolean {
        repeat(maxAttempts) { attempt ->
            // Drain any stale bytes left over from a previous failed attempt
            // or from device enumeration chatter, so we start clean.
            drainStale()

            if (tryHandshakeOnce()) return true
        }
        return false
    }

    private fun tryHandshakeOnce(): Boolean {
        for (b in HANDSHAKE_SEQ) {
            val written = usb.write(byteArrayOf(b), timeoutMs = 1000)
            if (written <= 0) return false
            val reply = usb.read(1, timeoutMs = 1000) ?: return false
            if (reply.isEmpty()) return false
            // BROM echoes the inverse of each byte we send.
            val expected = (b.toInt().inv() and 0xFF).toByte()
            if (reply[0] != expected) return false
        }
        return true
    }

    /** Reads and discards whatever is sitting in the IN buffer, if anything. */
    private fun drainStale() {
        // Short timeout: this is a best-effort flush, not a real read — if
        // nothing is pending it should return almost immediately.
        usb.read(64, timeoutMs = 50)
    }

    /** Reads the SoC hardware code (chip id), used to pick the right DA file. */
    fun getHwCode(): Int {
        usb.write(byteArrayOf(CMD_GET_HW_CODE))
        val resp = usb.read(2) ?: throw BromException("No response to GET_HW_CODE")
        if (resp.size < 2) throw BromException("Short response to GET_HW_CODE")
        return ((resp[0].toInt() and 0xFF) shl 8) or (resp[1].toInt() and 0xFF)
    }

    /**
     * Sends a Download Agent (DA) binary to the given SRAM address and asks
     * BROM to jump to it. The DA then takes over and exposes the richer
     * command set used for partition read/write.
     *
     * Throws [BromException] if the chip rejects the DA (commonly: SLA/DAA
     * auth failure on secured devices — see class doc).
     */
    fun sendDa(address: Long, data: ByteArray, signature: ByteArray?) {
        usb.write(byteArrayOf(CMD_SEND_DA))
        writeU32(address)
        writeU32(data.size.toLong())
        writeU32((signature?.size ?: 0).toLong())

        val ack = usb.read(2) ?: throw BromException("No ACK before DA payload")
        if (!isAck(ack)) throw BromException("BROM rejected DA header (auth/SLA?)")

        // Payload is sent in chunks; BROM does not need huge single writes.
        val chunkSize = 1024
        var offset = 0
        while (offset < data.size) {
            val len = minOf(chunkSize, data.size - offset)
            usb.write(data.copyOfRange(offset, offset + len))
            offset += len
        }

        if (signature != null && signature.isNotEmpty()) {
            usb.write(signature)
        }

        val finalAck = usb.read(2) ?: throw BromException("No final ACK after DA transfer")
        if (!isAck(finalAck)) {
            throw BromException(
                "DA transfer rejected by BROM. On devices with Secure Boot / " +
                    "SLA-DAA enabled, this means the DA is not authorized for " +
                    "this device and a signed/auth-bypass DA specific to this " +
                    "chip is required."
            )
        }
    }

    fun jumpDa(address: Long) {
        usb.write(byteArrayOf(CMD_JUMP_DA))
        writeU32(address)
        val ack = usb.read(2) ?: throw BromException("No ACK for JUMP_DA")
        if (!isAck(ack)) throw BromException("BROM refused to jump to DA")
    }

    private fun isAck(resp: ByteArray): Boolean {
        // Protocol convention used here: first byte 0 = success.
        return resp.isNotEmpty() && resp[0].toInt() == 0
    }

    private fun writeU32(value: Long) {
        val buf = ByteArrayOutputStream(4)
        buf.write(((value shr 24) and 0xFF).toInt())
        buf.write(((value shr 16) and 0xFF).toInt())
        buf.write(((value shr 8) and 0xFF).toInt())
        buf.write((value and 0xFF).toInt())
        usb.write(buf.toByteArray())
    }
}
