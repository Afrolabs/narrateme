package com.tapeaids.narrateme.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.tapeaids.narrateme.core.WavFileWriter
import com.tapeaids.narrateme.device.MicSource
import java.io.File
import kotlin.concurrent.thread

/**
 * Records the narrator to a 16-bit mono WAV file, from either microphone:
 *
 * - [MicSource.PHONE]: the phone's built-in mic at 44.1 kHz (16 kHz fallback).
 * - [MicSource.BLUETOOTH]: the glasses mics, which come over Bluetooth HFP (SCO).
 *   This routes communication audio to the glasses and records at 16 kHz, or 8 kHz
 *   if that's all the link offers. [stop] releases the SCO link so playback can use A2DP.
 */
class NarrationRecorder(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private var output: File? = null
    private var routedSco = false
    @Volatile private var running = false

    /** Starts recording to [file]. Returns an error message, or null on success. */
    @SuppressLint("MissingPermission") // checked below
    fun start(file: File, mic: MicSource): String? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "Microphone permission not granted"
        }
        val bluetooth = mic == MicSource.BLUETOOTH
        if (bluetooth) {
            routedSco = true
            if (!routeToGlasses()) {
                releaseRoute()
                return "Glasses microphone not available over Bluetooth"
            }
        }
        val rates = if (bluetooth) SCO_SAMPLE_RATES else PHONE_SAMPLE_RATES
        val source = if (bluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        val rec = rates.firstNotNullOfOrNull { openRecord(it, source) }
        if (rec == null) {
            releaseRoute()
            return "Could not open the microphone"
        }
        val preferred = if (bluetooth) input(AudioDeviceInfo.TYPE_BLUETOOTH_SCO) else input(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        preferred?.let { rec.setPreferredDevice(it) }

        val writer = WavFileWriter(file, rec.sampleRate)
        record = rec
        output = file
        running = true
        rec.startRecording()
        worker = thread(name = "narration-recorder") {
            val buffer = ByteArray(BUFFER_BYTES)
            try {
                while (running) {
                    val n = rec.read(buffer, 0, buffer.size)
                    if (n > 0) writer.write(buffer, n)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording failed", e)
            } finally {
                writer.close()
            }
        }
        Log.i(TAG, "Recording at ${rec.sampleRate} Hz to $file")
        return null
    }

    /** Stops recording and returns the finished WAV file, or null if nothing was recording. */
    fun stop(): File? {
        if (!running) return null
        running = false
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        worker?.join(JOIN_TIMEOUT_MS)
        record = null
        worker = null
        releaseRoute()
        return output.also { output = null }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(rate: Int, source: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val rec = AudioRecord(
            source,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, BUFFER_BYTES) * 2,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        return rec
    }

    private fun routeToGlasses(): Boolean {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sco = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: return false
            audioManager.setCommunicationDevice(sco)
        } else {
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            true
        }
    }

    private fun releaseRoute() {
        if (!routedSco) return
        routedSco = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun input(type: Int): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == type }

    private companion object {
        const val TAG = "NarrationRecorder"
        val SCO_SAMPLE_RATES = listOf(16_000, 8_000)
        val PHONE_SAMPLE_RATES = listOf(44_100, 16_000)
        const val BUFFER_BYTES = 8_820 // 100 ms at 44.1 kHz mono PCM16
        const val JOIN_TIMEOUT_MS = 2_000L
    }
}
