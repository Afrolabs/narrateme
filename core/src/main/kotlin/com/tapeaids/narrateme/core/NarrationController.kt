package com.tapeaids.narrateme.core

/** Whether a capture press records a new narration or plays an existing one. */
enum class Mode { NARRATE, LISTEN }

/** Spoken prompts for the wearer, who may not be able to see the phone. */
enum class Cue { RECORDING_STARTED, SAVED, RECOGNISED, NOT_RECOGNISED, PHOTO_FAILED, RECORDING_FAILED }

sealed interface State {
    data object Idle : State
    data object TakingPhoto : State
    data object Matching : State
    class Recording(val photo: ByteArray) : State
    data class Playing(val page: Page) : State
}

sealed interface Input {
    data object CapturePressed : Input
    data object ActionPressed : Input
    class PhotoTaken(val bytes: ByteArray) : Input
    data class PhotoFailed(val reason: String) : Input
    data class RecordingFailed(val reason: String) : Input
    data class PageFound(val page: Page) : Input
    data object PageNotFound : Input
    data object PlaybackFinished : Input
}

sealed interface Effect {
    data object TakePhoto : Effect
    /** Look for the stored page this photo shows; answer with PageFound or PageNotFound. */
    class FindPage(val photo: ByteArray) : Effect
    data object StartRecording : Effect
    class StopRecordingAndSave(val photo: ByteArray) : Effect
    data class Play(val page: Page) : Effect
    data object StopPlayback : Effect
    data class Announce(val cue: Cue) : Effect
}

/**
 * The narrate/listen loop as a pure state machine. Inputs come from the glasses
 * (button presses, photo results) and from the audio layer; the returned
 * effects are carried out by the app, in order.
 *
 * Not thread-safe: call [on] from a single thread.
 */
class NarrationController {
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
            is Input.PhotoFailed -> go(State.Idle, Effect.Announce(Cue.PHOTO_FAILED))
            else -> emptyList()
        }

        State.Matching -> when (input) {
            is Input.PageFound ->
                go(State.Playing(input.page), Effect.Announce(Cue.RECOGNISED), Effect.Play(input.page))
            Input.PageNotFound -> go(State.Idle, Effect.Announce(Cue.NOT_RECOGNISED))
            else -> emptyList()
        }

        is State.Recording -> when (input) {
            Input.ActionPressed, Input.CapturePressed ->
                go(State.Idle, Effect.StopRecordingAndSave(s.photo), Effect.Announce(Cue.SAVED))
            is Input.RecordingFailed -> go(State.Idle, Effect.Announce(Cue.RECORDING_FAILED))
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
            go(State.Recording(photo), Effect.Announce(Cue.RECORDING_STARTED), Effect.StartRecording)
        Mode.LISTEN -> go(State.Matching, Effect.FindPage(photo))
    }

    private fun go(next: State, vararg effects: Effect): List<Effect> {
        state = next
        return effects.toList()
    }
}
