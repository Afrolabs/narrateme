package com.tapeaids.narrateme.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Decides, from a stream of small greyscale camera frames, when to snap a photo of
 * the page: the view has held still for [stillFrames] frames, has enough contrast
 * to be a page with writing on it, and is a new view, not the one snapped last.
 *
 * "New" means either the steady view differs from the last snap by more than
 * [newView], or something big moved through the view since then (a hand turning
 * the page, for instance) and the view differs by at least [smallChange]. That way
 * the page that was just read, or just not recognised, isn't snapped again while
 * it stays in view, but turning to the next page is.
 *
 * Frames are luma values 0..255, all the same size. Thresholds are mean absolute
 * differences per pixel.
 */
class SnapDetector(
    private val stillFrames: Int = 10,
    private val still: Double = 4.0,
    private val bigMotion: Double = 20.0,
    private val newView: Double = 14.0,
    private val smallChange: Double = 4.0,
    private val minContrast: Double = 18.0,
) {
    private var previous: IntArray? = null
    private var lastSnap: IntArray? = null
    private var stillCount = 0
    private var movedSinceSnap = false

    /** Feeds one frame; returns true if this is the moment to snap. */
    fun offer(frame: IntArray): Boolean {
        val prev = previous
        previous = frame
        if (prev == null) return false
        val motion = meanAbsDiff(prev, frame)
        if (motion > bigMotion) movedSinceSnap = true
        stillCount = if (motion < still) stillCount + 1 else 0
        if (stillCount < stillFrames) return false
        if (contrast(frame) < minContrast) return false
        val snap = lastSnap
        if (snap != null) {
            val change = meanAbsDiff(snap, frame)
            val isNew = change > newView || (movedSinceSnap && change > smallChange)
            if (!isNew) return false
        }
        lastSnap = frame
        movedSinceSnap = false
        stillCount = 0
        return true
    }

    /** Pauses tracking (e.g. while a page is read) without forgetting the last snap. */
    fun pause() {
        previous = null
        stillCount = 0
    }

    /** Forgets everything, so the first steady page is snapped. */
    fun reset() {
        pause()
        lastSnap = null
        movedSinceSnap = false
    }

    companion object {
        fun meanAbsDiff(a: IntArray, b: IntArray): Double {
            require(a.size == b.size) { "Frames differ in size" }
            var sum = 0L
            for (i in a.indices) sum += abs(a[i] - b[i])
            return sum.toDouble() / a.size
        }

        /** Standard deviation of the luma values. */
        fun contrast(frame: IntArray): Double {
            val mean = frame.average()
            var sq = 0.0
            for (v in frame) sq += (v - mean) * (v - mean)
            return sqrt(sq / frame.size)
        }

        /**
         * Shrinks a luma plane to [outW] x [outH] by averaging blocks. [rowStride] is
         * the bytes per row in [luma]; pixels are one byte each.
         */
        fun downsample(luma: ByteArray, width: Int, height: Int, rowStride: Int, outW: Int, outH: Int): IntArray {
            val out = IntArray(outW * outH)
            val bw = width / outW
            val bh = height / outH
            for (oy in 0 until outH) for (ox in 0 until outW) {
                var sum = 0
                // Sample every other pixel in the block; plenty for a motion signal.
                var n = 0
                var y = oy * bh
                while (y < (oy + 1) * bh) {
                    var x = ox * bw
                    val row = y * rowStride
                    while (x < (ox + 1) * bw) {
                        sum += luma[row + x].toInt() and 0xFF
                        n++
                        x += 2
                    }
                    y += 2
                }
                out[oy * outW + ox] = if (n == 0) 0 else sum / n
            }
            return out
        }
    }
}
