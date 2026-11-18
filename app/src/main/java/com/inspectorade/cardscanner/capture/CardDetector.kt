package com.inspectorade.cardscanner.capture

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * Classic contour-based card detection: find outlines, keep ones that
 * approximate to a convex quadrilateral of roughly the right size and aspect
 * ratio for a trading card. Edge-based (blur + Canny) first, with a
 * brightness-based fallback for textured surfaces. A truly cluttered scene
 * would need a segmentation model instead (see image_alignment_pipeline
 * project memory).
 */
object CardDetector {

    private const val TAG = "CardDetector"

    // width / height, portrait orientation — matches CardOutlineOverlayView's guide.
    private const val CARD_ASPECT_RATIO = 2.5 / 3.5
    private const val ASPECT_TOLERANCE = 0.25
    private const val MIN_AREA_FRACTION = 0.12

    // How close (fraction of the frame's shorter side) a corner may come to the
    // frame's edge before the quad is treated as background, not a card.
    private const val FRAME_BORDER_FRACTION = 0.01

    // Outer band of the frame (fraction of its shorter side) sampled as surface.
    private const val BACKGROUND_BAND = 0.05

    // Card vs surface by color: past this many times the surface's own spread
    // (its 90th-percentile distance from its median color), and at least this far.
    private const val SURFACE_PERCENTILE = 0.9
    private const val SURFACE_SPREAD_FACTOR = 2.5
    private const val MIN_COLOR_DISTANCE = 25.0
    // A card fills ~99% of its bounding rectangle (only the rounded corners miss),
    // but on a color mask a dark border close to a dark surface drops out in
    // places: a deep blue back on black cloth measured 0.82.
    private const val MIN_RECTANGLE_FILL = 0.75

    /**
     * Looks for a quadrilateral matching a card's aspect ratio in a single-channel
     * (grayscale/luminance) Mat. Returns its four corners in the input Mat's pixel
     * coordinate space (same frame as the Mat passed in), or null if nothing
     * convincing is found. Caller owns `gray` and must release it themselves.
     *
     * [minAreaFraction] keeps small clutter from registering on the live preview;
     * a still photo being analyzed can safely allow a smaller card.
     */
    fun detect(gray: Mat, minAreaFraction: Double = MIN_AREA_FRACTION): Array<Point>? =
        detectByEdges(gray, minAreaFraction) ?: detectByBrightness(gray, minAreaFraction)

