package com.screensaathi.sarvam

/**
 * Finalises a streamed WAV after the complete response has been downloaded.
 *
 * OpenAI uses `0xffffffff` for the RIFF and data lengths while streaming. That
 * is useful on the wire, but Samsung's Android 12 MediaPlayer rejects the same
 * bytes after they have been saved as a local file. At that point the lengths
 * are known, so replace only those streaming sentinels with their real values.
 */
internal object WavStreamHeader {
    private const val UNKNOWN_SIZE = 0xffffffffL

    fun repair(bytes: ByteArray): ByteArray {
        if (bytes.size < 12 || !bytes.hasAscii(0, "RIFF") || !bytes.hasAscii(8, "WAVE")) {
            return bytes
        }

        var repaired: ByteArray? = null
        fun writable(): ByteArray = repaired ?: bytes.copyOf().also { repaired = it }

        if (bytes.readLeUInt(4) == UNKNOWN_SIZE) {
            writable().putLeInt(4, bytes.size - 8)
        }

        var chunkOffset = 12
        while (chunkOffset + 8 <= bytes.size) {
            val payloadOffset = chunkOffset + 8
            val declaredSize = bytes.readLeUInt(chunkOffset + 4)

            if (bytes.hasAscii(chunkOffset, "data")) {
                if (declaredSize == UNKNOWN_SIZE) {
                    writable().putLeInt(chunkOffset + 4, bytes.size - payloadOffset)
                }
                break
            }

            if (declaredSize == UNKNOWN_SIZE) break
            val paddedSize = declaredSize + (declaredSize and 1L)
            val next = payloadOffset.toLong() + paddedSize
            if (next <= chunkOffset || next > bytes.size) break
            chunkOffset = next.toInt()
        }

        return repaired ?: bytes
    }

    private fun ByteArray.hasAscii(offset: Int, value: String): Boolean {
        if (offset < 0 || offset + value.length > size) return false
        return value.indices.all { this[offset + it].toInt() and 0xff == value[it].code }
    }

    private fun ByteArray.readLeUInt(offset: Int): Long =
        (this[offset].toLong() and 0xff) or
            ((this[offset + 1].toLong() and 0xff) shl 8) or
            ((this[offset + 2].toLong() and 0xff) shl 16) or
            ((this[offset + 3].toLong() and 0xff) shl 24)

    private fun ByteArray.putLeInt(offset: Int, value: Int) {
        for (i in 0..3) this[offset + i] = (value ushr (8 * i)).toByte()
    }
}
