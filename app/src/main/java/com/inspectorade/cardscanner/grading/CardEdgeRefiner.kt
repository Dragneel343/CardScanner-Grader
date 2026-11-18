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
    // As far as the flattening margin allows: a rough outline can land on a dark
    // card back's inner frame, a whole border width (~5–7%) inside the cut.
    private const val SEARCH_FRACTION = 0.10
    private const val MAX_CORNER_SHIFT_FRACTION = 0.15
    // Samples this close (px) to a candidate line count as on it.
    private const val INLIER_DISTANCE = 2.0
    private const val STEP_HALF_WIDTH = 5
    private const val ALONG_HALF_SPAN = 7
    private const val POLARITY_NEAR = 10
    private const val POLARITY_FAR = 40
    private const val MIN_POLARITY_CONTRAST = 15f
    // An edge at least this share of the strongest in the window counts as the cut.
    private const val OUTERMOST_EDGE_FRACTION = 0.3f

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
        fun window(side: Side) = ((if (side.vertical) cardWidth else cardHeight) * SEARCH_FRACTION).toInt()
        val polarities = sides.map {
            polarity(image, it, window(it), alongFrom = margin, alongLength = if (it.vertical) cardHeight else cardWidth)
        }

        val lines = sides.mapIndexed { i, side ->
            fitEdge(
                image, side, polarities[i],
                window = window(side),
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
     * The surroundings are sampled at the far end of the search [window], not
     * just outside the current line: that line can lie inside the card (on a
     * card back's inner frame), and then just outside it is still card.
     */
    private fun polarity(image: Image, side: Side, window: Int, alongFrom: Int, alongLength: Int): Int {
        var inside = 0f
        var outside = 0f
        var count = 0
        val span = POLARITY_FAR - POLARITY_NEAR
        for (i in 0 until SAMPLES_PER_SIDE) {
            val along = alongFrom + (alongLength * (0.15 + 0.7 * i / (SAMPLES_PER_SIDE - 1))).toInt()
            for (d in POLARITY_NEAR..POLARITY_FAR) {
                inside += sample(image, side.vertical, along, side.expected + side.inward * d)
                outside += sample(image, side.vertical, along, side.expected - side.inward * (window - span + d - POLARITY_NEAR))
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

        // The line most samples agree on, from every pair of samples: some samples
        // can land on a printed edge instead of the cut (where the cut is faint, as
        // along a dark border in shadow), and a fit over all of them is pulled off
        // by those before they can be told apart.
        var bestKeep: BooleanArray? = null
        var bestCount = 0
        for (i in 0 until SAMPLES_PER_SIDE) for (j in i + 1 until SAMPLES_PER_SIDE) {
            val slope = (across[j] - across[i]) / (along[j] - along[i])
            val intercept = across[i] - slope * along[i]
            val keep = BooleanArray(SAMPLES_PER_SIDE) { abs(across[it] - (slope * along[it] + intercept)) <= INLIER_DISTANCE }
            val count = keep.count { it }
            if (count > bestCount) {
                bestCount = count
                bestKeep = keep
            }
        }
        if (bestCount < SAMPLES_PER_SIDE / 2) return null
        return leastSquares(along, across, bestKeep!!)
    }

    /**
     * Across-axis position in [from, to] of the card's edge: the outermost strong
     * edge of the right polarity, not simply the strongest. Walking in from the
     * surface, the cut is the first place it turns into card; anything printed
     * lies further in, and can be the stronger step — a card back's dark blue
     * border against black cloth is a weaker edge than the bright swirl just
     * inside that border.
     */
    private fun bestEdge(image: Image, side: Side, polarity: Int, along: Int, from: Int, to: Int): Int {
        val scores = FloatArray(to - from + 1) { edgeScore(image, side.vertical, side.inward, polarity, along, from + it) }
        val strongest = scores.maxOrNull() ?: return side.expected
        if (strongest <= 0f) return side.expected
        val threshold = strongest * OUTERMOST_EDGE_FRACTION
        // Outside to inside: increasing positions for left/top, decreasing for right/bottom.
        val order = if (side.inward > 0) scores.indices else scores.indices.reversed()
        var i = order.firstOrNull { scores[it] >= threshold } ?: return side.expected
        // Climb to the peak of that edge.
        while (true) {
            val next = i + side.inward
            if (next !in scores.indices || scores[next] < scores[i]) break
            i = next
        }
        return from + i
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

    /**
     * The pixel's strongest channel (HSV value) rather than perceived brightness:
     * a deep blue card back is barely brighter than black cloth to the eye
     * (blue counts little toward luma), but its blue channel stands far above the
     * cloth's — while the step from that border to the pale swirl inside shrinks.
     */
    private fun luminance(pixel: Int): Float =
        maxOf((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF).toFloat()

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
