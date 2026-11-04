package com.inspectorade.cardscanner.grading

import android.content.Context
import android.graphics.Bitmap
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Colors for the centering lines: picked automatically for each card (the
 * default), or chosen by the user and persisted between sessions.
 *
 * The palette is Okabe-Ito — designed to stay distinguishable under the common
 * forms of color blindness — plus white and black for maximum contrast against
 * light or dark card borders. Outer and inner lines also differ by pattern
 * (solid vs dashed, see [CenteringView]), so they never rely on color alone.
 */
object LineStyle {

    data class Swatch(val name: String, val color: Int)

    val palette = listOf(
        Swatch("Vermillion", 0xFFD55E00.toInt()),
        Swatch("Orange", 0xFFE69F00.toInt()),
        Swatch("Yellow", 0xFFF0E442.toInt()),
        Swatch("Bluish green", 0xFF009E73.toInt()),
        Swatch("Sky blue", 0xFF56B4E9.toInt()),
        Swatch("Blue", 0xFF0072B2.toInt()),
        Swatch("Reddish purple", 0xFFCC79A7.toInt()),
        Swatch("White", 0xFFFFFFFF.toInt()),
        Swatch("Black", 0xFF000000.toInt()),
    )

    private const val PREFS = "centering_line_style"
    private const val KEY_OUTER = "outer_color"
    private const val KEY_INNER = "inner_color"
    private const val KEY_AUTO = "auto"
    private val DEFAULT_OUTER = palette[0].color
    private val DEFAULT_INNER = palette[4].color

    fun outerColor(context: Context): Int = prefs(context).getInt(KEY_OUTER, DEFAULT_OUTER)

    fun innerColor(context: Context): Int = prefs(context).getInt(KEY_INNER, DEFAULT_INNER)

    fun save(context: Context, outer: Int, inner: Int) {
        prefs(context).edit().putInt(KEY_OUTER, outer).putInt(KEY_INNER, inner).apply()
    }

    fun isAuto(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, true)

    fun setAuto(context: Context, auto: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, auto).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Samples along each side, within its middle 60% (clear of corners).
    private const val SAMPLES_PER_SIDE = 24

    // How far past the inner line the frame is sampled, as a fraction of card size.
    private const val FRAME_OFFSET = 0.02

    // A swatch is rated by its worst contrasts, not its average, so it has to
    // stand out everywhere along the line; the 10th percentile rather than the
    // minimum, so a few holo sparkles can't veto a good color.
    private const val SCORE_PERCENTILE = 0.1

    // Lightness differences survive every type of color blindness; hue
    // differences don't always, so lightness counts double.
    private const val LIGHTNESS_WEIGHT = 2.0

    /**
     * The (outer, inner) pair of palette colors that stands out most on this
     * card: the solid outer line against the background and border it divides,
     * the dashed inner line against the border and the frame. Always two
     * different colors, so the lines stay told apart.
     */
    fun autoColors(card: Bitmap, margin: Int, lines: BorderLines): Pair<Int, Int> {
        val outerSamples = mutableListOf<DoubleArray>()
        val innerSamples = mutableListOf<DoubleArray>()
        val frameOffsetX = ((card.width - 2 * margin) * FRAME_OFFSET).toFloat()
        val frameOffsetY = ((card.height - 2 * margin) * FRAME_OFFSET).toFloat()

        for (side in listOf(BorderLine.INNER_LEFT, BorderLine.INNER_RIGHT, BorderLine.INNER_TOP, BorderLine.INNER_BOTTOM)) {
            val vertical = side.vertical
            val outerLine = when (side) {
                BorderLine.INNER_LEFT -> BorderLine.OUTER_LEFT
                BorderLine.INNER_RIGHT -> BorderLine.OUTER_RIGHT
                BorderLine.INNER_TOP -> BorderLine.OUTER_TOP
                else -> BorderLine.OUTER_BOTTOM
            }
            val outer = lines[outerLine]
            val inner = lines[side]
            val inward = if (inner > outer) 1f else -1f
            val background = outer - inward * margin / 2f
            val border = (outer + inner) / 2
            val frame = inner + inward * (if (vertical) frameOffsetX else frameOffsetY)
            val span = if (vertical) card.height else card.width
            for (k in 0 until SAMPLES_PER_SIDE) {
                val along = span * (0.2f + 0.6f * k / (SAMPLES_PER_SIDE - 1))
                fun lab(across: Float) = sampleLab(card, if (vertical) across else along, if (vertical) along else across)
                val bg = lab(background)
                val bd = lab(border)
                val fr = lab(frame)
                outerSamples += bg
                outerSamples += bd
                innerSamples += bd
                innerSamples += fr
            }
        }

        val outerScores = palette.associate { it.color to score(it.color, outerSamples) }
        val innerScores = palette.associate { it.color to score(it.color, innerSamples) }
        // Best pair overall: the weaker line of the two is what limits readability.
        val best = palette.flatMap { o -> palette.filter { it != o }.map { i -> o to i } }
            .maxBy { (o, i) -> minOf(outerScores.getValue(o.color), innerScores.getValue(i.color)) }
        return best.first.color to best.second.color
    }

    private fun score(color: Int, samples: List<DoubleArray>): Double {
        val swatch = lab(color)
        val distances = samples.map { distance(swatch, it) }.sorted()
        return distances[(distances.size * SCORE_PERCENTILE).toInt()]
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double {
        val dl = (a[0] - b[0]) * LIGHTNESS_WEIGHT
        val da = a[1] - b[1]
        val db = a[2] - b[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    /** Mean color of a 5×5 patch, in CIELAB. */
    private fun sampleLab(card: Bitmap, x: Float, y: Float): DoubleArray {
        val cx = x.toInt().coerceIn(2, card.width - 3)
        val cy = y.toInt().coerceIn(2, card.height - 3)
        var r = 0
        var g = 0
        var b = 0
        for (dy in -2..2) for (dx in -2..2) {
            val p = card.getPixel(cx + dx, cy + dy)
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        return lab(0xFF shl 24 or ((r / 25) shl 16) or ((g / 25) shl 8) or (b / 25))
    }

    /** sRGB → CIELAB (D65). */
    private fun lab(color: Int): DoubleArray {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = linear((color shr 16) and 0xFF)
        val g = linear((color shr 8) and 0xFF)
        val b = linear(color and 0xFF)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }
}
