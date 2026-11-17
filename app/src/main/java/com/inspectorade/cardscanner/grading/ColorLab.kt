package com.inspectorade.cardscanner.grading

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * sRGB → CIELAB (D65), where distances roughly track how different two colors
 * look: L is lightness 0–100, a/b the color axes.
 */
object ColorLab {

    fun lab(color: Int): DoubleArray {
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

    /** How colorful (vs gray) a Lab color is. */
    fun chroma(lab: DoubleArray): Double = sqrt(lab[1] * lab[1] + lab[2] * lab[2])

    fun distance(a: DoubleArray, b: DoubleArray): Double {
        val dl = a[0] - b[0]
        val da = a[1] - b[1]
        val db = a[2] - b[2]
        return sqrt(dl * dl + da * da + db * db)
    }

    /** Per-channel median of Lab colors (robust to a minority of odd pixels). */
    fun median(colors: List<DoubleArray>): DoubleArray =
        DoubleArray(3) { c -> colors.map { it[c] }.sorted()[colors.size / 2] }
}
