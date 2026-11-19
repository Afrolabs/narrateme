package com.tapeaids.narrateme.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SnapDetectorTest {
    private val size = 64 * 48

    /** A textured "page": stable pseudo-random pattern per seed. */
    private fun page(seed: Int): IntArray {
        val r = Random(seed)
        return IntArray(size) { r.nextInt(40, 220) }
    }

    private fun blank(level: Int = 128) = IntArray(size) { level }

    private fun noisy(frame: IntArray, amount: Int, seed: Int): IntArray {
        val r = Random(seed)
        return IntArray(size) { (frame[it] + r.nextInt(-amount, amount + 1)).coerceIn(0, 255) }
    }

    private fun detector() = SnapDetector(stillFrames = 3)

    /** Offers [frame] n times and returns how many snaps fired. */
    private fun SnapDetector.hold(frame: IntArray, n: Int = 6): Int = (1..n).count { offer(frame) }

    @Test
    fun `a page held still is snapped once`() {
        val d = detector()
        assertEquals(1, d.hold(page(1), 20))
    }

    @Test
    fun `nothing is snapped while the view keeps moving`() {
        val d = detector()
        val snaps = (1..20).count { d.offer(page(it)) }
        assertEquals(0, snaps)
    }

    @Test
    fun `a flat view without writing is not snapped`() {
        assertEquals(0, detector().hold(blank(), 20))
    }

    @Test
    fun `small hand shake around the same page doesn't snap it again`() {
        val d = detector()
        val p = page(1)
        assertEquals(1, d.hold(p))
        val snaps = (1..30).count { d.offer(noisy(p, 3, it / 6)) }
        assertEquals(0, snaps)
    }

    @Test
    fun `turning to a different page snaps the new one`() {
        val d = detector()
        assertEquals(1, d.hold(page(1)))
        // A hand passes through the view, then the next page is held still.
        d.offer(blank(20))
        d.offer(blank(240))
        assertEquals(1, d.hold(page(2)))
    }

    @Test
    fun `big motion then the same page again doesn't count as new`() {
        val d = detector()
        val p = page(1)
        assertEquals(1, d.hold(p))
        d.offer(blank(20))
        assertEquals(0, d.hold(p))
    }

    @Test
    fun `pause keeps the last snap, reset forgets it`() {
        val d = detector()
        val p = page(1)
        assertEquals(1, d.hold(p))
        d.pause()
        assertEquals(0, d.hold(p))
        d.reset()
        assertEquals(1, d.hold(p))
    }

    @Test
    fun `downsample averages blocks`() {
        val w = 4
        val h = 2
        val stride = 6 // padded rows
        val luma = ByteArray(stride * h)
        for (y in 0 until h) for (x in 0 until w) luma[y * stride + x] = if (x < 2) 10 else 200.toByte()
        val out = SnapDetector.downsample(luma, w, h, stride, 2, 1)
        assertEquals(listOf(10, 200), out.toList())
        assertTrue(SnapDetector.contrast(out) > 0)
        assertFalse(SnapDetector.contrast(IntArray(4) { 5 }) > 0)
    }
}
