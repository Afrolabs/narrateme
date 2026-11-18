package com.tapeaids.narrateme.audio

import android.media.AudioManager
import android.media.ToneGenerator
import com.tapeaids.narrateme.core.Cue

/** Plays short tones so the wearer knows what happened without looking at the phone. */
class Beeper {
    private val tones = ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)

    /** Plays the tone for [cue] and returns its length in milliseconds. */
    fun play(cue: Cue): Long {
        val (tone, ms) = when (cue) {
            Cue.RECORDING_STARTED -> ToneGenerator.TONE_PROP_BEEP to 200
            Cue.SAVED -> ToneGenerator.TONE_PROP_ACK to 300
            Cue.NO_PAGE -> ToneGenerator.TONE_PROP_NACK to 400
            Cue.ERROR -> ToneGenerator.TONE_SUP_ERROR to 500
        }
        tones.startTone(tone, ms)
        return ms.toLong()
    }

    fun release() = tones.release()

    private companion object {
        const val VOLUME = 80
    }
}
