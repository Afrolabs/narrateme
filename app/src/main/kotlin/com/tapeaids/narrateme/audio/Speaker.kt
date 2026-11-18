package com.tapeaids.narrateme.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Speaks short prompts through the media stream (so they reach the glasses' speakers
 * over Bluetooth, like the narration). [say] returns once the prompt has finished.
 */
class Speaker(context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId)
        })
    }

    private fun finish(id: String) {
        pending.remove(id)?.complete(Unit)
    }

    suspend fun say(text: String) {
        if (withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } != true) {
            Log.w(TAG, "Text-to-speech unavailable, can't say: $text")
            return
        }
        if (!languageSet) {
            languageSet = true
            tts.setLanguage(Locale.UK)
        }
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return
        }
        withTimeoutOrNull(SPEAK_TIMEOUT_MS) { done.await() }
        pending.remove(id)
    }

    @Volatile private var languageSet = false

    fun release() = tts.shutdown()

    private companion object {
        const val TAG = "Speaker"
        const val INIT_TIMEOUT_MS = 3_000L
        const val SPEAK_TIMEOUT_MS = 10_000L
    }
}
