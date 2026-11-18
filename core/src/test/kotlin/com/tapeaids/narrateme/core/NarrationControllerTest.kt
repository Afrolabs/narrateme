package com.tapeaids.narrateme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NarrationControllerTest {
    private val photo = byteArrayOf(1, 2, 3)
    private val page = Page(File("page.jpg"), File("narration.wav"))

    private fun controller(match: Page? = null, mode: Mode = Mode.NARRATE) =
        NarrationController { match }.also { it.mode = mode }

    @Test
    fun `narrate happy path takes photo, records, then saves on action button`() {
        val c = controller()
        assertEquals(listOf(Effect.TakePhoto), c.on(Input.CapturePressed))
        assertEquals(State.TakingPhoto, c.state)

        assertEquals(
            listOf(Effect.Beep(Cue.RECORDING_STARTED), Effect.StartRecording),
            c.on(Input.PhotoTaken(photo)),
        )
        assertTrue(c.state is State.Recording)

        val effects = c.on(Input.ActionPressed)
        assertEquals(2, effects.size)
        val save = effects[0] as Effect.StopRecordingAndSave
        assertSame(photo, save.photo)
        assertEquals(Effect.Beep(Cue.SAVED), effects[1])
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
    fun `photo failure beeps error and returns to idle`() {
        val c = controller()
        c.on(Input.CapturePressed)
        assertEquals(listOf(Effect.Beep(Cue.ERROR)), c.on(Input.PhotoFailed("boom")))
        assertEquals(State.Idle, c.state)
    }

    @Test
    fun `recording failure beeps error and saves nothing`() {
        val c = controller()
        c.on(Input.CapturePressed)
        c.on(Input.PhotoTaken(photo))
        assertEquals(listOf(Effect.Beep(Cue.ERROR)), c.on(Input.RecordingFailed("no sco")))
        assertEquals(State.Idle, c.state)
    }

    @Test
    fun `listen with a stored page plays it, capture stops it`() {
        val c = controller(match = page, mode = Mode.LISTEN)
        assertEquals(listOf(Effect.TakePhoto), c.on(Input.CapturePressed))
        assertEquals(listOf(Effect.Play(page)), c.on(Input.PhotoTaken(photo)))
        assertEquals(State.Playing(page), c.state)
        assertEquals(listOf(Effect.StopPlayback), c.on(Input.CapturePressed))
        assertEquals(State.Idle, c.state)
    }

    @Test
    fun `playback finishing returns to idle`() {
        val c = controller(match = page, mode = Mode.LISTEN)
        c.on(Input.CapturePressed)
        c.on(Input.PhotoTaken(photo))
        assertEquals(emptyList<Effect>(), c.on(Input.PlaybackFinished))
        assertEquals(State.Idle, c.state)
    }

    @Test
    fun `listen with no stored page beeps no-page`() {
        val c = controller(match = null, mode = Mode.LISTEN)
        c.on(Input.CapturePressed)
        assertEquals(listOf(Effect.Beep(Cue.NO_PAGE)), c.on(Input.PhotoTaken(photo)))
        assertEquals(State.Idle, c.state)
    }

    @Test
    fun `action button is ignored when idle or listening`() {
        assertEquals(emptyList<Effect>(), controller().on(Input.ActionPressed))
        val c = controller(match = page, mode = Mode.LISTEN)
        c.on(Input.CapturePressed)
        c.on(Input.PhotoTaken(photo))
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
        assertEquals(State.Idle, c.state)
    }
}
