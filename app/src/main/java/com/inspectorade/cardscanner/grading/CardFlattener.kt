package com.inspectorade.cardscanner.grading

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import com.inspectorade.cardscanner.capture.CardDetector
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns a captured photo into a straight-on, fixed-size image of the card
 * (perspective removed), with a margin of background left around it so the
 * card's physical edge is visible for the outer centering lines.
 */
object CardFlattener {

    const val CARD_WIDTH = 1000
    const val CARD_HEIGHT = 1400
    const val MARGIN = 60

    private const val DETECTION_MAX_DIM = 800.0
    private const val REFINEMENT_PASSES = 2

    // Far smaller than the live preview's threshold: a still being measured can
    // have the card well away from the camera and still be worth trying.
    private const val STILL_MIN_AREA_FRACTION = 0.02

    /**
     * [sourceCardHeight] is how tall the card was in the original photo, in
     * pixels — the real resolution behind the measurement, since the output is
     * always resampled to [CARD_HEIGHT]. Null if no card was found.
     */
    class Result(val bitmap: Bitmap, val cardFound: Boolean, val sourceCardHeight: Double?)

    /**
     * Decodes [uri] at full resolution (border widths are only a few percent of
     * the card, so every source pixel counts toward centering precision), upright
     * per EXIF. Returns an RGBA Mat the caller must release. Rotation happens in
     * OpenCV and the decoded Bitmap is freed immediately, so peak memory is two
     * full-size buffers rather than three.
     */
    fun loadUpright(resolver: ContentResolver, uri: Uri): Mat {
        val decoded = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
            ?: error("Could not decode $uri")
        val rgba = Mat()
        Utils.bitmapToMat(decoded, rgba)
        decoded.recycle()

        val orientation = resolver.openInputStream(uri)!!.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val rotateCode = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> Core.ROTATE_90_CLOCKWISE
            ExifInterface.ORIENTATION_ROTATE_180 -> Core.ROTATE_180
            ExifInterface.ORIENTATION_ROTATE_270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return rgba
        }
        val rotated = Mat()
        Core.rotate(rgba, rotated, rotateCode)
        rgba.release()
        return rotated
    }

    /**
     * If no card is found, the whole photo is stretched into the output instead —
     * centering ratios are per-axis so the stretch doesn't skew them, and the user
     * can still drag the lines into place by hand. Does not release [rgba].
     */
    fun flatten(rgba: Mat): Result {
        val rough = detectCorners(rgba)
            ?: return Result(warp(rgba, wholeImageCorners(rgba), wholeOutputCorners()), false, null)

        // Warp with the current corners, fit the card's real edges in that
        // nearly-square view, and map the refined corners back to the original
        // photo. Each pass starts from a straighter card, so the second converges
        // onto the true edges even when the rough corners were well off. Every
        // warp samples the original photo, so the result is interpolated once.
        var corners = rough
        var flattened = warp(rgba, corners, canonicalCorners())
        repeat(REFINEMENT_PASSES) {
            val pixels = IntArray(flattened.width * flattened.height)
            flattened.getPixels(pixels, 0, flattened.width, 0, 0, flattened.width, flattened.height)
            val refined = CardEdgeRefiner.refine(
                pixels, flattened.width, flattened.height, MARGIN, CARD_WIDTH, CARD_HEIGHT
            ) ?: return Result(flattened, true, cardHeight(corners))

            corners = mapPoints(refined, from = canonicalCorners(), to = corners)
            flattened.recycle()
            flattened = warp(rgba, corners, canonicalCorners())
        }
        return Result(flattened, true, cardHeight(corners))
    }

    /** Average length of the left and right edges of a TL, TR, BR, BL quad. */
    private fun cardHeight(corners: Array<Point>): Double =
        (hypot(corners[3].x - corners[0].x, corners[3].y - corners[0].y) +
            hypot(corners[2].x - corners[1].x, corners[2].y - corners[1].y)) / 2

    private fun canonicalCorners(): Array<Point> {
        val m = MARGIN.toDouble()
        return arrayOf(
            Point(m, m),
            Point(m + CARD_WIDTH, m),
            Point(m + CARD_WIDTH, m + CARD_HEIGHT),
            Point(m, m + CARD_HEIGHT),
        )
    }

    private fun wholeImageCorners(rgba: Mat): Array<Point> = arrayOf(
        Point(0.0, 0.0),
        Point(rgba.cols().toDouble(), 0.0),
        Point(rgba.cols().toDouble(), rgba.rows().toDouble()),
        Point(0.0, rgba.rows().toDouble()),
    )

    private fun wholeOutputCorners(): Array<Point> {
        val w = (CARD_WIDTH + 2 * MARGIN).toDouble()
        val h = (CARD_HEIGHT + 2 * MARGIN).toDouble()
        return arrayOf(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h))
    }

    private fun warp(rgba: Mat, src: Array<Point>, dst: Array<Point>): Bitmap {
        val outWidth = CARD_WIDTH + 2 * MARGIN
        val outHeight = CARD_HEIGHT + 2 * MARGIN
        val srcMat = MatOfPoint2f(*src)
        val dstMat = MatOfPoint2f(*dst)
        val transform = Imgproc.getPerspectiveTransform(srcMat, dstMat)
        val warped = Mat()
        try {
            Imgproc.warpPerspective(
                rgba, warped, transform, Size(outWidth.toDouble(), outHeight.toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE
            )
            val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, output)
            return output
        } finally {
            srcMat.release()
            dstMat.release()
            transform.release()
            warped.release()
        }
    }

    /** Applies the perspective transform that takes [from] onto [to] to [points]. */
    private fun mapPoints(points: Array<Point>, from: Array<Point>, to: Array<Point>): Array<Point> {
        val fromMat = MatOfPoint2f(*from)
        val toMat = MatOfPoint2f(*to)
        val transform = Imgproc.getPerspectiveTransform(fromMat, toMat)
        val input = MatOfPoint2f(*points)
        val output = MatOfPoint2f()
        try {
            Core.perspectiveTransform(input, output, transform)
            return output.toArray()
        } finally {
            fromMat.release()
            toMat.release()
            transform.release()
            input.release()
            output.release()
        }
    }

    /** Runs the same detector as the live preview, on a downscaled copy at a similar resolution. */
    private fun detectCorners(rgba: Mat): Array<Point>? {
        val scale = DETECTION_MAX_DIM / max(rgba.cols(), rgba.rows())
        val small = Mat()
        val gray = Mat()
        try {
            Imgproc.resize(rgba, small, Size(), scale, scale, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
            val found = CardDetector.detect(gray, STILL_MIN_AREA_FRACTION) ?: return null
            return orderPortrait(found.map { Point(it.x / scale, it.y / scale) })
        } finally {
            small.release()
            gray.release()
        }
    }

    /**
     * Orders corners top-left, top-right, bottom-right, bottom-left by angle around
     * the centroid (robust to moderate rotation), then rotates the order a quarter
     * turn if the card was lying sideways so the long edge runs top-to-bottom.
     */
    private fun orderPortrait(points: List<Point>): Array<Point> {
        val cx = points.sumOf { it.x } / points.size
        val cy = points.sumOf { it.y } / points.size
        val (tl, tr, br, bl) = points.sortedBy { atan2(it.y - cy, it.x - cx) }

        val topLength = hypot(tr.x - tl.x, tr.y - tl.y)
        val sideLength = hypot(bl.x - tl.x, bl.y - tl.y)
        return if (topLength > sideLength) arrayOf(bl, tl, tr, br) else arrayOf(tl, tr, br, bl)
    }
}
