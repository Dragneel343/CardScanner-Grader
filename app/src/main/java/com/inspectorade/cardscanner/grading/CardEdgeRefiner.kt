package com.inspectorade.cardscanner.grading

import org.opencv.core.Point
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Refines a roughly-flattened card's corners by fitting a straight line to each
 * of its four physical edges and intersecting them. The rough corners from
 * contour detection are only approximate — rounded corners pull them inward,
 * detection runs at low resolution, and the textured-surface fallback detector
 * is coarser still — which leaves the card rotated/skewed after the first warp.
 *
 * Built to resist textured backgrounds (carbon fiber, fabric, wood grain):
 * - Polarity: whether the card is lighter or darker than its surroundings is
 *   measured once per side, and only edges stepping that way count. Texture
 *   ridges alternate light/dark, so half of them are rejected outright.
 * - Each edge sample is averaged along a stretch of the edge, so diagonal or
 *   fine texture blurs out while the card's straight edge stays sharp.
 * - A refinement is only accepted if the image shows a stronger card edge along
 *   the refined outline than along the current one, so it can never make the
 *   result worse than what it started from.
 */
object CardEdgeRefiner {

    private const val SAMPLES_PER_SIDE = 40
    private const val SEARCH_FRACTION = 0.07
    private const val MAX_CORNER_SHIFT_FRACTION = 0.10
    private const val STEP_HALF_WIDTH = 5
    private const val ALONG_HALF_SPAN = 7
    private const val POLARITY_NEAR = 10
    private const val POLARITY_FAR = 40
    private const val MIN_POLARITY_CONTRAST = 15f

    /** A line expressed as `across = slope * along + intercept`. */
    private class Line(val slope: Double, val intercept: Double)

    /** One side of the card: which axis it lies along and which way is "into the card". */
    private class Side(val vertical: Boolean, val expected: Int, val inward: Int)

    private class Image(val luma: FloatArray, val width: Int, val height: Int) {
        fun at(x: Int, y: Int): Float = luma[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
    }

    /**
     * Returns refined corners (TL, TR, BR, BL) in the flattened image's pixel
     * coordinates, or null if the edges couldn't be fit or the fit isn't a
     * clear improvement on the current outline.
     */
    fun refine(
        pixels: IntArray, width: Int, height: Int, margin: Int, cardWidth: Int, cardHeight: Int,
    ): Array<Point>? {
        val image = Image(FloatArray(pixels.size) { luminance(pixels[it]) }, width, height)

        val left = Side(vertical = true, expected = margin, inward = 1)
        val right = Side(vertical = true, expected = margin + cardWidth, inward = -1)
        val top = Side(vertical = false, expected = margin, inward = 1)
        val bottom = Side(vertical = false, expected = margin + cardHeight, inward = -1)
        val sides = listOf(left, right, top, bottom)
        val polarities = sides.map { polarity(image, it, alongFrom = margin, alongLength = if (it.vertical) cardHeight else cardWidth) }

        val lines = sides.mapIndexed { i, side ->
            fitEdge(
                image, side, polarities[i],
                window = ((if (side.vertical) cardWidth else cardHeight) * SEARCH_FRACTION).toInt(),
                alongFrom = margin, alongLength = if (side.vertical) cardHeight else cardWidth,
            ) ?: return null
        }

        val refined = arrayOf(
            intersect(lines[0], lines[2]),
            intersect(lines[1], lines[2]),
            intersect(lines[1], lines[3]),
            intersect(lines[0], lines[3]),
        )
        val current = arrayOf(
            Point(margin.toDouble(), margin.toDouble()),
            Point((margin + cardWidth).toDouble(), margin.toDouble()),
            Point((margin + cardWidth).toDouble(), (margin + cardHeight).toDouble()),
            Point(margin.toDouble(), (margin + cardHeight).toDouble()),
        )

        val maxShift = cardWidth * MAX_CORNER_SHIFT_FRACTION
        val plausible = refined.indices.all {
            abs(refined[it].x - current[it].x) <= maxShift && abs(refined[it].y - current[it].y) <= maxShift
        }
        if (!plausible) return null

        return if (outlineScore(image, refined, polarities) > outlineScore(image, current, polarities)) refined else null
    }

    /**
     * +1 if the card is brighter than its surroundings on this side, -1 if
     * darker, 0 if too close to call (then edges of either direction count).
     */
    private fun polarity(image: Image, side: Side, alongFrom: Int, alongLength: Int): Int {
        var inside = 0f
        var outside = 0f
        var count = 0
        for (i in 0 until SAMPLES_PER_SIDE) {
            val along = alongFrom + (alongLength * (0.15 + 0.7 * i / (SAMPLES_PER_SIDE - 1))).toInt()
            for (d in POLARITY_NEAR..POLARITY_FAR) {
                inside += sample(image, side.vertical, along, side.expected + side.inward * d)
                outside += sample(image, side.vertical, along, side.expected - side.inward * d)
                count++
            }
        }
        val difference = (inside - outside) / count
        return when {
            difference > MIN_POLARITY_CONTRAST -> 1
            difference < -MIN_POLARITY_CONTRAST -> -1
            else -> 0
        }
    }

