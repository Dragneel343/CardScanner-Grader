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
 *
 * The [CardLayout] says which borders carry a dot-code strip (e-Card left and
 * bottom, EX dot-code bottom). On those sides the strip — and the line of small
 * print beside it — interrupt the border color and then it resumes, so there
 * the inner line is the first departure the border color *doesn't* come back
 * from: the card frame.
 */
object BorderDetector {

    private const val STEP_HALF_WIDTH = 3
    // Flattening puts the card's edge within ~1% of the margin, so the outer line
    // is searched only this close to it. Wider and a stronger printed edge just
    // inside can win — a card back's swirl-to-border change, ~3% in, beats its
    // dark border against a dark surface.
    private const val OUTER_SEARCH_FRACTION = 0.02
    private const val INNER_MIN_FRACTION = 0.015
    private const val INNER_MAX_FRACTION = 0.15
    // The border's own color is the median over this band just inside the edge —
    // a median, and a band this wide, so worn edges (a whitened rim ~1% wide on a
    // played card) or the start of a dot-code strip can't skew it.
    private const val REFERENCE_NEAR_FRACTION = 0.005
    private const val REFERENCE_FAR_FRACTION = 0.02

    // Official scans are often cropped inside the card's cut, leaving as little as
    // ~1% of border (Hitmonchan ex's top), so on a scan the border color is sampled
    // right at the image edge and the search starts almost there.
    private const val SCAN_REFERENCE_NEAR_FRACTION = 0.001
    private const val SCAN_REFERENCE_FAR_FRACTION = 0.004
    private const val SCAN_INNER_MIN_FRACTION = 0.005
    private const val MIN_DEPARTURE = 30f
    private const val DEPARTURE_FRACTION = 0.35f
    private const val SUSTAIN = 4
    private const val SNAP_BACK = 6
    private const val RESUME_SUSTAIN = 4

    // The printed frame on a card back, as a share of the card's width and height,
    // measured on a near-mint WOTC-era back (Espeon, Neo Discovery) across five
    // photos that agreed to the pixel. Allowed to vary this much (of the card's
    // size) for print and measurement slack.
    private const val BACK_FRAME_WIDTH = 0.898
    private const val BACK_FRAME_HEIGHT = 0.918
    private const val BACK_FRAME_TOLERANCE = 0.005

    fun detect(card: Bitmap, margin: Int, layout: CardLayout = CardLayout.STANDARD): BorderLines =
        detect(card, margin, layout, outerKnown = false)

    /**
     * Lines for a card's back. Every Pokémon card back is printed with the same
     * frame, so the frame's size is known and only its position (the centering)
     * varies: each axis's two inner lines are found together, as the pair of
     * color steps exactly the frame's size apart with the most contrast between
     * them. One worn or faded border can't pull its line off on its own then —
     * the first-departure search of [detect] stops early where a border's color
     * drifts (darkening toward the frame, fading toward a worn edge), and a
     * back's border and swirl are too alike in color to set a firm threshold.
     */
    fun detectBack(card: Bitmap, margin: Int): BorderLines {
        val lines = detect(card, margin)
        val profiles = Profiles(card, margin)
        fun pair(strength: FloatArray, outerStart: Float, outerEnd: Float, frameFraction: Double): Pair<Int, Int> {
            val cardSize = outerEnd - outerStart
            val nominal = cardSize * frameFraction
            val tolerance = (cardSize * BACK_FRAME_TOLERANCE).toInt()
            val from = (outerStart + cardSize * INNER_MIN_FRACTION).toInt()
            val to = (outerStart + cardSize * INNER_MAX_FRACTION).toInt()
            var best = from to (from + nominal).toInt()
            var bestScore = Float.NEGATIVE_INFINITY
            for (start in from..to) for (d in -tolerance..tolerance) {
                val end = (start + nominal).toInt() + d
                if (end >= outerEnd - cardSize * INNER_MIN_FRACTION || end !in strength.indices) continue
                val score = strength[start] + strength[end]
                if (score > bestScore) {
                    bestScore = score
                    best = start to end
                }
            }
            return best
        }
        val (left, right) = pair(stepStrength(profiles.columns), lines[BorderLine.OUTER_LEFT], lines[BorderLine.OUTER_RIGHT], BACK_FRAME_WIDTH)
        val (top, bottom) = pair(stepStrength(profiles.rows), lines[BorderLine.OUTER_TOP], lines[BorderLine.OUTER_BOTTOM], BACK_FRAME_HEIGHT)
        lines[BorderLine.INNER_LEFT] = left.toFloat()
        lines[BorderLine.INNER_RIGHT] = right.toFloat()
        lines[BorderLine.INNER_TOP] = top.toFloat()
        lines[BorderLine.INNER_BOTTOM] = bottom.toFloat()
        return lines
    }

