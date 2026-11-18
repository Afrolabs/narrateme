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
    fun `listen looks the page up, announces it, then plays it`() {
        val c = controller(Mode.LISTEN)
        assertEquals(listOf(Effect.TakePhoto), c.on(Input.CapturePressed))
        val find = c.on(Input.PhotoTaken(photo)).single() as Effect.FindPage
        assertSame(photo, find.photo)
        assertEquals(State.Matching, c.state)
        assertEquals(
            listOf(Effect.Announce(Cue.RECOGNISED), Effect.Play(page)),
            c.on(Input.PageFound(page)),
        )
        assertEquals(State.Playing(page), c.state)
    }

    @Test
    fun `capture stops playback`() {
        val c = playing()
        assertEquals(listOf(Effect.StopPlayback), c.on(Input.CapturePressed))
        assertEquals(State.Idle, c.state)
    }

    @Test
    fun `playback finishing returns to idle`() {
        val c = playing()
        assertEquals(emptyList<Effect>(), c.on(Input.PlaybackFinished))
        assertEquals(State.Idle, c.state)
    }

    @Test
    fun `unrecognised page is announced and returns to idle`() {
        val c = controller(Mode.LISTEN)
        c.on(Input.CapturePressed)
        c.on(Input.PhotoTaken(photo))
        assertEquals(listOf(Effect.Announce(Cue.NOT_RECOGNISED)), c.on(Input.PageNotFound))
        assertEquals(State.Idle, c.state)
    }

    @Test
    fun `buttons are ignored while matching`() {
        val c = controller(Mode.LISTEN)
        c.on(Input.CapturePressed)
        c.on(Input.PhotoTaken(photo))
        assertEquals(emptyList<Effect>(), c.on(Input.CapturePressed))
        assertEquals(emptyList<Effect>(), c.on(Input.ActionPressed))
        assertEquals(State.Matching, c.state)
    }

    @Test
    fun `action button is ignored when idle or playing`() {
        assertEquals(emptyList<Effect>(), controller().on(Input.ActionPressed))
        val c = playing()
        assertEquals(emptyList<Effect>(), c.on(Input.ActionPressed))
        assertEquals(State.Playing(page), c.state)
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
        assertEquals(State.Idle, c.state)
    }
}
