package com.inspectorade.cardscanner.grading

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
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
 * Works best on a dark background. In a single photo, shine at the cut — light
 * catching the cut edge, foil sparkle, holo running off the edge — looks just
 * like whitening. Given a second photo of the same side with the card turned
 * 180° (see [analyze]), wear counts only where both show it: shine moves with
 * the card's angle to the light, wear doesn't. Borders that are themselves white
 * or pale silver can't show whitening — reported as not assessable rather than
 * guessed at.
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
        /**
         * With a turned second photo: one photo showed this corner clearly marked
         * and the other didn't. Shine does that — but so does a ding, which shows
         * by how light falls on it and can catch it at one angle only. Not graded
         * either way; the user is asked to check it by eye.
         */
        val possibleDing: Boolean = false,
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
        /** Whether a turned second photo confirmed the wear (see [analyze]). */
        val confirmed: Boolean,
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
    private const val EDGE_FACE = 3         // the card's edge face can show this far in from the cut…
    private const val PAPER_DEPTH = 6       // …and whitening is looked for this deep past it
    private const val DENT = 4              // cut this much further in than usual: a dent
    private const val REFERENCE_NEAR = 12   // band the border's own color comes from
    private const val REFERENCE_FAR = 20
    private const val MIN_RUN = 3           // shortest whitened run that counts
    private const val DENT_WINDOW = 40      // a dent is judged against the cut this far either side
    private const val REFERENCE_WINDOW = 40 // border color is taken from this far either side
    // Two photos of the card line up to within a few pixels after flattening, so
    // wear in one is matched against the other this far along the edge either side.
    private const val MATCH_TOLERANCE = 4
    private const val MATCH_TOLERANCE_STEPS = 2 // the same, in steps around a corner's arc
    // A corner marked this much in one photo but no more than clean in both: a possible ding.
    private const val ONE_PHOTO_MARK = 0.10
    private const val CLEAN_CORNER = 0.03

    // Color tests, in CIELAB.
    private const val COLORFUL = 15.0       // border chroma above this: a colored border
    private const val PAPER_CHROMA = 0.5    // paper: at most this share of the border's chroma…
    private const val ABOVE_BACKGROUND = 20.0 // …and this much lighter than the background…
    private const val SAME_HUE = 0.8        // …and if its hue is the border's (cosine of the a/b angle)…
    private const val PAPER_MAX_CHROMA = 12.0 // …nearly colorless: beige card stock, not a lit-up border
    private const val DEEP_PAPER = 4        // or paper-like this many px deep: past a lit rim at the cut
    // Card stock's own hue (cream), as CIELAB a/b: borders near it can't use the hue test.
    private const val CARD_STOCK_A = 2.0
    private const val CARD_STOCK_B = 12.0
    private const val NEUTRAL = 15.0
    private const val LIGHTER_NEUTRAL = 25.0 // on gray/black borders: this much lighter, and gray
    private const val PALE_BORDER_L = 70.0  // neutral borders lighter than this can't be judged
    private const val CARD_START = 0.5      // the cut: first pixel at least this far from background toward border…
    private const val CUT_SUSTAIN = 5       // …with this many in a row (px) that are

    /**
     * Finds the wear on [card]. [turned] is an optional second photo of the same
     * side, taken with the card turned 180° and already turned back and
     * flattened into the same frame as [card]: then whitening, chips and dents
     * count only where both photos show them (within a few pixels), and a
     * corner's rounding is the smaller of the two. Highlights are on [card].
     */
    fun analyze(card: Bitmap, lines: BorderLines, turned: Bitmap? = null): Result {
        val first = Scan(card, lines)
        val second = turned?.let { Scan(it, lines) }
        val tolerance = first.px(MATCH_TOLERANCE)
        fun confirmedBy(flags: List<Boolean>, other: List<Boolean>?, within: Int): List<Boolean> =
            if (other == null) flags
            else flags.indices.map { i ->
                flags[i] && (maxOf(0, i - within)..minOf(other.size - 1, i + within)).any { other[it] }
            }

        val whitened = mutableListOf<Int>()
        val missing = mutableListOf<Int>()

        val edges = Side.entries.map { side ->
            val scan = first.edges.getValue(side)
            val other = second?.edges?.getValue(side)
            if (!scan.assessable || other?.assessable == false) return@map EdgeResult(side, 0.0, 0, 0.0, false)
            val paper = confirmedBy(scan.paper, other?.paper, tolerance)
            val runs = runsOf(paper).filter { it.last - it.first + 1 >= first.px(MIN_RUN) }
            for (run in runs) for (i in run) first.paintPaper(side, i, whitened)
            val dented = confirmedBy(scan.dented, other?.dented, tolerance)
            for (i in dented.indices) if (dented[i]) first.paintMissing(side, i, missing)
            EdgeResult(
                side,
                whitenedFraction = runs.sumOf { it.last - it.first + 1 }.toDouble() / paper.size,
                chips = runs.size,
                dentedFraction = dented.count { it }.toDouble() / dented.size,
                assessable = true,
            )
        }

        val corners = Corner.entries.map { corner ->
            val scan = first.corners.getValue(corner)
            val other = second?.corners?.getValue(corner)
            if (!scan.assessable || other?.assessable == false) return@map CornerResult(corner, 0.0, 0.0, false)
            val paper = confirmedBy(scan.paperSteps, other?.paperSteps, MATCH_TOLERANCE_STEPS)
            for (step in paper.indices) if (paper[step]) first.paintCorner(corner, step, whitened)
            val rounding = minOf(scan.roundingPx, other?.roundingPx ?: scan.roundingPx)
            fun fraction(flags: List<Boolean>) = flags.count { it }.toDouble() / flags.size
            val whitened = fraction(paper)
            val inOnePhoto = other != null && maxOf(fraction(scan.paperSteps), fraction(other.paperSteps)) > ONE_PHOTO_MARK
            CornerResult(
                corner,
                roundingMm = rounding.coerceAtLeast(0) * first.mmPerPixel,
                whitenedFraction = whitened,
                assessable = true,
                possibleDing = inOnePhoto && whitened <= CLEAN_CORNER,
            )
        }

        return Result(edges, corners, whitened.distinct().toIntArray(), missing.distinct().toIntArray(), first.radius, second != null)
    }

    /** What one side of the card shows along one edge: per point, card stock at the cut, and a dent. */
    private class EdgeScan(val assessable: Boolean, val paper: List<Boolean>, val dented: List<Boolean>)

    /** What one photo shows around a corner's arc: per step, card stock at the cut; and its rounding. */
    private class CornerScan(val assessable: Boolean, val paperSteps: List<Boolean>, val roundingPx: Int)

    /** One photo's findings at every point along the edges and around the corners. */
    private class Scan(card: Bitmap, lines: BorderLines) {
        private val width = card.width
        private val height = card.height
        private val pixels = IntArray(width * height).also { card.getPixels(it, 0, width, 0, 0, width, height) }
        private fun labAt(x: Int, y: Int): DoubleArray =
            ColorLab.lab(pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)])

        private val left = lines[BorderLine.OUTER_LEFT].roundToInt()
        private val right = lines[BorderLine.OUTER_RIGHT].roundToInt()
        private val top = lines[BorderLine.OUTER_TOP].roundToInt()
        private val bottom = lines[BorderLine.OUTER_BOTTOM].roundToInt()
        private val cardWidth = (right - left).coerceAtLeast(1)
        private val scale = cardWidth / 1000.0
        fun px(standard: Int) = maxOf(1, (standard * scale).roundToInt())
        val mmPerPixel = CARD_WIDTH_MM / cardWidth
        val radius = (CORNER_RADIUS_MM / mmPerPixel).roundToInt()

        // Where whitening is looked for, from the cut found at a point: past the
        // card's own edge face — its white core, which shows as a thin pale
        // sliver along the cut wherever the camera sees the edge a little from
        // the side — onto the card's face, where graders look for card stock.
        private fun paperBand(cut: Int) = (cut + px(EDGE_FACE)) until (cut + px(EDGE_FACE + PAPER_DEPTH))

        // Pixel at [along] on [side]'s line, [inward] pixels in (negative: outside).
        private fun point(side: Side, along: Int, inward: Int): Pair<Int, Int> = when (side) {
            Side.TOP -> along to top + inward
            Side.BOTTOM -> along to bottom - inward
            Side.LEFT -> left + inward to along
            Side.RIGHT -> right - inward to along
        }
        private fun span(side: Side) = if (side == Side.TOP || side == Side.BOTTOM) (left + radius)..(right - radius)
        else (top + radius)..(bottom - radius)

        private val references = Side.entries.associateWith { side ->
            ColorLab.median(span(side).step(4).flatMap { along ->
                (px(REFERENCE_NEAR)..px(REFERENCE_FAR) step 2).map { d -> point(side, along, d).let { (x, y) -> labAt(x, y) } }
            })
        }
        // The border's own color around each point along a side: a rolling median,
        // so light that falls off toward the corners (a shaded border reads duller
        // and paler) isn't mistaken for wear. Indexed like span(side).
        private val localReferences = Side.entries.associateWith { side ->
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
        private val backgrounds = Side.entries.associateWith { side ->
            ColorLab.median(span(side).step(8).flatMap { along ->
                (px(15)..px(35) step 5).map { d -> point(side, along, -d).let { (x, y) -> labAt(x, y) } }
            })
        }

        // Where the card starts along a line of pixels running inward: the first
        // one clearly closer to the border's color than the background's, with
        // card continuing past it — a speck of lint or dust on the surface just
        // outside the cut is a pixel or two of light with surface beyond it, and
        // would otherwise read as whitened card stock.
        private fun cutAlong(sample: (Int) -> DoubleArray, reference: DoubleArray, background: DoubleArray): Int? {
            val gap = ColorLab.distance(reference, background)
            fun isCard(d: Int) = ColorLab.distance(sample(d), background) >= gap * CARD_START
            return (-px(SEARCH_OUTSIDE)..px(SEARCH_INSIDE)).firstOrNull { d ->
                (d until d + px(CUT_SUSTAIN)).all(::isCard)
            }
        }

        private val edgeCuts = mutableMapOf<Side, List<Int?>>()
        private val edgeUsual = mutableMapOf<Side, List<Int>>()

        val edges: Map<Side, EdgeScan> = Side.entries.associateWith { side ->
            val reference = references.getValue(side)
            val background = backgrounds.getValue(side)
            if (!assessable(reference)) return@associateWith EdgeScan(false, emptyList(), emptyList())
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
            edgeCuts[side] = cuts
            edgeUsual[side] = usual
            val paper = positions.indices.map { i ->
                val cut = cuts[i] ?: return@map false
                showsPaper((paperBand(cut)).map { d ->
                    point(side, positions[i], d).let { (x, y) -> labAt(x, y) }
                }, local[i], background, scale)
            }
            val dented = positions.indices.map { i -> (cuts[i] ?: Int.MAX_VALUE) > usual[i] + px(DENT) }
            EdgeScan(true, paper, dented)
        }

        /** Adds the card-stock pixels at point [i] along [side] to [into]. */
        fun paintPaper(side: Side, i: Int, into: MutableList<Int>) {
            val cut = edgeCuts.getValue(side)[i] ?: return
            val along = span(side).first + i
            val local = localReferences.getValue(side)[i]
            val background = backgrounds.getValue(side)
            for (d in paperBand(cut)) point(side, along, d).let { (x, y) ->
                if (isPaper(labAt(x, y), local, background, strict = false)) into += y * width + x
            }
        }

        /** Adds the missing material at a dent at point [i] along [side] to [into]. */
        fun paintMissing(side: Side, i: Int, into: MutableList<Int>) {
            val usual = edgeUsual.getValue(side)[i]
            val cut = edgeCuts.getValue(side)[i] ?: usual
            val along = span(side).first + i
            for (d in usual..minOf(cut, usual + px(SEARCH_INSIDE))) point(side, along, d).let { (x, y) -> into += y * width + x }
        }

        private class Arc(
            val reference: DoubleArray, val background: DoubleArray, val cuts: List<Int?>, val point: (Int, Int) -> Pair<Int, Int>,
        )
        private val arcs = mutableMapOf<Corner, Arc>()

        val corners: Map<Corner, CornerScan> = Corner.entries.associateWith { corner ->
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
            if (!assessable(reference)) return@associateWith CornerScan(false, emptyList(), 0)

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
            arcs[corner] = Arc(reference, background, cuts, ::arcPoint)
            // The arc's ends sit on the straight edges: their cut is where this
            // card's edge normally is, so rounding is measured relative to that.
            val baseline = listOfNotNull(cuts.first(), cuts.last()).minOrNull() ?: 0
            val recessions = cuts.map { (it ?: px(SEARCH_INSIDE)) - baseline }
            val paperSteps = (0..steps).map { step ->
                val cut = cuts[step] ?: return@map false
                showsPaper((paperBand(cut)).map { d ->
                    arcPoint(step, d).let { (x, y) -> labAt(x, y) }
                }, reference, background, scale)
            }
            CornerScan(true, paperSteps, recessions.maxOrNull() ?: 0)
        }

        /** Adds the card-stock pixels at [step] around [corner]'s arc to [into]. */
        fun paintCorner(corner: Corner, step: Int, into: MutableList<Int>) {
            val arc = arcs.getValue(corner)
            val cut = arc.cuts[step] ?: return
            for (d in paperBand(cut)) arc.point(step, d).let { (x, y) ->
                if (isPaper(labAt(x, y), arc.reference, arc.background, strict = false)) into += y * width + x
            }
        }
    }

    /**
     * Whether the pixels running in from the cut ([inward], cut first) show card
     * stock: a couple of clearly bare ones, or pale ones reaching deep. Light
     * catching the cut edge makes a pale rim in the border's hue only ~3 px
     * deep, while card stock on a worn edge reaches further in — and on a
     * yellow border can be beige, the border's own hue, so depth is what tells
     * them apart there.
     */
    private fun showsPaper(inward: List<DoubleArray>, border: DoubleArray, background: DoubleArray, scale: Double): Boolean =
        inward.count { isPaper(it, border, background, strict = true) } >= 2 ||
            inward.count { isPaper(it, border, background, strict = false) } >= maxOf(1, (DEEP_PAPER * scale).roundToInt())

    /** Borders that are themselves white or pale gray can't show whitening. */
    private fun assessable(border: DoubleArray) =
        ColorLab.chroma(border) >= COLORFUL || border[0] < PALE_BORDER_L

    /**
     * Card stock: much less colorful than the border and not dark like the
     * background. [strict] also rules out the border lit up: light catching the
     * card's cut edge leaves a pale rim that keeps the border's hue (light blue
     * on a blue back), so a pixel in the border's hue must be almost colorless.
     * Not on yellow and other borders the hue of card stock itself, though:
     * there worn, dirty edges are pale yellow too, and can't be told from a
     * lit rim by color.
     */
    private fun isPaper(pixel: DoubleArray, border: DoubleArray, background: DoubleArray, strict: Boolean): Boolean {
        val borderChroma = ColorLab.chroma(border)
        val chroma = ColorLab.chroma(pixel)
        return if (borderChroma >= COLORFUL) {
            fun alignment(a: Double, b: Double, aChroma: Double) =
                (a * border[1] + b * border[2]) / (aChroma * borderChroma).coerceAtLeast(1e-6)
            val stockHued = alignment(CARD_STOCK_A, CARD_STOCK_B, hypot(CARD_STOCK_A, CARD_STOCK_B)) >= SAME_HUE
            val litBorder = strict && !stockHued && alignment(pixel[1], pixel[2], chroma) >= SAME_HUE && chroma > PAPER_MAX_CHROMA
            !litBorder && chroma <= borderChroma * PAPER_CHROMA && pixel[0] >= background[0] + ABOVE_BACKGROUND
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
