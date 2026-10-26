package com.inspectorade.cardscanner.capture

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * Classic contour-based card detection: blur, Canny edge detection, find
 * contours, keep ones that approximate to a convex quadrilateral of roughly
 * the right size and aspect ratio for a trading card. Good enough for a
 * clean background (desk/mat); a messy background would need a segmentation
 * model instead (see surface_grading_approach / image_alignment_pipeline
 * project memory for why that's the planned fallback later).
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
     */
    fun detect(gray: Mat): Array<Point>? {
        val blurred = Mat()
        val edges = Mat()
        val dilated = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()

        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 50.0, 150.0)
            Imgproc.dilate(edges, dilated, Mat())
            // RETR_EXTERNAL (outermost contours only) instead of RETR_LIST (all of
            // them) — otherwise a high-contrast inner design element (e.g. the
            // Pokeball logo printed on a card back) can out-compete the card's own
            // physical edge, which often has weaker contrast against the background.
            // An inner element is always nested inside the real card boundary, so
            // once that boundary is detected as a valid closed contour, EXTERNAL
            // structurally excludes anything inside it from ever being picked.
            Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            val frameArea = gray.rows() * gray.cols()
            var best: Array<Point>? = null
            var bestArea = 0.0

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < frameArea * MIN_AREA_FRACTION) continue

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
            blurred.release()
            edges.release()
            dilated.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }
}
