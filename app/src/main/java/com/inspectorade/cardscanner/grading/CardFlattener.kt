package com.inspectorade.cardscanner.grading

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import com.inspectorade.cardscanner.capture.CardDetector
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
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

    // Corner-placement noise; a refinement pass may make the outline this much
    // less rectangular and still count as no worse.
    private const val MISMATCH_TOLERANCE_DEGREES = 0.2

    private const val TAG = "CardFlattener"

    // Far smaller than the live preview's threshold: a still being measured can
    // have the card well away from the camera and still be worth trying.
    private const val STILL_MIN_AREA_FRACTION = 0.02

    /**
     * [sourceCardHeight] is how tall the card was in the original photo, in
     * pixels — the real resolution behind the measurement, since the output is
     * always resampled to [CARD_HEIGHT]. Null if no card was found.
     */
    class Result(
        val bitmap: Bitmap,
        val cardFound: Boolean,
        val sourceCardHeight: Double?,
        /** Final card corners (TL, TR, BR, BL) in the source photo, if a card was found. */
        val cornersInPhoto: Array<Point>?,
        /** The detector's corners before edge refinement, for debugging. */
        val roughCorners: Array<Point>? = null,
        /** See [sideMismatchDegrees]; 0 if no card was found. */
        val sideMismatchDegrees: Double = 0.0,
    )

    /**
     * Just the card (no margin), flattened at [scale]× the standard size. Text
     * recognition needs this: a card's collector number is tiny print, too small
     * to read reliably at the standard [CARD_WIDTH], though the photo has the detail.
     */
    fun cardOnly(rgba: Mat, corners: Array<Point>, scale: Int): Bitmap {
        val width = CARD_WIDTH * scale
        val height = CARD_HEIGHT * scale
        val dst = arrayOf(
            Point(0.0, 0.0),
            Point(width.toDouble(), 0.0),
            Point(width.toDouble(), height.toDouble()),
            Point(0.0, height.toDouble()),
        )
        return warp(rgba, corners, dst, width, height)
    }

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
            ?: return Result(warp(rgba, wholeImageCorners(rgba), wholeOutputCorners()), false, null, null)

        // Warp with the current corners, fit the card's real edges in that
        // nearly-square view, and map the refined corners back to the original
        // photo. Each pass starts from a straighter card, so the second converges
        // onto the true edges even when the rough corners were well off. Every
        // warp samples the original photo, so the result is interpolated once.
        //
        // A pass is kept only if it leaves the outline at least as rectangular as
        // before: on a worn card, the refiner can lock onto the wrong edge on part
        // of a side (whitened edge wear, the inner border, a shadow) and tilt an
        // edge the detector had right.
        var corners = rough
        var flattened = warp(rgba, corners, canonicalCorners())
        repeat(REFINEMENT_PASSES) { pass ->
            val pixels = IntArray(flattened.width * flattened.height)
            flattened.getPixels(pixels, 0, flattened.width, 0, 0, flattened.width, flattened.height)
            val refined = CardEdgeRefiner.refine(
                pixels, flattened.width, flattened.height, MARGIN, CARD_WIDTH, CARD_HEIGHT
            ) ?: return result(flattened, corners, rough)

            val candidate = mapPoints(refined, from = canonicalCorners(), to = corners)
            Log.d(TAG, "Refinement pass ${pass + 1}: sides off parallel %.2f° -> %.2f°"
                .format(sideMismatchDegrees(corners), sideMismatchDegrees(candidate)))
            if (sideMismatchDegrees(candidate) > sideMismatchDegrees(corners) + MISMATCH_TOLERANCE_DEGREES) {
                return result(flattened, corners, rough)
            }
            corners = candidate
            flattened.recycle()
            flattened = warp(rgba, corners, canonicalCorners())
        }
        return result(flattened, corners, rough)
    }

    private fun result(flattened: Bitmap, corners: Array<Point>, rough: Array<Point>) =
        Result(flattened, true, cardHeight(corners), corners, rough, sideMismatchDegrees(corners))

    /**
     * How far from parallel opposite sides of a TL, TR, BR, BL outline are, in
     * degrees (the worse pair). A card photographed straight on — which the
     * level indicator ensures — is within a fraction of a degree; more means a
     * corner landed off the card, and the flattened card comes out skewed.
     */
    fun sideMismatchDegrees(corners: Array<Point>): Double {
        fun angle(from: Point, to: Point) = Math.toDegrees(atan2(to.y - from.y, to.x - from.x))
        val (tl, tr, br, bl) = corners
        return max(abs(angle(tl, tr) - angle(bl, br)), abs(angle(tl, bl) - angle(tr, br)))
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

    private fun warp(
        rgba: Mat,
        src: Array<Point>,
        dst: Array<Point>,
        outWidth: Int = CARD_WIDTH + 2 * MARGIN,
        outHeight: Int = CARD_HEIGHT + 2 * MARGIN,
    ): Bitmap {
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