    /**
     * Samples the edge position along the straight middle 70% of a side (skipping
     * rounded corners), then fits a line, dropping outliers and refitting once.
     */
    private fun fitEdge(
        image: Image, side: Side, polarity: Int, window: Int, alongFrom: Int, alongLength: Int,
    ): Line? {
        val along = DoubleArray(SAMPLES_PER_SIDE)
        val across = DoubleArray(SAMPLES_PER_SIDE)
        for (i in 0 until SAMPLES_PER_SIDE) {
            val a = alongFrom + (alongLength * (0.15 + 0.7 * i / (SAMPLES_PER_SIDE - 1))).toInt()
            along[i] = a.toDouble()
            across[i] = bestEdge(image, side, polarity, a, side.expected - window, side.expected + window).toDouble()
        }

        var keep = BooleanArray(SAMPLES_PER_SIDE) { true }
        var line = leastSquares(along, across, keep) ?: return null
        val residuals = DoubleArray(SAMPLES_PER_SIDE) { abs(across[it] - (line.slope * along[it] + line.intercept)) }
        val median = residuals.sorted()[SAMPLES_PER_SIDE / 2]
        val limit = maxOf(2.0, 2.5 * median)
        keep = BooleanArray(SAMPLES_PER_SIDE) { residuals[it] <= limit }
        if (keep.count { it } < SAMPLES_PER_SIDE / 2) return null
        line = leastSquares(along, across, keep) ?: return null
        return line
    }

    /** Across-axis position in [from, to] with the strongest card edge of the right polarity. */
    private fun bestEdge(image: Image, side: Side, polarity: Int, along: Int, from: Int, to: Int): Int {
        var best = side.expected
        var bestScore = Float.NEGATIVE_INFINITY
        for (pos in from..to) {
            val score = edgeScore(image, side.vertical, side.inward, polarity, along, pos)
            if (score > bestScore) {
                bestScore = score
                best = pos
            }
        }
        return best
    }

    /**
     * How strongly position [pos] looks like this side's card edge: mean
     * brightness just inside minus just outside, averaged along the edge,
     * oriented by [polarity] (unsigned when polarity is unknown).
     */
    private fun edgeScore(
        image: Image, vertical: Boolean, inward: Int, polarity: Int, along: Int, pos: Int,
    ): Float {
        var inside = 0f
        var outside = 0f
        for (a in along - ALONG_HALF_SPAN..along + ALONG_HALF_SPAN) {
            for (d in 0 until STEP_HALF_WIDTH) {
                inside += sample(image, vertical, a, pos + inward * d)
                outside += sample(image, vertical, a, pos - inward * (d + 1))
            }
        }
        val signed = (inside - outside) / ((2 * ALONG_HALF_SPAN + 1) * STEP_HALF_WIDTH)
        return if (polarity == 0) abs(signed) else signed * polarity
    }

    /**
     * Average edge strength along all four sides of an outline (TL, TR, BR, BL),
     * sampled over each side's straight middle section.
     */
    private fun outlineScore(image: Image, corners: Array<Point>, polarities: List<Int>): Float {
        // Side order matches polarities: left, right, top, bottom.
        val segments = listOf(
            Triple(corners[0], corners[3], 1),
            Triple(corners[1], corners[2], -1),
            Triple(corners[0], corners[1], 1),
            Triple(corners[3], corners[2], -1),
        )
        var total = 0f
        var count = 0
        segments.forEachIndexed { index, (start, end, inward) ->
            val vertical = index < 2
            for (i in 0 until SAMPLES_PER_SIDE) {
                val t = 0.15 + 0.7 * i / (SAMPLES_PER_SIDE - 1)
                val x = start.x + (end.x - start.x) * t
                val y = start.y + (end.y - start.y) * t
                val along = (if (vertical) y else x).roundToInt()
                val across = (if (vertical) x else y).roundToInt()
                total += edgeScore(image, vertical, inward, polarities[index], along, across)
                count++
            }
        }
        return total / count
    }

    private fun sample(image: Image, vertical: Boolean, along: Int, across: Int): Float =
        if (vertical) image.at(across, along) else image.at(along, across)

    private fun luminance(pixel: Int): Float =
        0.299f * ((pixel shr 16) and 0xFF) + 0.587f * ((pixel shr 8) and 0xFF) + 0.114f * (pixel and 0xFF)

    private fun leastSquares(x: DoubleArray, y: DoubleArray, keep: BooleanArray): Line? {
        var n = 0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in x.indices) {
            if (!keep[i]) continue
            n++
            sx += x[i]
            sy += y[i]
            sxx += x[i] * x[i]
            sxy += x[i] * y[i]
        }
        val denominator = n * sxx - sx * sx
        if (n < 2 || abs(denominator) < 1e-9) return null
        val slope = (n * sxy - sx * sy) / denominator
        return Line(slope, (sy - slope * sx) / n)
    }

    /** Intersects a vertical-ish edge (x = a·y + b) with a horizontal-ish one (y = c·x + d). */
    private fun intersect(vertical: Line, horizontal: Line): Point {
        val x = (vertical.slope * horizontal.intercept + vertical.intercept) / (1 - vertical.slope * horizontal.slope)
        return Point(x, horizontal.slope * x + horizontal.intercept)
    }
}
