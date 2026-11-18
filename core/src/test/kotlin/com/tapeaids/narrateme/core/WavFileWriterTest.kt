package com.tapeaids.narrateme.core

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileWriterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `writes a valid 16 kHz mono PCM16 header with patched sizes`() {
        val file = tmp.newFile("a.wav")
        WavFileWriter(file, sampleRate = 16_000).use { w ->
            w.write(ByteArray(100) { it.toByte() }, 100)
            w.write(ByteArray(50), 20)
        }
        val bytes = file.readBytes()
        assertEquals(44 + 120, bytes.size)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(36 + 120, b.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals("fmt ", String(bytes, 12, 4))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28)) // byte rate
        assertEquals(2, b.getShort(32).toInt()) // block align
        assertEquals(16, b.getShort(34).toInt()) // bits per sample
        assertEquals("data", String(bytes, 36, 4))
        assertEquals(120, b.getInt(40))
        assertEquals(5.toByte(), bytes[44 + 5])
    }

    @Test
    fun `empty recording still has a valid header`() {
        val file = tmp.newFile("b.wav")
        WavFileWriter(file, sampleRate = 8_000).close()
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, file.length().toInt())
        assertEquals(0, b.getInt(40))
        assertEquals(16_000, b.getInt(28))
    }
}
