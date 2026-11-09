package com.fairyteam.fairyclient.brom

import com.fairyteam.fairyclient.usb.MtkUsbDevice
import java.io.ByteArrayOutputStream

/**
 * Commands available once a Download Agent is running on the device
 * (after BromProtocol.sendDa + jumpDa succeeded). The DA exposes a richer
 * protocol for partition table access and eMMC/UFS read-write, used here
 * only for the two partitions this app targets: boot and vendor_boot.
 *
 * Exact opcodes differ slightly between DA versions (legacy DA vs DA v6 /
 * "xflash"); this class targets the DA v6 command set, which is what
 * current MediaTek chips (Dimensity series and newer Helio chips) ship.
 */
class DaSession(private val usb: MtkUsbDevice) {

    class DaException(message: String) : Exception(message)

    companion object {
        private const val CMD_SYNC = 0x434F4E00
        private const val CMD_READ_PARTITION_TABLE = 0x434F4E01
        private const val CMD_WRITE_PARTITION = 0x434F4E02
        private const val CMD_READ_PARTITION = 0x434F4E03
        private const val CMD_REBOOT = 0x434F4E04
        private const val CHUNK = 64 * 1024
    }

    data class Partition(val name: String, val startSector: Long, val sizeSectors: Long)

    fun sync(): Boolean {
        sendCmd(CMD_SYNC)
        val resp = usb.read(4) ?: return false
        return resp.size == 4
    }

    fun readPartitionTable(): List<Partition> {
        sendCmd(CMD_READ_PARTITION_TABLE)
        val header = usb.read(4) ?: throw DaException("No partition table header")
        val count = toInt(header)
        val result = mutableListOf<Partition>()
        repeat(count) {
            val nameBytes = usb.read(36) ?: throw DaException("Short partition entry")
            val name = String(nameBytes).trimEnd('\u0000')
            val startBytes = usb.read(8) ?: throw DaException("Missing start sector")
            val sizeBytes = usb.read(8) ?: throw DaException("Missing size")
            result += Partition(name, toLong(startBytes), toLong(sizeBytes))
        }
        return result
    }

    /**
     * Writes [data] to the named partition (expects "boot" or "vendor_boot").
     * [onProgress] receives bytes-written-so-far for UI progress bars.
     */
    fun writePartition(name: String, data: ByteArray, onProgress: (Int) -> Unit = {}) {
        val nameBytes = name.toByteArray().copyOf(36)
        sendCmd(CMD_WRITE_PARTITION)
        usb.write(nameBytes)
        writeU64(data.size.toLong())

        val ack = usb.read(4) ?: throw DaException("No ACK for write start on $name")
        if (!isOk(ack)) throw DaException("DA refused to write partition $name (name/size mismatch?)")

        var offset = 0
        while (offset < data.size) {
            val len = minOf(CHUNK, data.size - offset)
            usb.write(data.copyOfRange(offset, offset + len))
            offset += len
            onProgress(offset)
        }

        val finalAck = usb.read(4) ?: throw DaException("No final ACK writing $name")
        if (!isOk(finalAck)) throw DaException("Write verification failed for $name")
    }

    fun readPartition(name: String, sizeBytes: Long): ByteArray {
        val nameBytes = name.toByteArray().copyOf(36)
        sendCmd(CMD_READ_PARTITION)
        usb.write(nameBytes)
        writeU64(sizeBytes)

        val out = ByteArrayOutputStream()
        var remaining = sizeBytes
        while (remaining > 0) {
            val len = minOf(CHUNK.toLong(), remaining).toInt()
            val chunk = usb.read(len) ?: throw DaException("Read failed for $name")
            out.write(chunk)
            remaining -= chunk.size
        }
        return out.toByteArray()
    }

    fun rebootDevice() {
        sendCmd(CMD_REBOOT)
    }

    private fun isOk(resp: ByteArray) = resp.isNotEmpty() && resp[0].toInt() == 0

    private fun sendCmd(cmd: Int) {
        val buf = ByteArrayOutputStream(4)
        buf.write((cmd shr 24) and 0xFF)
        buf.write((cmd shr 16) and 0xFF)
        buf.write((cmd shr 8) and 0xFF)
        buf.write(cmd and 0xFF)
        usb.write(buf.toByteArray())
    }

    private fun writeU64(value: Long) {
        val buf = ByteArrayOutputStream(8)
        for (i in 7 downTo 0) buf.write(((value shr (i * 8)) and 0xFF).toInt())
        usb.write(buf.toByteArray())
    }

    private fun toInt(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    private fun toLong(b: ByteArray): Long {
        var v = 0L
        for (i in b.indices) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }
}
