package com.inspectorade.cardscanner.grading

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Looks for wear along a flattened card's edges and at its four corners, using
 * the card-edge (outer) lines confirmed on the centering screen as a guide.
 *
 * Everything is measured from where the card's cut actually is, found afresh
 * at every point: photos blur the cut over several pixels, so a fixed offset
 * from the line would land half on the background. From each point:
 * - Whitening: paper showing through just inside the cut — much less colorful
 *   than the border's own color and not dark like the background. Worn edges
 *   on played cards are often grey or beige rather than bright white, so
 *   "lighter than the border" alone would miss them. A spot counts only if it
 *   runs along the edge for a few pixels, so print dots and sparkles don't.
 * - Dents and chips: the cut sitting further in than along the rest of that
 *   side — material missing.
 * - Corner rounding: how far in from a factory corner (3 mm radius) the
 *   corner's boundary sits, in millimeters.
 *
 * Works best on a dark background. Glare can look like whitening, and borders
 * that are themselves white or pale silver can't show it — those are reported
 * as not assessable rather than guessed at.
 */
object CornerEdgeAnalyzer {

    enum class Side(val label: String) { TOP("Top"), RIGHT("Right"), BOTTOM("Bottom"), LEFT("Left") }

    enum class Corner(val label: String) {
        TOP_LEFT("Top left"), TOP_RIGHT("Top right"), BOTTOM_RIGHT("Bottom right"), BOTTOM_LEFT("Bottom left")
    }

    class EdgeResult(
        val side: Side,
        val whitenedFraction: Double,
        val chips: Int,
        val dentedFraction: Double,
        val assessable: Boolean,
    ) {
        /** One wear figure: dents count double, being worse than surface whitening. */
        val wear: Double get() = maxOf(whitenedFraction, dentedFraction * 2)
    }

    class CornerResult(
        val corner: Corner,
        /** Furthest the corner's boundary sits inside a factory corner, in mm. */
        val roundingMm: Double,
        val whitenedFraction: Double,
        val assessable: Boolean,
    )

    class Result(
        val edges: List<EdgeResult>,
        val corners: List<CornerResult>,
        /** Pixel indices (y * width + x) found whitened, for highlighting. */
        val whitenedPixels: IntArray,
        /** Pixel indices where material is missing (dents, worn-away corners). */
        val missingPixels: IntArray,
        /** Factory corner radius in pixels — sizes the corner close-ups. */
        val cornerRadius: Int,
    ) {
        val edgeGrade: Int? get() = edges.filter { it.assessable }.maxOfOrNull { it.wear }?.let(::edgeGradeFor)
        val cornerGrade: Int? get() = corners.filter { it.assessable }.minOfOrNull { cornerGradeFor(it) }
    }

    // Pokémon cards: 63 × 88 mm, corners cut to about a 3 mm radius.
    private const val CARD_WIDTH_MM = 63.0
    private const val CORNER_RADIUS_MM = 3.0

    // Depths in pixels on the standard 1000-px-wide flattened card.
    private const val SEARCH_OUTSIDE = 10   // look this far outside the line for the cut…
    private const val SEARCH_INSIDE = 30    // …and this far in
    private const val PAPER_DEPTH = 6       // whitening is looked for this deep past the cut
    private const val DENT = 4              // cut this much further in than usual: a dent
    private const val REFERENCE_NEAR = 12   // band the border's own color comes from
    private const val REFERENCE_FAR = 20
    private const val MIN_RUN = 3           // shortest whitened run that counts
    private const val DENT_WINDOW = 40      // a dent is judged against the cut this far either side
    private const val REFERENCE_WINDOW = 40 // border color is taken from this far either side

    // Color tests, in CIELAB.
    private const val COLORFUL = 15.0       // border chroma above this: a colored border
    private const val PAPER_CHROMA = 0.5    // paper: at most this share of the border's chroma…
    private const val ABOVE_BACKGROUND = 20.0 // …and this much lighter than the background
    private const val NEUTRAL = 15.0
    private const val LIGHTER_NEUTRAL = 25.0 // on gray/black borders: this much lighter, and gray
    private const val PALE_BORDER_L = 70.0  // neutral borders lighter than this can't be judged
    private const val CARD_START = 0.5      // the cut: first pixel at least this far from background toward border

