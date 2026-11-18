package com.tapeaids.narrateme.core

/** Whether a capture press records a new narration or plays an existing one. */
enum class Mode { NARRATE, LISTEN }

/** Short audible signals for the wearer, who may not be able to see the phone. */
enum class Cue { RECORDING_STARTED, SAVED, NO_PAGE, ERROR }

sealed interface State {
    data object Idle : State
    data object TakingPhoto : State
    class Recording(val photo: ByteArray) : State
    data class Playing(val page: Page) : State
}

sealed interface Input {
    data object CapturePressed : Input
    data object ActionPressed : Input
    class PhotoTaken(val bytes: ByteArray) : Input
    data class PhotoFailed(val reason: String) : Input
    data class RecordingFailed(val reason: String) : Input
    data object PlaybackFinished : Input
}

sealed interface Effect {
    data object TakePhoto : Effect
    data object StartRecording : Effect
    class StopRecordingAndSave(val photo: ByteArray) : Effect
    data class Play(val page: Page) : Effect
    data object StopPlayback : Effect
    data class Beep(val cue: Cue) : Effect
}

/**
 * The narrate/listen loop as a pure state machine. Inputs come from the glasses
 * (button presses, photo results) and from the audio layer; the returned
 * effects are carried out by the app, in order.
 *
 * Not thread-safe: call [on] from a single thread.
 */
class NarrationController(private val matcher: PageMatcher) {
    var state: State = State.Idle
        private set

    /** Changing mode is only allowed between interactions. */
    var mode: Mode = Mode.NARRATE
        set(value) {
            if (state == State.Idle) field = value
        }

    fun on(input: Input): List<Effect> = when (val s = state) {
        State.Idle -> when (input) {
            Input.CapturePressed -> go(State.TakingPhoto, Effect.TakePhoto)
            else -> emptyList()
        }

        State.TakingPhoto -> when (input) {
            is Input.PhotoTaken -> photoTaken(input.bytes)
            is Input.PhotoFailed -> go(State.Idle, Effect.Beep(Cue.ERROR))
            else -> emptyList()
        }

        is State.Recording -> when (input) {
            Input.ActionPressed, Input.CapturePressed ->
                go(State.Idle, Effect.StopRecordingAndSave(s.photo), Effect.Beep(Cue.SAVED))
            is Input.RecordingFailed -> go(State.Idle, Effect.Beep(Cue.ERROR))
            else -> emptyList()
        }

        is State.Playing -> when (input) {
            Input.CapturePressed -> go(State.Idle, Effect.StopPlayback)
            Input.PlaybackFinished -> go(State.Idle)
            else -> emptyList()
        }
    }

    private fun photoTaken(photo: ByteArray): List<Effect> = when (mode) {
        Mode.NARRATE ->
            go(State.Recording(photo), Effect.Beep(Cue.RECORDING_STARTED), Effect.StartRecording)
        Mode.LISTEN -> when (val page = matcher.match(photo)) {
            null -> go(State.Idle, Effect.Beep(Cue.NO_PAGE))
            else -> go(State.Playing(page), Effect.Play(page))
        }
    }

    private fun go(next: State, vararg effects: Effect): List<Effect> {
        state = next
        return effects.toList()
    }
}