    private fun detectByEdges(gray: Mat, minAreaFraction: Double, explain: Boolean = false): Array<Point>? {
        val blurred = Mat()
        val edges = Mat()
        val dilated = Mat()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 50.0, 150.0)
            Imgproc.dilate(edges, dilated, Mat())
            return bestCardQuad(dilated, gray.rows() * gray.cols(), minAreaFraction, explain)
        } finally {
            blurred.release()
            edges.release()
            dilated.release()
        }
    }

    /**
     * Fallback for a card about as bright as the surface but a different color
     * (a dark blue card back on dark brown wood), which grayscale can't separate.
     * First the same edge search as [detect], on each color channel of CIELAB
     * (a: green–red, b: blue–yellow): uneven lighting changes brightness smoothly
     * and barely touches them, while the card's border against the surface is a
     * sharp jump in hue. Then a color-region search, see [detectByColorRegion].
     * Takes an RGBA Mat; caller releases it.
     */
    fun detectAgainstBackground(rgba: Mat, minAreaFraction: Double = MIN_AREA_FRACTION): Array<Point>? {
        val rgb = Mat()
        val lab = Mat()
        val channels = mutableListOf<Mat>()
        try {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            Core.split(lab, channels)
            for (c in listOf(2, 1)) {
                // Stretch the channel's range: hue differences are numerically
                // small next to brightness, and Canny's thresholds are absolute.
                val stretched = Mat()
                Core.normalize(channels[c], stretched, 0.0, 255.0, Core.NORM_MINMAX)
                val found = detectByEdges(stretched, minAreaFraction, explain = true)
                stretched.release()
                Log.d(TAG, "Color edges, channel ${"Lab"[c]}: ${if (found != null) "found" else "nothing"}")
                if (found != null) return found
            }
        } finally {
            rgb.release()
            lab.release()
            channels.forEach { it.release() }
        }
        return detectByColorRegion(rgba, minAreaFraction)
    }

    /**
     * Scores every pixel by how far its color is from the surface's (taken from
     * the frame's outer border, which is surface since the card is framed inside
     * the guide) and looks for the card-shaped region that stands out.
     */
    private fun detectByColorRegion(rgba: Mat, minAreaFraction: Double): Array<Point>? {
        val rgb = Mat()
        val lab = Mat()
        val labFloat = Mat()
        val distance = Mat()
        val binary = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        try {
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.medianBlur(rgb, rgb, 9)
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            lab.convertTo(labFloat, CvType.CV_32FC3)

            val rows = lab.rows()
            val cols = lab.cols()
            val band = (minOf(rows, cols) * BACKGROUND_BAND).toInt().coerceAtLeast(2)
            val samples = mutableListOf<DoubleArray>()
            for (y in 0 until rows step 4) for (x in 0 until cols step 4) {
                if (x < band || y < band || x >= cols - band || y >= rows - band) samples += labFloat.get(y, x)
            }
            val background = DoubleArray(3) { c -> samples.map { it[c] }.sorted()[samples.size / 2] }

            val channels = mutableListOf<Mat>()
            Core.split(labFloat, channels)
            val sum = Mat.zeros(rows, cols, CvType.CV_32F)
            for (c in 0 until 3) {
                val diff = Mat()
                Core.subtract(channels[c], org.opencv.core.Scalar(background[c]), diff)
                Core.multiply(diff, diff, diff)
                Core.add(sum, diff, sum)
                diff.release()
                channels[c].release()
            }
            Core.sqrt(sum, sum)

            // The cut-off comes from how much the surface itself varies (wood grain,
            // cloth texture) — not Otsu, which on a card back splits inside the
            // card (dark border vs bright swirl) and leaves the border with the
            // surface. Anything well beyond the surface's own spread is card.
            val spread = (0 until rows step 4).flatMap { y ->
                (0 until cols step 4).filter { x -> x < band || y < band || x >= cols - band || y >= rows - band }
                    .map { x -> sum.get(y, x)[0] }
            }.sorted().let { it[(it.size * SURFACE_PERCENTILE).toInt().coerceAtMost(it.size - 1)] }
            val cutoff = maxOf(MIN_COLOR_DISTANCE, spread * SURFACE_SPREAD_FACTOR)
            Log.d(TAG, "Color region: surface spread %.1f, cutoff %.1f".format(spread, cutoff))
            Imgproc.threshold(sum, distance, cutoff, 255.0, Imgproc.THRESH_BINARY)
            distance.convertTo(binary, CvType.CV_8U)
            sum.release()
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
            return bestCardQuad(binary, rows * cols, minAreaFraction, explain = true)
        } finally {
            listOf(rgb, lab, labFloat, distance, binary, kernel).forEach { it.release() }
        }
    }

    /**
     * Fallback for textured backgrounds (carbon-fiber mats, wood grain, fabric):
     * there, every weave line is an edge, Canny output becomes a mesh, and the
     * card's outline merges into it. But the card is usually much brighter or
     * darker than the surface as a whole, so a heavy median blur (which erases
     * fine texture while keeping the card's straight edges) followed by a
     * bright/dark split isolates the card as a single solid region. Tries the
     * bright region first (light borders on a dark surface), then the dark one.
     */
    private fun detectByBrightness(gray: Mat, minAreaFraction: Double): Array<Point>? {
        val blurred = Mat()
        val binary = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        try {
            Imgproc.medianBlur(gray, blurred, 9)
            Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel)
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)

            val frameArea = gray.rows() * gray.cols()
            bestCardQuad(binary, frameArea, minAreaFraction)?.let { return it }
            Core.bitwise_not(binary, binary)
            return bestCardQuad(binary, frameArea, minAreaFraction)
        } finally {
            blurred.release()
            binary.release()
            kernel.release()
        }
    }

    /**
     * Largest convex, card-proportioned quadrilateral among the outermost contours
     * of a binary image.
     *
     * RETR_EXTERNAL (outermost only), not RETR_LIST (all): otherwise a high-contrast
     * inner design element (e.g. the Pokeball logo printed on a card back) can
     * out-compete the card's own physical edge, which often has weaker contrast
     * against the background. Anything printed on the card is nested inside its
     * boundary, so once that boundary is a valid closed contour, EXTERNAL
     * structurally excludes it.
     *
     * Quads touching the frame's border are skipped: the card is framed inside
     * the guide, so an outline running along the photo's edge is a patch of
     * background (e.g. the surface bounded by the frame and a box behind it).
     */
    private fun bestCardQuad(binary: Mat, frameArea: Int, minAreaFraction: Double, explain: Boolean = false): Array<Point>? {
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        val inset = minOf(binary.cols(), binary.rows()) * FRAME_BORDER_FRACTION
        fun touchesBorder(p: Point) =
            p.x < inset || p.y < inset || p.x > binary.cols() - 1 - inset || p.y > binary.rows() - 1 - inset
        try {
            Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            var best: Array<Point>? = null
            var bestArea = 0.0
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < frameArea * minAreaFraction) continue

                val contour2f = MatOfPoint2f(*contour.toArray())
                val perimeter = Imgproc.arcLength(contour2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(contour2f, approx, 0.02 * perimeter, true)
                contour2f.release()

                var points = approx.toArray()
                approx.release()
                if (points.size != 4) {
                    rectangleFilledBy(contour, area)?.let {
                        if (explain) Log.d(TAG, "Solid rectangle used: %d corners, area %.0f%%"
                            .format(points.size, area * 100 / frameArea))
                        points = it
                    }
                }
                if (points.size != 4 || points.any(::touchesBorder)) {
                    if (explain) Log.d(TAG, "Rejected: area %.0f%%, %d corners, touches border %b"
                        .format(area * 100 / frameArea, points.size, points.any(::touchesBorder)))
                    continue
                }

                val pointsAsInt = MatOfPoint(*points)
                val convex = Imgproc.isContourConvex(pointsAsInt)
                val rect = Imgproc.boundingRect(pointsAsInt)
                pointsAsInt.release()
                if (!convex || rect.height == 0) continue

                val boxAspect = rect.width.toDouble() / rect.height.toDouble()
                val matchesPortrait = abs(boxAspect - CARD_ASPECT_RATIO) < ASPECT_TOLERANCE
                val matchesLandscape = abs((1.0 / boxAspect) - CARD_ASPECT_RATIO) < ASPECT_TOLERANCE
                if (!matchesPortrait && !matchesLandscape) {
                    if (explain) Log.d(TAG, "Rejected: area %.0f%%, aspect %.2f".format(area * 100 / frameArea, boxAspect))
                    continue
                }

                if (area > bestArea) {
                    bestArea = area
                    best = points
                }
            }
            return best
        } finally {
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /**
     * The corners of the smallest rectangle around an outline that didn't simplify
     * to four corners (a dent from a shadow or a worn corner adds a few), if the
     * outline fills most of it — as a card does, rounded corners and all.
     * A blob that only partly covers the card (half the border, a patch of
     * swirl) fills its rectangle poorly and is left rejected. Edge refinement
     * later fits the true edges, so a rectangle in place of a slightly
     * perspective-skewed quad is close enough.
     */
    private fun rectangleFilledBy(contour: MatOfPoint, area: Double): Array<Point>? {
        val contour2f = MatOfPoint2f(*contour.toArray())
        val box = Imgproc.minAreaRect(contour2f)
        contour2f.release()
        val boxArea = box.size.width * box.size.height
        if (boxArea <= 0 || area / boxArea < MIN_RECTANGLE_FILL) return null
        val corners = arrayOfNulls<Point>(4)
        box.points(corners)
        return corners.requireNoNulls()
    }
}
