package com.inspectorade.cardscanner.capture

import org.opencv.core.Core
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

    // width / height, portrait orientation — matches CardOutlineOverlayView's guide.
    private const val CARD_ASPECT_RATIO = 2.5 / 3.5
    private const val ASPECT_TOLERANCE = 0.25
    private const val MIN_AREA_FRACTION = 0.12

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

    private fun detectByEdges(gray: Mat, minAreaFraction: Double): Array<Point>? {
        val blurred = Mat()
        val edges = Mat()
        val dilated = Mat()
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 50.0, 150.0)
            Imgproc.dilate(edges, dilated, Mat())
            return bestCardQuad(dilated, gray.rows() * gray.cols(), minAreaFraction)
        } finally {
            blurred.release()
            edges.release()
            dilated.release()
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
     */
    private fun bestCardQuad(binary: Mat, frameArea: Int, minAreaFraction: Double): Array<Point>? {
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
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

                val points = approx.toArray()
                approx.release()
                if (points.size != 4) continue

                val pointsAsInt = MatOfPoint(*points)
                val convex = Imgproc.isContourConvex(pointsAsInt)
                val rect = Imgproc.boundingRect(pointsAsInt)
                pointsAsInt.release()
                if (!convex || rect.height == 0) continue

                val boxAspect = rect.width.toDouble() / rect.height.toDouble()
                val matchesPortrait = abs(boxAspect - CARD_ASPECT_RATIO) < ASPECT_TOLERANCE
                val matchesLandscape = abs((1.0 / boxAspect) - CARD_ASPECT_RATIO) < ASPECT_TOLERANCE
                if (!matchesPortrait && !matchesLandscape) continue

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
}
