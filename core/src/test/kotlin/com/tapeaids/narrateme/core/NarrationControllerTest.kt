package com.tapeaids.narrateme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NarrationControllerTest {
    private val photo = byteArrayOf(1, 2, 3)
    private val page = Page("1", File("page.jpg"), File("narration.wav"))

    private fun controller(mode: Mode = Mode.NARRATE) =
        NarrationController().also { it.mode = mode }

    /** Drives a listen-mode controller to the point where the page has been found and is playing. */
    private fun playing(): NarrationController = controller(Mode.LISTEN).apply {
        on(Input.CapturePressed)
        on(Input.PhotoTaken(photo))
        on(Input.PageFound(page))
    }

    @Test
    fun `narrate happy path takes photo, records, then saves on action button`() {
        val c = controller()
        assertEquals(listOf(Effect.TakePhoto), c.on(Input.CapturePressed))
        assertEquals(State.TakingPhoto, c.state)

        assertEquals(
            listOf(Effect.Announce(Cue.RECORDING_STARTED), Effect.StartRecording),
            c.on(Input.PhotoTaken(photo)),
        )
        assertTrue(c.state is State.Recording)

        val effects = c.on(Input.ActionPressed)
        assertEquals(2, effects.size)
        val save = effects[0] as Effect.StopRecordingAndSave
        assertSame(photo, save.photo)
        assertEquals(Effect.Announce(Cue.SAVED), effects[1])
        assertEquals(State.Idle, c.state)
    }

    @Test
    fun `second capture press also stops a recording`() {
        val c = controller()
        c.on(Input.CapturePressed)
        c.on(Input.PhotoTaken(photo))
        val effects = c.on(Input.CapturePressed)
        assertTrue(effects[0] is Effect.StopRecordingAndSave)
        assertEquals(State.Idle, c.state)
    }

    @Test
    fun `capture while a photo is in flight is ignored`() {
        val c = controller()
        c.on(Input.CapturePressed)
        assertEquals(emptyList<Effect>(), c.on(Input.CapturePressed))
        assertEquals(State.TakingPhoto, c.state)
    }

    @Test
    fun `photo failure announces it and returns to idle`() {
        val c = controller()
        c.on(Input.CapturePressed)
        assertEquals(listOf(Effect.Announce(Cue.PHOTO_FAILED)), c.on(Input.PhotoFailed("boom")))
        assertEquals(State.Idle, c.state)
    }

    @Test
    fun `recording failure announces it and saves nothing`() {
        val c = controller()
        c.on(Input.CapturePressed)
        c.on(Input.PhotoTaken(photo))
        assertEquals(listOf(Effect.Announce(Cue.RECORDING_FAILED)), c.on(Input.RecordingFailed("no sco")))
        assertEquals(State.Idle, c.state)
    }

    @Test
    fun `mode change is ignored unless idle`() {
        val c = controller()
        c.on(Input.CapturePressed)
        c.mode = Mode.LISTEN
        assertEquals(Mode.NARRATE, c.mode)
        c.on(Input.PhotoFailed("x"))
        c.mode = Mode.LISTEN
        assertEquals(Mode.LISTEN, c.mode)
    }

    @Test
    fun `stray results in the wrong state are ignored`() {
        val c = controller()
        assertEquals(emptyList<Effect>(), c.on(Input.PhotoTaken(photo)))
        assertEquals(emptyList<Effect>(), c.on(Input.RecordingFailed("late")))
        assertEquals(emptyList<Effect>(), c.on(Input.PlaybackFinished))
        assertEquals(emptyList<Effect>(), c.on(Input.PageFound(page)))
        assertEquals(emptyList<Effect>(), c.on(Input.PageNotFound))
        assertEquals(emptyList<Effect>(), c.on(Input.ScreenTapped))
        assertEquals(State.Idle, c.state)
    }

    @Test
    fun `action button is ignored when idle`() {
        assertEquals(emptyList<Effect>(), controller().on(Input.ActionPressed))
        assertEquals(emptyList<Effect>(), controller(Mode.LISTEN).on(Input.ActionPressed))
    }

    @Test
    fun `listen starts scanning, then a snapped page is matched`() {
        val c = controller(Mode.LISTEN)
        assertEquals(
            listOf(Effect.Announce(Cue.LOOKING), Effect.StartScanning(fresh = true)),
            c.on(Input.CapturePressed),
        )
        assertEquals(State.Scanning, c.state)
        val effects = c.on(Input.PhotoTaken(photo))
        assertEquals(Effect.StopScanning, effects[0])
        assertSame(photo, (effects[1] as Effect.FindPage).photo)
        assertEquals(2, effects.size)
        assertEquals(State.Matching, c.state)
    }

    @Test
    fun `a recognised page plays the page-turn sound, then is read`() {
        val c = controller(Mode.LISTEN)
        c.on(Input.CapturePressed)
        c.on(Input.PhotoTaken(photo))
        assertEquals(listOf(Effect.PageTurnSound, Effect.Play(page)), c.on(Input.PageFound(page)))
        assertEquals(State.Playing(page), c.state)
    }

    @Test
    fun `an unrecognised page is announced and scanning carries on`() {
        val c = controller(Mode.LISTEN)
        c.on(Input.CapturePressed)
        c.on(Input.PhotoTaken(photo))
        assertEquals(
            listOf(Effect.Announce(Cue.NOT_RECOGNISED), Effect.StartScanning(fresh = false)),
            c.on(Input.PageNotFound),
        )
        assertEquals(State.Scanning, c.state)
    }

    @Test
    fun `when a narration ends it scans for the next page`() {
        val c = playing()
        assertEquals(listOf(Effect.StartScanning(fresh = false)), c.on(Input.PlaybackFinished))
        assertEquals(State.Scanning, c.state)
    }

    @Test
    fun `tapping the screen pauses and resumes`() {
        val c = playing()
        assertEquals(listOf(Effect.PausePlayback), c.on(Input.ScreenTapped))
        assertEquals(State.Paused(page), c.state)
        assertEquals(listOf(Effect.ResumePlayback), c.on(Input.ScreenTapped))
        assertEquals(State.Playing(page), c.state)
    }

    @Test
    fun `tapping while scanning does nothing`() {
        val c = controller(Mode.LISTEN)
        c.on(Input.CapturePressed)
        assertEquals(emptyList<Effect>(), c.on(Input.ScreenTapped))
        assertEquals(State.Scanning, c.state)
    }

    @Test
    fun `capture while scanning snaps once, for the glasses`() {
        val c = controller(Mode.LISTEN)
        c.on(Input.CapturePressed)
        assertEquals(listOf(Effect.TakePhoto), c.on(Input.CapturePressed))
        assertEquals(emptyList<Effect>(), c.on(Input.CapturePressed))
        c.on(Input.PhotoFailed("x"))
        assertEquals(State.Scanning, c.state)
        assertEquals(listOf(Effect.TakePhoto), c.on(Input.CapturePressed))
    }

    @Test
    fun `capture while reading skips to the page now in view`() {
        val c = playing()
        assertEquals(
            listOf(Effect.StopPlayback, Effect.StartScanning(fresh = false), Effect.TakePhoto),
            c.on(Input.CapturePressed),
        )
        assertEquals(State.Scanning, c.state)
    }

    @Test
    fun `action stops listening from any listen state`() {
        val scanning = controller(Mode.LISTEN).apply { on(Input.CapturePressed) }
        assertEquals(
            listOf(Effect.StopScanning, Effect.Announce(Cue.STOPPED_LISTENING)),
            scanning.on(Input.ActionPressed),
        )
        assertEquals(State.Idle, scanning.state)

        val reading = playing()
        assertEquals(
            listOf(Effect.StopPlayback, Effect.StopScanning, Effect.Announce(Cue.STOPPED_LISTENING)),
            reading.on(Input.ActionPressed),
        )
        assertEquals(State.Idle, reading.state)

        val paused = playing().apply { on(Input.ScreenTapped) }
        paused.on(Input.ActionPressed)
        assertEquals(State.Idle, paused.state)
    }

    @Test
    fun `a match arriving after listening stopped is ignored`() {
        val c = controller(Mode.LISTEN)
        c.on(Input.CapturePressed)
        c.on(Input.PhotoTaken(photo))
        c.on(Input.ActionPressed)
        assertEquals(emptyList<Effect>(), c.on(Input.PageFound(page)))
        assertEquals(State.Idle, c.state)
    }
}
