package com.tapeaids.narrateme

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.core.types.RegistrationState
import com.tapeaids.narrateme.audio.NarrationPlayer
import com.tapeaids.narrateme.audio.NarrationRecorder
import com.tapeaids.narrateme.audio.Speaker
import com.tapeaids.narrateme.core.Cue
import com.tapeaids.narrateme.core.Effect
import com.tapeaids.narrateme.core.Input
import com.tapeaids.narrateme.core.Mode
import com.tapeaids.narrateme.core.NarrationController
import com.tapeaids.narrateme.core.PageStore
import com.tapeaids.narrateme.core.State
import com.tapeaids.narrateme.core.pickMatch
import com.tapeaids.narrateme.recognition.PageRecognizer
import com.tapeaids.narrateme.glasses.GlassesEvent
import com.tapeaids.narrateme.glasses.GlassesSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A saved page as shown on screen. */
class PageThumb(val id: String, val label: String, val bitmap: Bitmap?)

data class UiState(
    val mode: Mode = Mode.NARRATE,
    val state: State = State.Idle,
    val pages: List<PageThumb> = emptyList(),
    val lastMatch: String = "",
    val message: String = "",
    val useMock: Boolean = false,
)

/** Wires the glasses, audio and storage to the narrate/listen state machine. */
class NarrateViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PageStore(File(app.filesDir, "pages"))
    private val controller = NarrationController()
    private val recognizer = PageRecognizer()
    private val recorder = NarrationRecorder(app)
    private val player = NarrationPlayer()
    private val speaker = Speaker(app)
    val glasses = GlassesSession(app, viewModelScope)

    // Effects from successive inputs run strictly in order (e.g. a stop can't overtake a start).
    private val effectLock = Mutex()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val registration: StateFlow<RegistrationState> get() = glasses.registration

    init {
        viewModelScope.launch {
            glasses.events.collect { event ->
                handle(
                    when (event) {
                        GlassesEvent.CapturePressed -> Input.CapturePressed
                        GlassesEvent.ActionPressed -> Input.ActionPressed
                        is GlassesEvent.PhotoTaken -> Input.PhotoTaken(event.bytes)
                        is GlassesEvent.PhotoFailed -> Input.PhotoFailed(event.reason).also { say(it.reason) }
                    },
                )
            }
        }
        viewModelScope.launch { refreshPages() }
    }

    fun setMode(mode: Mode) {
        controller.mode = mode
        publish()
    }

    fun setUseMock(enabled: Boolean) {
        runCatching { glasses.enableMock(enabled) }
            .onFailure { say("Mock glasses failed: ${it.message}") }
        _ui.value = _ui.value.copy(useMock = enabled)
    }

    /** Deletes every saved page. Only between interactions. */
    fun clearPages() {
        if (controller.state != State.Idle) return
        viewModelScope.launch {
            effectLock.withLock {
                withContext(Dispatchers.IO) { store.clear() }
                recognizer.forgetAll()
                refreshPages()
                say("All pages cleared")
            }
        }
    }

    fun register(activity: Activity) = glasses.register(activity)
    fun connect() = glasses.connect()
    fun pressCapture() = glasses.pressCapture()
    fun pressAction() = glasses.pressAction()

    /** Feeds one input to the controller and carries out its effects in order. */
    private fun handle(input: Input) {
        val effects = controller.on(input)
        publish()
        if (effects.isEmpty()) return
        viewModelScope.launch {
            effectLock.withLock { for (effect in effects) run(effect) }
        }
    }

    private suspend fun run(effect: Effect) {
        when (effect) {
            Effect.TakePhoto -> {
                say("Taking photo…")
                glasses.capturePhoto()
            }
            Effect.StartRecording -> {
                val error = withContext(Dispatchers.IO) { recorder.start(File(getApplication<Application>().cacheDir, "recording.wav")) }
                if (error != null) {
                    say(error)
                    handle(Input.RecordingFailed(error))
                } else {
                    say("Recording… press the action button (or capture again) to stop")
                }
            }
            is Effect.StopRecordingAndSave -> {
                val saved = withContext(Dispatchers.IO) {
                    recorder.stop()?.let { store.save(effect.photo, it) }
                }
                say(if (saved != null) "Narration saved" else "Nothing was recorded")
                refreshPages()
            }
            is Effect.FindPage -> {
                say("Looking for the page…")
                val pages = withContext(Dispatchers.IO) { store.list() }
                val scores = withContext(Dispatchers.Default) {
                    runCatching { recognizer.score(effect.photo, pages) }
                        .onFailure { say("Page matching failed: ${it.message}") }
                        .getOrDefault(emptyList())
                }
                val pick = pickMatch(scores)
                val labels = pages.withIndex().associate { (i, p) -> p.id to "page ${i + 1}" }
                val summary = if (scores.isEmpty()) "no saved pages" else
                    scores.joinToString { "${labels[it.page.id]} = ${it.score}" }
                _ui.value = _ui.value.copy(
                    lastMatch = "Match scores: $summary → ${pick?.let { labels[it.id] } ?: "not recognised"}",
                )
                handle(if (pick != null) Input.PageFound(pick) else Input.PageNotFound)
            }
            is Effect.Play -> {
                say("Playing narration")
                player.play(
                    effect.page.audio,
                    onFinished = { handle(Input.PlaybackFinished) },
                    onError = {
                        say(it)
                        viewModelScope.launch { speaker.say("Playback failed") }
                        handle(Input.PlaybackFinished)
                    },
                )
            }
            Effect.StopPlayback -> {
                player.stop()
                say("Stopped")
            }
            is Effect.Announce -> speaker.say(effect.cue.phrase())
        }
    }

    private suspend fun refreshPages() {
        val thumbs = withContext(Dispatchers.IO) {
            store.list().mapIndexed { i, page -> PageThumb(page.id, "Page ${i + 1}", decodeThumbnail(page.photo)) }
        }
        _ui.value = _ui.value.copy(pages = thumbs)
    }

    private fun decodeThumbnail(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_PX) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun say(message: String) {
        _ui.value = _ui.value.copy(message = message)
    }

    private fun publish() {
        _ui.value = _ui.value.copy(mode = controller.mode, state = controller.state)
    }

    override fun onCleared() {
        recorder.stop()
        player.stop()
        speaker.release()
        glasses.disconnect()
    }

    private fun Cue.phrase() = when (this) {
        Cue.RECORDING_STARTED -> "Recording started"
        Cue.SAVED -> "Narration saved"
        Cue.RECOGNISED -> "Page recognised, starting narration"
        Cue.NOT_RECOGNISED -> "Page not recognised"
        Cue.PHOTO_FAILED -> "Photo failed, please try again"
        Cue.RECORDING_FAILED -> "Recording failed"
    }

    private companion object {
        const val THUMB_PX = 300
    }
}
