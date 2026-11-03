package com.inspectorade.cardscanner.grading

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Finds the outer (physical card edge) and inner (printed border edge) lines on a
 * card already flattened by [CardFlattener], as a starting point for the user to
 * refine.
 *
 * Each side is reduced to a 1-D color profile by averaging across the middle 60%
 * of that side (skipping corners, and averaging away most holo/print texture).
 * The outer line is the strongest color step near the expected card edge; the
 * inner line is where the color first departs from the border's own color (see
 * [innerEdge]). Full-art and borderless cards have no real inner border, so
 * expect those to need manual adjustment.
 */
object BorderDetector {

    private const val STEP_HALF_WIDTH = 3
    private const val OUTER_SEARCH_FRACTION = 0.04
    private const val INNER_MIN_FRACTION = 0.015
    private const val INNER_MAX_FRACTION = 0.15
    private const val REFERENCE_NEAR_FRACTION = 0.005
    private const val REFERENCE_FAR_FRACTION = 0.012
    private const val MIN_DEPARTURE = 30f
    private const val DEPARTURE_FRACTION = 0.35f
    private const val SUSTAIN = 4
    private const val SNAP_BACK = 6

    fun detect(card: Bitmap, margin: Int): BorderLines {
        val width = card.width
        val height = card.height
        val pixels = IntArray(width * height)
        card.getPixels(pixels, 0, width, 0, 0, width, height)

        val cardWidth = width - 2 * margin
        val cardHeight = height - 2 * margin

        val columns = profile(pixels, width, height, alongX = true,
            from = margin + (cardHeight * 0.2).toInt(), to = margin + (cardHeight * 0.8).toInt())
        val rows = profile(pixels, width, height, alongX = false,
            from = margin + (cardWidth * 0.2).toInt(), to = margin + (cardWidth * 0.8).toInt())

        val lines = BorderLines()
        detectAxis(columns, margin, cardWidth, lines,
            BorderLine.OUTER_LEFT, BorderLine.INNER_LEFT, BorderLine.OUTER_RIGHT, BorderLine.INNER_RIGHT)
        detectAxis(rows, margin, cardHeight, lines,
            BorderLine.OUTER_TOP, BorderLine.INNER_TOP, BorderLine.OUTER_BOTTOM, BorderLine.INNER_BOTTOM)
        return lines
    }

    private fun detectAxis(
        profile: Array<FloatArray>,
        margin: Int,
        cardSize: Int,
        lines: BorderLines,
        outerStart: BorderLine,
        innerStart: BorderLine,
        outerEnd: BorderLine,
        innerEnd: BorderLine,
    ) {
        val strength = stepStrength(profile)
        val outerWindow = (cardSize * OUTER_SEARCH_FRACTION).toInt()

        val startOuter = strongest(strength, margin - outerWindow, margin + outerWindow)
        val endEdge = margin + cardSize
        val endOuter = strongest(strength, endEdge - outerWindow, endEdge + outerWindow)

        lines[outerStart] = startOuter.toFloat()
        lines[innerStart] = innerEdge(profile, strength, startOuter, inward = 1, cardSize).toFloat()
        lines[outerEnd] = endOuter.toFloat()
        lines[innerEnd] = innerEdge(profile, strength, endOuter, inward = -1, cardSize).toFloat()
    }

    /**
     * The printed border ends at the *first* place the color departs from the
     * border's own color — not at the strongest step, which is usually the much
     * higher-contrast art box further in. Samples the border color just inside
     * the card edge, walks inward until the color stays clearly different, then
     * snaps to the sharpest step right around that point.
     */
    private fun innerEdge(
        profile: Array<FloatArray>, strength: FloatArray, outer: Int, inward: Int, cardSize: Int,
    ): Int {
        val refNear = outer + inward * (cardSize * REFERENCE_NEAR_FRACTION).toInt()
        val refFar = outer + inward * (cardSize * REFERENCE_FAR_FRACTION).toInt()
        val reference = FloatArray(3)
        var refCount = 0
        for (i in minOf(refNear, refFar)..maxOf(refNear, refFar)) {
            if (i !in profile.indices) continue
            for (c in 0 until 3) reference[c] += profile[i][c]
            refCount++
        }
        val searchStart = outer + inward * (cardSize * INNER_MIN_FRACTION).toInt()
        val searchEnd = outer + inward * (cardSize * INNER_MAX_FRACTION).toInt()
        if (refCount == 0) return strongest(strength, minOf(searchStart, searchEnd), maxOf(searchStart, searchEnd))
        for (c in 0 until 3) reference[c] /= refCount

        val positions = generateSequence(searchStart) { it + inward }
            .takeWhile { if (inward > 0) it <= searchEnd else it >= searchEnd }
            .filter { it in profile.indices }
            .toList()
        val distances = positions.map { colorDistance(profile[it], reference) }
        val maxDistance = distances.maxOrNull() ?: 0f
        val threshold = maxOf(MIN_DEPARTURE, maxDistance * DEPARTURE_FRACTION)

        val departure = positions.indices.firstOrNull { i ->
            (i until minOf(i + SUSTAIN, positions.size)).all { distances[it] > threshold }
        }?.let { positions[it] }
            ?: return strongest(strength, minOf(searchStart, searchEnd), maxOf(searchStart, searchEnd))

        // The departure is detected a pixel or two past the true edge; snap to the
        // sharpest step just before it.
        val snapFrom = departure - inward * SNAP_BACK
        return strongest(strength, minOf(snapFrom, departure + inward), maxOf(snapFrom, departure + inward))
    }

    private fun colorDistance(a: FloatArray, b: FloatArray): Float =
        abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])

    /** Mean RGB at each position along the axis, averaged over [from, to) across it. */
    private fun profile(
        pixels: IntArray, width: Int, height: Int, alongX: Boolean, from: Int, to: Int,
    ): Array<FloatArray> {
        val length = if (alongX) width else height
        val result = Array(length) { FloatArray(3) }
        val count = (to - from).coerceAtLeast(1)
        for (i in 0 until length) {
            var r = 0L
            var g = 0L
            var b = 0L
            for (j in from until to) {
                val p = if (alongX) pixels[j * width + i] else pixels[i * width + j]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            result[i][0] = r.toFloat() / count
            result[i][1] = g.toFloat() / count
            result[i][2] = b.toFloat() / count
        }
        return result
    }

    /** Color difference between the windows just before and just after each position. */
    private fun stepStrength(profile: Array<FloatArray>): FloatArray {
        val n = profile.size
        val k = STEP_HALF_WIDTH
        val strength = FloatArray(n)
        for (i in k until n - k) {
            var total = 0f
            for (c in 0 until 3) {
                var before = 0f
                var after = 0f
                for (d in 1..k) {
                    before += profile[i - d][c]
                    after += profile[i + d - 1][c]
                }
                total += abs(after - before) / k
            }
            strength[i] = total
        }
        return strength
    }

    private fun strongest(strength: FloatArray, from: Int, to: Int): Int {
        val start = from.coerceIn(0, strength.size - 1)
        val end = to.coerceIn(start, strength.size - 1)
        var best = start
        for (i in start..end) if (strength[i] > strength[best]) best = i
        return best
    }
}
