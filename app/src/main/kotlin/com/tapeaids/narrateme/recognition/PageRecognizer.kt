package com.tapeaids.narrateme.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tapeaids.narrateme.core.Page
import com.tapeaids.narrateme.core.PageScore
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * Scores how well a photo matches each stored page photo. It finds ORB keypoints
 * (corners in the handwriting, ruling, blots), pairs them up between the two
 * photos, and counts how many pairs agree on one perspective transform. That count
 * survives a different angle, distance or light, and stays low for a different page.
 *
 * Not thread-safe; call from one coroutine at a time.
 */
class PageRecognizer {
    private class Features(val keypoints: MatOfKeyPoint, val descriptors: Mat)

    private val orb = ORB.create(MAX_FEATURES)
    private val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
    private val cache = HashMap<String, Features>()

    fun score(photo: ByteArray, pages: List<Page>): List<PageScore> {
        cache.keys.retainAll(pages.map { it.id }.toSet())
        val query = decode(BitmapFactory.decodeByteArray(photo, 0, photo.size, sampled(photo)))
            ?: return pages.map { PageScore(it, 0) }
        return pages.map { page ->
            val stored = cache.getOrPut(page.id) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(page.photo.path, opts)
                decode(BitmapFactory.decodeFile(page.photo.path, sampleOptions(opts)))
                    ?: Features(MatOfKeyPoint(), Mat())
            }
            PageScore(page, inliers(query, stored))
        }
    }

    fun forgetAll() = cache.clear()

    private fun sampled(bytes: ByteArray): BitmapFactory.Options {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return sampleOptions(opts)
    }

    /** Decodes at no less than [WORK_PX] on the long side, but skips needless full-size decodes. */
    private fun sampleOptions(bounds: BitmapFactory.Options) = BitmapFactory.Options().apply {
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= WORK_PX) sample *= 2
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    private fun decode(bitmap: Bitmap?): Features? {
        bitmap ?: return null
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        bitmap.recycle()
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val longSide = max(gray.cols(), gray.rows())
        if (longSide > WORK_PX) {
            val scale = WORK_PX.toDouble() / longSide
            Imgproc.resize(gray, gray, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        }
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        orb.detectAndCompute(gray, Mat(), keypoints, descriptors)
        gray.release()
        return Features(keypoints, descriptors)
    }

    private fun inliers(query: Features, stored: Features): Int {
        if (query.descriptors.empty() || stored.descriptors.empty()) return 0
        val knn = mutableListOf<MatOfDMatch>()
        matcher.knnMatch(query.descriptors, stored.descriptors, knn, 2)
        // Lowe's ratio test: keep a pair only if it's clearly better than the next-best candidate.
        val good = knn.mapNotNull { pair ->
            val m = pair.toArray()
            if (m.size == 2 && m[0].distance < RATIO * m[1].distance) m[0] else null
        }
        if (good.size < MIN_PAIRS) return 0
        val qk = query.keypoints.toArray()
        val sk = stored.keypoints.toArray()
        val src = MatOfPoint2f(*good.map { qk[it.queryIdx].pt }.toTypedArray())
        val dst = MatOfPoint2f(*good.map { sk[it.trainIdx].pt }.toTypedArray())
        val mask = Mat()
        val homography = Calib3d.findHomography(src, dst, Calib3d.RANSAC, RANSAC_PX, mask)
        val count = if (homography.empty()) 0 else Core.countNonZero(mask)
        listOf<Mat>(src, dst, mask, homography).forEach { it.release() }
        return count
    }

    private companion object {
        const val WORK_PX = 1000
        const val MAX_FEATURES = 2000
        const val RATIO = 0.75f
        const val MIN_PAIRS = 8
        const val RANSAC_PX = 5.0
    }
}