    fun analyze(card: Bitmap, lines: BorderLines): Result {
        val width = card.width
        val height = card.height
        val pixels = IntArray(width * height)
        card.getPixels(pixels, 0, width, 0, 0, width, height)
        fun labAt(x: Int, y: Int): DoubleArray =
            ColorLab.lab(pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)])

        val left = lines[BorderLine.OUTER_LEFT].roundToInt()
        val right = lines[BorderLine.OUTER_RIGHT].roundToInt()
        val top = lines[BorderLine.OUTER_TOP].roundToInt()
        val bottom = lines[BorderLine.OUTER_BOTTOM].roundToInt()
        val cardWidth = (right - left).coerceAtLeast(1)
        val scale = cardWidth / 1000.0
        fun px(standard: Int) = maxOf(1, (standard * scale).roundToInt())
        val mmPerPixel = CARD_WIDTH_MM / cardWidth
        val radius = (CORNER_RADIUS_MM / mmPerPixel).roundToInt()

        val whitened = mutableListOf<Int>()
        val missing = mutableListOf<Int>()

        // Pixel at [along] on [side]'s line, [inward] pixels in (negative: outside).
        fun point(side: Side, along: Int, inward: Int): Pair<Int, Int> = when (side) {
            Side.TOP -> along to top + inward
            Side.BOTTOM -> along to bottom - inward
            Side.LEFT -> left + inward to along
            Side.RIGHT -> right - inward to along
        }
        fun span(side: Side) = if (side == Side.TOP || side == Side.BOTTOM) (left + radius)..(right - radius)
        else (top + radius)..(bottom - radius)

        val references = Side.entries.associateWith { side ->
            ColorLab.median(span(side).step(4).flatMap { along ->
                (px(REFERENCE_NEAR)..px(REFERENCE_FAR) step 2).map { d -> point(side, along, d).let { (x, y) -> labAt(x, y) } }
            })
        }
        // The border's own color around each point along a side: a rolling median,
        // so light that falls off toward the corners (a shaded border reads duller
        // and paler) isn't mistaken for wear. Indexed like span(side).
        val localReferences = Side.entries.associateWith { side ->
            val positions = span(side).toList()
            val samples = positions.map { along ->
                ColorLab.median((px(REFERENCE_NEAR)..px(REFERENCE_FAR) step 2).map { d ->
                    point(side, along, d).let { (x, y) -> labAt(x, y) }
                })
            }
            val window = px(REFERENCE_WINDOW)
            val coarse = (positions.indices step 4).associateWith { i ->
                ColorLab.median(samples.subList(maxOf(0, i - window), minOf(samples.size, i + window + 1)))
            }
            positions.indices.map { i -> coarse.getValue(i / 4 * 4) }
        }
        val backgrounds = Side.entries.associateWith { side ->
            ColorLab.median(span(side).step(8).flatMap { along ->
                (px(15)..px(35) step 5).map { d -> point(side, along, -d).let { (x, y) -> labAt(x, y) } }
            })
        }

        // Where the card starts along a line of pixels running inward: the first
        // one clearly closer to the border's color than the background's.
        fun cutAlong(sample: (Int) -> DoubleArray, reference: DoubleArray, background: DoubleArray): Int? {
            val gap = ColorLab.distance(reference, background)
            return (-px(SEARCH_OUTSIDE)..px(SEARCH_INSIDE)).firstOrNull { d ->
                ColorLab.distance(sample(d), background) >= gap * CARD_START
            }
        }

        val edges = Side.entries.map { side ->
            val reference = references.getValue(side)
            val background = backgrounds.getValue(side)
            if (!assessable(reference)) return@map EdgeResult(side, 0.0, 0, 0.0, false)
            val positions = span(side).toList()
            val local = localReferences.getValue(side)
            val cuts = positions.mapIndexed { i, along ->
                cutAlong({ d -> point(side, along, d).let { (x, y) -> labAt(x, y) } }, local[i], background)
            }
            // Where the cut normally is around each point: a rolling median, so a
            // card that sits slightly bowed or tilted doesn't read as dented — real
            // dents and chips are local, a few millimeters long.
            val window = px(DENT_WINDOW)
            val usual = positions.indices.map { i ->
                cuts.subList(maxOf(0, i - window), minOf(cuts.size, i + window + 1)).filterNotNull().sorted()
                    .let { if (it.isEmpty()) 0 else it[it.size / 2] }
            }

            val paper = positions.indices.map { i ->
                val cut = cuts[i] ?: return@map false
                (cut until cut + px(PAPER_DEPTH)).count { d ->
                    point(side, positions[i], d).let { (x, y) -> isPaper(labAt(x, y), local[i], background) }
                } >= 2
            }
            val runs = runsOf(paper).filter { it.last - it.first + 1 >= px(MIN_RUN) }
            for (run in runs) for (i in run) {
                val cut = cuts[i] ?: continue
                for (d in cut until cut + px(PAPER_DEPTH)) point(side, positions[i], d).let { (x, y) ->
                    if (isPaper(labAt(x, y), local[i], background)) whitened += y * width + x
                }
            }
            val dented = positions.indices.filter { i -> (cuts[i] ?: Int.MAX_VALUE) > usual[i] + px(DENT) }
            for (i in dented) for (d in usual[i]..minOf(cuts[i] ?: usual[i], usual[i] + px(SEARCH_INSIDE))) {
                point(side, positions[i], d).let { (x, y) -> missing += y * width + x }
            }
            EdgeResult(
                side,
                whitenedFraction = runs.sumOf { it.last - it.first + 1 }.toDouble() / positions.size,
                chips = runs.size,
                dentedFraction = dented.size.toDouble() / positions.size,
                assessable = true,
            )
        }

        val corners = Corner.entries.map { corner ->
            val (sides, startDegrees) = when (corner) {
                Corner.TOP_LEFT -> (Side.TOP to Side.LEFT) to 180.0
                Corner.TOP_RIGHT -> (Side.TOP to Side.RIGHT) to 270.0
                Corner.BOTTOM_RIGHT -> (Side.BOTTOM to Side.RIGHT) to 0.0
                Corner.BOTTOM_LEFT -> (Side.BOTTOM to Side.LEFT) to 90.0
            }
            // The border color right beside this corner, from the ends of its two sides.
            val horizontal = localReferences.getValue(sides.first)
            val vertical = localReferences.getValue(sides.second)
            val reference = average(
                if (sides.second == Side.LEFT) horizontal.first() else horizontal.last(),
                if (sides.first == Side.TOP) vertical.first() else vertical.last(),
            )
            val background = average(backgrounds.getValue(sides.first), backgrounds.getValue(sides.second))
            if (!assessable(reference)) return@map CornerResult(corner, 0.0, 0.0, false)

            // A factory corner: an arc of the standard radius joining the two lines.
            val centerX = if (sides.second == Side.LEFT) left + radius else right - radius
            val centerY = if (sides.first == Side.TOP) top + radius else bottom - radius
            val steps = maxOf(8, (radius * PI / 2).roundToInt())
            fun arcPoint(step: Int, inward: Int): Pair<Int, Int> {
                val angle = Math.toRadians(startDegrees + 90.0 * step / steps)
                val r = radius - inward
                return (centerX + r * cos(angle)).roundToInt() to (centerY + r * sin(angle)).roundToInt()
            }
            val cuts = (0..steps).map { step ->
                cutAlong({ d -> arcPoint(step, d).let { (x, y) -> labAt(x, y) } }, reference, background)
            }
            // The arc's ends sit on the straight edges: their cut is where this
            // card's edge normally is, so rounding is measured relative to that.
            val baseline = listOfNotNull(cuts.first(), cuts.last()).minOrNull() ?: 0
            val recessions = cuts.map { (it ?: px(SEARCH_INSIDE)) - baseline }
            val paperSteps = (0..steps).map { step ->
                val cut = cuts[step] ?: return@map false
                (cut until cut + px(PAPER_DEPTH)).count { d ->
                    arcPoint(step, d).let { (x, y) -> isPaper(labAt(x, y), reference, background) }
                } >= 2
            }
            for (step in 0..steps) {
                val cut = cuts[step] ?: continue
                if (paperSteps[step]) for (d in cut until cut + px(PAPER_DEPTH)) arcPoint(step, d).let { (x, y) ->
                    if (isPaper(labAt(x, y), reference, background)) whitened += y * width + x
                }
            }
            CornerResult(
                corner,
                roundingMm = (recessions.maxOrNull() ?: 0).coerceAtLeast(0) * mmPerPixel,
                whitenedFraction = paperSteps.count { it }.toDouble() / paperSteps.size,
                assessable = true,
            )
        }

        return Result(edges, corners, whitened.distinct().toIntArray(), missing.distinct().toIntArray(), radius)
    }

    /** Borders that are themselves white or pale gray can't show whitening. */
    private fun assessable(border: DoubleArray) =
        ColorLab.chroma(border) >= COLORFUL || border[0] < PALE_BORDER_L

    /** Bare card stock: much less colorful than the border and not dark like the background. */
    private fun isPaper(pixel: DoubleArray, border: DoubleArray, background: DoubleArray): Boolean {
        val borderChroma = ColorLab.chroma(border)
        val chroma = ColorLab.chroma(pixel)
        return if (borderChroma >= COLORFUL) {
            chroma <= borderChroma * PAPER_CHROMA && pixel[0] >= background[0] + ABOVE_BACKGROUND
        } else {
            pixel[0] >= border[0] + LIGHTER_NEUTRAL && chroma < NEUTRAL
        }
    }

    private fun average(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { (a[it] + b[it]) / 2 }

    private fun runsOf(flags: List<Boolean>): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (i in flags.indices) {
            if (flags[i] && start < 0) start = i
            if (!flags[i] && start >= 0) { runs += start until i; start = -1 }
        }
        if (start >= 0) runs += start until flags.size
        return runs
    }

    // First-pass thresholds, to calibrate against graded cards — so shown with "≈".

    /** From the worst edge's share of its length whitened (or dented, counted double). */
    fun edgeGradeFor(worst: Double): Int = when {
        worst <= 0.005 -> 10
        worst <= 0.02 -> 9
        worst <= 0.05 -> 8
        worst <= 0.10 -> 7
        worst <= 0.20 -> 6
        else -> 5
    }

    /**
     * From the corner's whitening alone. Rounding isn't graded yet: factory
     * corners differ by era (a near-mint 2025 card measured rounder against a
     * 3 mm corner than a heavily played 2001 one), so it needs a per-era factory
     * radius first; [CornerResult.roundingMm] is shown for information.
     */
    fun cornerGradeFor(corner: CornerResult): Int = when {
        corner.whitenedFraction <= 0.03 -> 10
        corner.whitenedFraction <= 0.10 -> 9
        corner.whitenedFraction <= 0.20 -> 8
        corner.whitenedFraction <= 0.35 -> 7
        corner.whitenedFraction <= 0.50 -> 6
        else -> 5
    }
}
