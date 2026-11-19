package com.tapeaids.narrateme.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.tapeaids.narrateme.R

/** Plays the page-turn swish when a page is snapped, on the media stream like the narration. */
class PageTurnSound(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val sound = pool.load(context, R.raw.page_turn, 1)

    fun play() {
        pool.play(sound, 1f, 1f, 1, 0, 1f)
    }

    fun release() = pool.release()
}
