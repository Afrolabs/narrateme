package com.tapeaids.narrateme.core

/** Whether the user is recording narrations (NARRATE) or listening to them (LISTEN). */
enum class Mode { NARRATE, LISTEN }

/** Spoken prompts for the user, who may not be able to see the phone. */
enum class Cue {
    RECORDING_STARTED, SAVED, RECOGNISED, NOT_RECOGNISED, PHOTO_FAILED, RECORDING_FAILED,
    /** Listening started: the camera is looking for a page. */
    LOOKING,
    /** Listening stopped. */
    STOPPED_LISTENING,
}

sealed interface State {
    data object Idle : State
    data object TakingPhoto : State
    class Recording(val photo: ByteArray) : State

    /** Listening: waiting for a page to come into view (or, on the glasses, a capture press). */
    data object Scanning : State
    data object Matching : State
    data class Playing(val page: Page) : State
    data class Paused(val page: Page) : State
}

sealed interface Input {
    data object CapturePressed : Input
    data object ActionPressed : Input

    /** A tap anywhere on the listen screen: pause or resume the narration. */
    data object ScreenTapped : Input
    class PhotoTaken(val bytes: ByteArray) : Input
    data class PhotoFailed(val reason: String) : Input
    data class RecordingFailed(val reason: String) : Input
    data class PageFound(val page: Page) : Input
    data object PageNotFound : Input
    data object PlaybackFinished : Input
}

sealed interface Effect {
    data object TakePhoto : Effect

    /**
     * Watch for a page and snap it when one is held in view (phone), answering with
     * PhotoTaken. [fresh] is true when listening has just started.
     */
    data class StartScanning(val fresh: Boolean) : Effect
    data object StopScanning : Effect
    data object PageTurnSound : Effect

    /** Look for the stored page this photo shows; answer with PageFound or PageNotFound. */
    class FindPage(val photo: ByteArray) : Effect
    data object StartRecording : Effect
    class StopRecordingAndSave(val photo: ByteArray) : Effect
    data class Play(val page: Page) : Effect
    data object PausePlayback : Effect
    data object ResumePlayback : Effect
    data object StopPlayback : Effect
    data class Announce(val cue: Cue) : Effect
}

/**
 * The narrate/listen loop as a pure state machine. Inputs come from the device
 * (button presses, photos, screen taps) and from the audio layer; the returned
 * effects are carried out by the app, in order.
 *
 * Narrate: capture takes a photo and starts recording; action (or capture) saves.
 *
 * Listen: capture starts listening. While scanning, a photo (snapped automatically
 * on the phone, or by a capture press on the glasses) is matched against the saved
 * pages; a match is read out, otherwise "not recognised" and back to scanning.
 * When a narration ends it goes back to scanning for the next page. A screen tap
 * pauses or resumes; capture skips to the next page; action stops listening.
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

    private var photoInFlight = false

    fun on(input: Input): List<Effect> = when (val s = state) {
        State.Idle -> when (input) {
            Input.CapturePressed -> when (mode) {
                Mode.NARRATE -> go(State.TakingPhoto, Effect.TakePhoto)
                Mode.LISTEN -> {
                    photoInFlight = false
                    go(State.Scanning, Effect.Announce(Cue.LOOKING), Effect.StartScanning(fresh = true))
                }
            }
            else -> emptyList()
        }

        // Narrate
        State.TakingPhoto -> when (input) {
            is Input.PhotoTaken ->
                go(State.Recording(input.bytes), Effect.Announce(Cue.RECORDING_STARTED), Effect.StartRecording)
            is Input.PhotoFailed -> go(State.Idle, Effect.Announce(Cue.PHOTO_FAILED))
            else -> emptyList()
        }

        is State.Recording -> when (input) {
            Input.ActionPressed, Input.CapturePressed ->
                go(State.Idle, Effect.StopRecordingAndSave(s.photo), Effect.Announce(Cue.SAVED))
            is Input.RecordingFailed -> go(State.Idle, Effect.Announce(Cue.RECORDING_FAILED))
            else -> emptyList()
        }

        // Listen
        State.Scanning -> when (input) {
            Input.CapturePressed ->
                if (photoInFlight) emptyList() else {
                    photoInFlight = true
                    listOf(Effect.TakePhoto)
                }
            is Input.PhotoTaken -> {
                photoInFlight = false
                go(State.Matching, Effect.StopScanning, Effect.PageTurnSound, Effect.FindPage(input.bytes))
            }
            is Input.PhotoFailed -> {
                photoInFlight = false
                listOf(Effect.Announce(Cue.PHOTO_FAILED))
            }
            Input.ActionPressed -> stopListening()
            else -> emptyList()
        }

        State.Matching -> when (input) {
            is Input.PageFound ->
                go(State.Playing(input.page), Effect.Announce(Cue.RECOGNISED), Effect.Play(input.page))
            Input.PageNotFound ->
                go(State.Scanning, Effect.Announce(Cue.NOT_RECOGNISED), Effect.StartScanning(fresh = false))
            Input.ActionPressed -> stopListening()
            else -> emptyList()
        }

        is State.Playing -> when (input) {
            Input.ScreenTapped -> go(State.Paused(s.page), Effect.PausePlayback)
            Input.PlaybackFinished -> go(State.Scanning, Effect.StartScanning(fresh = false))
            Input.CapturePressed -> nextPage()
            Input.ActionPressed -> stopListening(Effect.StopPlayback)
            else -> emptyList()
        }

        is State.Paused -> when (input) {
            Input.ScreenTapped -> go(State.Playing(s.page), Effect.ResumePlayback)
            Input.CapturePressed -> nextPage()
            Input.ActionPressed -> stopListening(Effect.StopPlayback)
            else -> emptyList()
        }
    }

    /** Stops the narration and snaps the page now in view. */
    private fun nextPage(): List<Effect> {
        photoInFlight = true
        return go(State.Scanning, Effect.StopPlayback, Effect.StartScanning(fresh = false), Effect.TakePhoto)
    }

    private fun stopListening(vararg first: Effect): List<Effect> =
        go(State.Idle, *first, Effect.StopScanning, Effect.Announce(Cue.STOPPED_LISTENING))

    private fun go(next: State, vararg effects: Effect): List<Effect> {
        state = next
        return effects.toList()
    }
}
