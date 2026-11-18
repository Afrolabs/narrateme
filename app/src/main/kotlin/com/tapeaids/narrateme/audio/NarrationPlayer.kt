package com.tapeaids.narrateme.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/**
 * Plays a narration as media audio. With the SCO link released (see
 * [NarrationRecorder.stop]), Android sends media to the glasses over A2DP.
 */
class NarrationPlayer {
    private var player: MediaPlayer? = null

    fun play(file: File, onFinished: () -> Unit, onError: (String) -> Unit) {
        stop()
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        mp.setOnCompletionListener {
            stop()
            onFinished()
        }
        mp.setOnErrorListener { _, what, extra ->
            stop()
            onError("Playback error $what/$extra")
            true
        }
        try {
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            stop()
            onError("Could not play narration: ${e.message}")
        }
    }

    fun stop() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
    }
}
