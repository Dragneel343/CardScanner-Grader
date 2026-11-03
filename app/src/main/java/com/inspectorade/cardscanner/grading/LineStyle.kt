package com.inspectorade.cardscanner.grading

import android.content.Context

/**
 * User-chosen colors for the centering lines, persisted between sessions.
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
    private val DEFAULT_OUTER = palette[0].color
    private val DEFAULT_INNER = palette[4].color

    fun outerColor(context: Context): Int = prefs(context).getInt(KEY_OUTER, DEFAULT_OUTER)

    fun innerColor(context: Context): Int = prefs(context).getInt(KEY_INNER, DEFAULT_INNER)

    fun save(context: Context, outer: Int, inner: Int) {
        prefs(context).edit().putInt(KEY_OUTER, outer).putInt(KEY_INNER, inner).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