    /**
     * Inner lines of an image that is exactly the card, edge to edge — an
     * official scan — so the outer lines are simply the image edges.
     */
    fun detectInner(card: Bitmap, layout: CardLayout): BorderLines =
        detect(card, margin = 0, layout, outerKnown = true)

    /**
     * Moves each inner line of [predicted] to the sharpest step within
     * [windowFraction] of the card's size of where it is; outer lines are kept.
     */
    fun snapInner(card: Bitmap, margin: Int, predicted: BorderLines, windowFraction: Double): BorderLines {
        val profiles = Profiles(card, margin)
        val columns = stepStrength(profiles.columns)
        val rows = stepStrength(profiles.rows)
        val lines = predicted.copy()
        for (line in BorderLine.entries.filter { !it.outer }) {
            val strength = if (line.vertical) columns else rows
            val cardSize = if (line.vertical) profiles.cardWidth else profiles.cardHeight
            val window = (cardSize * windowFraction).toInt()
            val at = predicted[line].toInt()
            // Never onto the card's own edge, always a strong step: stay clear of the outer line.
            val gap = (cardSize * INNER_MIN_FRACTION).toInt()
            val outer = predicted[outerOf(line)].toInt()
            val (from, to) = if (at > outer) maxOf(at - window, outer + gap) to at + window
            else at - window to minOf(at + window, outer - gap)
            lines[line] = strongest(strength, from, to).toFloat()
        }
        return lines
    }

    private fun outerOf(inner: BorderLine) = when (inner) {
        BorderLine.INNER_LEFT -> BorderLine.OUTER_LEFT
        BorderLine.INNER_RIGHT -> BorderLine.OUTER_RIGHT
        BorderLine.INNER_TOP -> BorderLine.OUTER_TOP
        else -> BorderLine.OUTER_BOTTOM
    }

    private fun detect(card: Bitmap, margin: Int, layout: CardLayout, outerKnown: Boolean): BorderLines {
        val profiles = Profiles(card, margin)
        val stripLeft = layout == CardLayout.E_READER
        val stripBottom = layout == CardLayout.E_READER || layout == CardLayout.EX_DOT_CODE

        val lines = BorderLines()
        detectAxis(profiles.columns, margin, profiles.cardWidth, lines,
            BorderLine.OUTER_LEFT, BorderLine.INNER_LEFT, BorderLine.OUTER_RIGHT, BorderLine.INNER_RIGHT,
            stripAtStart = stripLeft, stripAtEnd = false, outerKnown)
        detectAxis(profiles.rows, margin, profiles.cardHeight, lines,
            BorderLine.OUTER_TOP, BorderLine.INNER_TOP, BorderLine.OUTER_BOTTOM, BorderLine.INNER_BOTTOM,
            stripAtStart = false, stripAtEnd = stripBottom, outerKnown)
        return lines
    }

    /** Color profiles across the card, each averaged over the middle 60% of the other axis. */
    private class Profiles(card: Bitmap, margin: Int) {
        val cardWidth = card.width - 2 * margin
        val cardHeight = card.height - 2 * margin
        val columns: Array<FloatArray>
        val rows: Array<FloatArray>

