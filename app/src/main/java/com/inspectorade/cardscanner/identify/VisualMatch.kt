package com.inspectorade.cardscanner.identify

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * How much a photographed card looks like an official card image — for picking
 * the user's printing among several with the same name when the collector
 * number couldn't be read. Different printings almost always have different
 * art, so a coarse color comparison separates them; reprints sharing the same
 * art score alike, and the user picks between those.
 */
object VisualMatch {

    // Both images are reduced to this size: plenty to tell artwork apart, and
    // small enough that print texture, glare and holo sparkle mostly vanish.
    private const val WIDTH = 100
    private const val HEIGHT = 140

    // The photo's central part slides over the scan by up to this much on each
    // side, so the scans' inconsistent cropping doesn't cost the match.
    private const val SLACK = 0.07

    /** Normalized color correlation, -1..1; higher means more alike. */
    fun similarity(photo: Bitmap, reference: Bitmap): Double {
        val card = prepared(photo)
        val ref = prepared(reference)
        val dx = (WIDTH * SLACK).toInt()
        val dy = (HEIGHT * SLACK).toInt()
        val center = card.submat(Rect(dx, dy, WIDTH - 2 * dx, HEIGHT - 2 * dy))
        val result = Mat()
        try {
            Imgproc.matchTemplate(ref, center, result, Imgproc.TM_CCOEFF_NORMED)
            return Core.minMaxLoc(result).maxVal
        } finally {
            listOf(card, ref, center, result).forEach { it.release() }
        }
    }

    private fun prepared(bitmap: Bitmap): Mat {
        val rgba = Mat()
        val rgb = Mat()
        val small = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        Imgproc.resize(rgb, small, Size(WIDTH.toDouble(), HEIGHT.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(small, small, Size(3.0, 3.0), 0.0)
        rgba.release()
        rgb.release()
        return small
    }
}
