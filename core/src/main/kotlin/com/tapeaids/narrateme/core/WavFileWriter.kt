package com.tapeaids.narrateme.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Streams 16-bit PCM to a WAV file, fixing up the header sizes on [close]. */
class WavFileWriter(
    file: File,
    private val sampleRate: Int,
    private val channels: Int = 1,
) : Closeable {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0) }
    private var dataBytes = 0L

    init {
        out.write(header(0))
    }

    fun write(pcm16: ByteArray, length: Int) {
        out.write(pcm16, 0, length)
        dataBytes += length
    }

    override fun close() {
        out.seek(0)
        out.write(header(dataBytes))
        out.close()
    }

    private fun header(dataSize: Long): ByteArray {
        val blockAlign = channels * BYTES_PER_SAMPLE
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataSize).toInt()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(sampleRate); putInt(sampleRate * blockAlign)
            putShort(blockAlign.toShort()); putShort((BYTES_PER_SAMPLE * 8).toShort())
            put("data".toByteArray()); putInt(dataSize.toInt())
        }.array()
    }

    private companion object {
        const val HEADER_SIZE = 44
        const val BYTES_PER_SAMPLE = 2
    }
}