        init {
            val pixels = IntArray(card.width * card.height)
            card.getPixels(pixels, 0, card.width, 0, 0, card.width, card.height)
            columns = BorderDetector.profile(pixels, card.width, card.height, alongX = true,
                from = margin + (cardHeight * 0.2).toInt(), to = margin + (cardHeight * 0.8).toInt())
            rows = BorderDetector.profile(pixels, card.width, card.height, alongX = false,
                from = margin + (cardWidth * 0.2).toInt(), to = margin + (cardWidth * 0.8).toInt())
        }
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
        stripAtStart: Boolean,
        stripAtEnd: Boolean,
        outerKnown: Boolean,
    ) {
        val strength = stepStrength(profile)
        val outerWindow = (cardSize * OUTER_SEARCH_FRACTION).toInt()

        val endEdge = margin + cardSize
        val startOuter = if (outerKnown) margin else strongest(strength, margin - outerWindow, margin + outerWindow)
        val endOuter = if (outerKnown) endEdge - 1 else strongest(strength, endEdge - outerWindow, endEdge + outerWindow)

        lines[outerStart] = startOuter.toFloat()
        lines[innerStart] = innerEdge(profile, strength, startOuter, inward = 1, cardSize, stripAtStart, outerKnown).toFloat()
        lines[outerEnd] = endOuter.toFloat()
        lines[innerEnd] = innerEdge(profile, strength, endOuter, inward = -1, cardSize, stripAtEnd, outerKnown).toFloat()
    }

    /**
     * The printed border ends at the *first* place the color departs from the
     * border's own color — not at the strongest step, which is usually the much
     * higher-contrast art box further in. Samples the border color just inside
     * the card edge, walks inward until the color stays clearly different, then
     * snaps to the sharpest step right around that point. With [skipInterruptions]
     * (a side with a dot-code strip), departures the border color later resumes
     * from are passed over. [scan] is for an official scan (see the SCAN_ constants).
     */
    private fun innerEdge(
        profile: Array<FloatArray>, strength: FloatArray, outer: Int, inward: Int, cardSize: Int,
        skipInterruptions: Boolean, scan: Boolean,
    ): Int {
        val near = if (scan) SCAN_REFERENCE_NEAR_FRACTION else REFERENCE_NEAR_FRACTION
        val far = if (scan) SCAN_REFERENCE_FAR_FRACTION else REFERENCE_FAR_FRACTION
        val min = if (scan) SCAN_INNER_MIN_FRACTION else INNER_MIN_FRACTION
        val refNear = outer + inward * maxOf(1, (cardSize * near).toInt())
        val refFar = outer + inward * maxOf(2, (cardSize * far).toInt())
        val samples = (minOf(refNear, refFar)..maxOf(refNear, refFar)).filter { it in profile.indices }
        val searchStart = outer + inward * (cardSize * min).toInt()
        val searchEnd = outer + inward * (cardSize * INNER_MAX_FRACTION).toInt()
        if (samples.isEmpty()) return strongest(strength, minOf(searchStart, searchEnd), maxOf(searchStart, searchEnd))
        val reference = FloatArray(3) { c -> samples.map { profile[it][c] }.sorted()[samples.size / 2] }

        val positions = generateSequence(searchStart) { it + inward }
            .takeWhile { if (inward > 0) it <= searchEnd else it >= searchEnd }
            .filter { it in profile.indices }
            .toList()
        val distances = positions.map { colorDistance(profile[it], reference) }
        val maxDistance = distances.maxOrNull() ?: 0f
        val threshold = maxOf(MIN_DEPARTURE, maxDistance * DEPARTURE_FRACTION)

        fun departsAt(i: Int) = (i until minOf(i + SUSTAIN, positions.size)).all { distances[it] > threshold }
        fun resumesAt(i: Int) = i + RESUME_SUSTAIN <= positions.size &&
            (i until i + RESUME_SUSTAIN).all { distances[it] <= threshold }

        var departureIndex: Int? = null
        var i = 0
        while (i < positions.size) {
            if (!departsAt(i)) {
                i++
                continue
            }
            val resume = if (skipInterruptions) (i + 1 until positions.size).firstOrNull(::resumesAt) else null
            if (resume == null) {
                departureIndex = i
                break
            }
            i = resume
        }
        val departure = departureIndex?.let { positions[it] }
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
