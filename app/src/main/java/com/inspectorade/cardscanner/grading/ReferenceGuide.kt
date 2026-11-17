package com.inspectorade.cardscanner.grading

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * Places the inner lines using the card's official image. Holo foil and glare
 * can make a printed border look the same color as the frame next to it in a
 * photo (Hitmonchan ex's cosmos foil vs its cream name bar), but on the clean
 * official scan the edge is plain. So: find the inner lines on the scan, measure
 * how the user's print sits relative to the scan's (everything printed moves
 * together, so the art box, frame and text all vote), shift the scan's lines by
 * that much, and snap each to the sharpest edge nearby in the photo — the
 * measurement itself still comes from the user's card.
 *
 * Neither the scan's own centering nor its crop matters: its lines and its print
 * are measured together, and only the print-to-print offset carries over.
 */
object ReferenceGuide {

    private const val TAG = "ReferenceGuide"

    // Alignment runs at half resolution; plenty for a starting point the snap refines.
    private const val WORK_SCALE = 0.5

    // Leave out the band at the physical edge — that's the cut, not the print.
    private const val EDGE_EXCLUDE = 0.03

    // Below this phase-correlation peak, the two images didn't line up convincingly.
    private const val MIN_RESPONSE = 0.03

    // No real card is off by more than this; a bigger shift means a bad match.
    private const val MAX_SHIFT_FRACTION = 0.05

    private const val SNAP_WINDOW = 0.012

    // Each side is aligned on the strip of card this deep along it.
    private const val STRIP_FRACTION = 0.3

    /**
     * Inner lines guided by [reference], keeping [detected]'s outer lines; null if
     * the images can't be aligned with confidence (the caller keeps [detected]).
     */
    fun guide(card: Bitmap, margin: Int, reference: Bitmap, layout: CardLayout, detected: BorderLines): BorderLines? {
        val cardWidth = card.width - 2 * margin
        val cardHeight = card.height - 2 * margin
        val scaled = Bitmap.createScaledBitmap(reference, cardWidth, cardHeight, true)
        val cardMat = Mat()
        val refMat = Mat()
        try {
            Utils.bitmapToMat(card, cardMat)
            Utils.bitmapToMat(scaled, refMat)
            val photoEdges = cardMat.submat(Rect(margin, margin, cardWidth, cardHeight)).let { edgeMap(it).also { _ -> it.release() } }
            val refEdges = edgeMap(refMat)
            val refLines = BorderDetector.detectInner(scaled, layout)

            // Scans are cropped inside or outside the card's cut by varying amounts,
            // so the scan's print can be a few percent larger or smaller than the
            // card's. One shift can't fit all four sides then; align each side on
            // the strip of card along it instead.
            val predicted = detected.copy()
            var anyAligned = false
            for (line in BorderLine.entries.filter { !it.outer }) {
                val shift = sideShift(photoEdges, refEdges, line)
                if (shift == null) {
                    Log.d(TAG, "$line: not aligned, keeping detected")
                    continue
                }
                predicted[line] = refLines[line] + margin + shift.toFloat()
                anyAligned = true
                Log.d(TAG, "$line: scan %.0f + shift %.1f -> %.0f".format(refLines[line], shift, predicted[line]))
            }
            photoEdges.release()
            refEdges.release()
            if (!anyAligned) return null
            return BorderDetector.snapInner(card, margin, predicted, SNAP_WINDOW)
        } finally {
            cardMat.release()
            refMat.release()
            if (scaled !== reference) scaled.recycle()
        }
    }

    // The turned-around comparison must beat the as-is one by this much
    // (normalized correlation) before the card counts as upside down.
    private const val ORIENTATION_MARGIN = 0.05

    // Blur applied to the edge maps before comparing orientation, so a few
    // pixels of misalignment or the scan's looser crop don't matter.
    private const val ORIENTATION_BLUR = 15.0

    /**
     * Whether the flattened [card] is upside down compared with the official
     * image of the card the user selected: its printed design correlates better
     * with the scan turned 180°. Null if neither way matches convincingly.
     */
    fun isUpsideDown(card: Bitmap, margin: Int, reference: Bitmap): Boolean? {
        val cardWidth = card.width - 2 * margin
        val cardHeight = card.height - 2 * margin
        val scaled = Bitmap.createScaledBitmap(reference, cardWidth, cardHeight, true)
        val cardMat = Mat()
        val refMat = Mat()
        try {
            Utils.bitmapToMat(card, cardMat)
            Utils.bitmapToMat(scaled, refMat)
            val photoEdges = cardMat.submat(Rect(margin, margin, cardWidth, cardHeight)).let { edgeMap(it).also { _ -> it.release() } }
            val refEdges = edgeMap(refMat)
            val turnedRef = Mat()
            Core.flip(refEdges, turnedRef, -1)
            listOf(photoEdges, refEdges, turnedRef).forEach {
                Imgproc.GaussianBlur(it, it, Size(0.0, 0.0), ORIENTATION_BLUR * WORK_SCALE)
            }
            val asIs = correlation(photoEdges, refEdges)
            val turned = correlation(photoEdges, turnedRef)
            listOf(photoEdges, refEdges, turnedRef).forEach { it.release() }
            Log.d(TAG, "Orientation vs scan: as-is %.3f, turned %.3f".format(asIs, turned))
            return when {
                turned > asIs + ORIENTATION_MARGIN -> true
                asIs > turned + ORIENTATION_MARGIN -> false
                else -> null
            }
        } finally {
            cardMat.release()
            refMat.release()
            if (scaled !== reference) scaled.recycle()
        }
    }

    /** Normalized correlation of two same-size single-channel float images. */
    private fun correlation(a: Mat, b: Mat): Double {
        val result = Mat()
        try {
            Imgproc.matchTemplate(a, b, result, Imgproc.TM_CCOEFF_NORMED)
            return result.get(0, 0)[0]
        } finally {
            result.release()
        }
    }

    /**
     * How far the user's print sits from the scan's, across [line]'s side (x for
     * left/right, y for top/bottom), in card pixels; null if the strip along that
     * side doesn't align convincingly.
     */
    private fun sideShift(photoEdges: Mat, refEdges: Mat, line: BorderLine): Double? {
        val w = photoEdges.cols()
        val h = photoEdges.rows()
        val stripW = (w * STRIP_FRACTION).toInt()
        val stripH = (h * STRIP_FRACTION).toInt()
        val strip = when (line) {
            BorderLine.INNER_LEFT -> Rect(0, 0, stripW, h)
            BorderLine.INNER_RIGHT -> Rect(w - stripW, 0, stripW, h)
            BorderLine.INNER_TOP -> Rect(0, 0, w, stripH)
            else -> Rect(0, h - stripH, w, stripH)
        }
        val photo = photoEdges.submat(strip)
        val ref = refEdges.submat(strip)
        val window = Mat()
        try {
            Imgproc.createHanningWindow(window, photo.size(), CvType.CV_32F)
            val response = DoubleArray(1)
            // phaseCorrelate(ref, photo) reports where the photo's content sits
            // relative to the scan's (sign confirmed on a real card).
            val shift = Imgproc.phaseCorrelate(ref, photo, window, response)
            val across = (if (line.vertical) shift.x else shift.y) / WORK_SCALE
            val size = (if (line.vertical) w else h) / WORK_SCALE
            Log.d(TAG, "$line strip: shift %.1f response %.3f".format(across, response[0]))
            return if (response[0] < MIN_RESPONSE || abs(across) > size * MAX_SHIFT_FRACTION) null else across
        } finally {
            photo.release()
            ref.release()
            window.release()
        }
    }

    /**
     * Gradient magnitude of the card's interior at [WORK_SCALE]: edges of the
     * printed design, which line up between a photo and a scan even when their
     * colors and lighting don't.
     */
    private fun edgeMap(rgba: Mat): Mat {
        val small = Mat()
        val gray = Mat()
        val gx = Mat()
        val gy = Mat()
        val magnitude = Mat()
        try {
            Imgproc.resize(rgba, small, Size(), WORK_SCALE, WORK_SCALE, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)
            Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0)
            Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1)
            Core.magnitude(gx, gy, magnitude)
            val insetX = (magnitude.cols() * EDGE_EXCLUDE).toInt()
            val insetY = (magnitude.rows() * EDGE_EXCLUDE).toInt()
            val inside = Rect(insetX, insetY, magnitude.cols() - 2 * insetX, magnitude.rows() - 2 * insetY)
            // Zero the edge band rather than cropping, so both maps keep the same geometry.
            val masked = Mat.zeros(magnitude.size(), CvType.CV_32F)
            magnitude.submat(inside).copyTo(masked.submat(inside))
            return masked
        } finally {
            listOf(small, gray, gx, gy, magnitude).forEach { it.release() }
        }
    }
}
