package com.screensaathi.sarvam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WavStreamHeaderTest {

    @Test
    fun `repairs unknown RIFF and data sizes from a completed OpenAI stream`() {
        val streamed = wavBytes(riffSize = -1, dataSize = -1)

        val repaired = WavStreamHeader.repair(streamed)

        assertEquals(repaired.size - 8, repaired.readLeInt(4))
        assertEquals(4, repaired.readLeInt(40))
    }

    @Test
    fun `leaves an already sized WAV unchanged`() {
        val complete = wavBytes(riffSize = 40, dataSize = 4)

        assertArrayEquals(complete, WavStreamHeader.repair(complete))
    }

    @Test
    fun `leaves non WAV bytes unchanged`() {
        val json = "{\"error\":\"not audio\"}".toByteArray()

        assertArrayEquals(json, WavStreamHeader.repair(json))
    }

    private fun wavBytes(riffSize: Int, dataSize: Int): ByteArray = ByteArray(48).also { bytes ->
        bytes.putAscii(0, "RIFF")
        bytes.putLeInt(4, riffSize)
        bytes.putAscii(8, "WAVE")
        bytes.putAscii(12, "fmt ")
        bytes.putLeInt(16, 16)
        bytes.putAscii(36, "data")
        bytes.putLeInt(40, dataSize)
        bytes[44] = 1
        bytes[45] = 2
        bytes[46] = 3
        bytes[47] = 4
    }

    private fun ByteArray.putAscii(offset: Int, text: String) {
        text.toByteArray(Charsets.US_ASCII).copyInto(this, offset)
    }

    private fun ByteArray.putLeInt(offset: Int, value: Int) {
        for (i in 0..3) this[offset + i] = (value ushr (8 * i)).toByte()
    }

    private fun ByteArray.readLeInt(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)
}
