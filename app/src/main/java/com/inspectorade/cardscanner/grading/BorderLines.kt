package com.inspectorade.cardscanner.grading

import kotlin.math.max

enum class BorderLine(val vertical: Boolean, val outer: Boolean) {
    OUTER_LEFT(true, true),
    OUTER_RIGHT(true, true),
    OUTER_TOP(false, true),
    OUTER_BOTTOM(false, true),
    INNER_LEFT(true, false),
    INNER_RIGHT(true, false),
    INNER_TOP(false, false),
    INNER_BOTTOM(false, false),
}

/**
 * How a card's borders are paired when judging centering.
 *
 * STANDARD compares opposite borders (left vs right, top vs bottom).
 *
 * E_READER is for e-Reader era cards (Expedition, Aquapolis, Skyridge, …), whose
 * left and bottom borders are deliberately wider to hold the dot-code strip, so
 * opposite borders are never meant to match. The collector-community convention
 * (shared in grading videos and forums — not found in any grader's published
 * standard) pairs the two normal-width borders, top vs right, and the two wide
 * ones, left vs bottom. Revisit if a grader publishes an official method.
 *
 * EX_DOT_CODE is for EX-era non-holo cards (EX Ruby & Sapphire, Sandstorm,
 * Dragon) whose dot-code strip runs along the bottom only, so just the bottom
 * border is wide. Left vs right is unaffected and graded normally; top vs bottom
 * is shown but not graded until there's a calibrated design baseline for how
 * wide the bottom is meant to be (no grader or community rule exists for these).
 */
enum class CardLayout { STANDARD, E_READER, EX_DOT_CODE }

/** Two border pairs, each as the first border's share (%) of the pair. */
data class CenteringReading(
    val firstLabel: String,
    val firstShare: Double,
    val secondLabel: String,
    val secondShare: Double,
    val secondGraded: Boolean = true,
) {
    /** The wider border's share in whichever graded pair is worse — what graders key off. */
    val worstShare: Double
        get() {
            val first = max(firstShare, 100 - firstShare)
            return if (secondGraded) max(first, max(secondShare, 100 - secondShare)) else first
        }
}

/**
 * Positions (in flattened-card bitmap pixels) of the card's physical edge (outer)
 * and printed border edge (inner) on each side. Centering compares the widths of
 * pairs of borders.
 */
class BorderLines(private val positions: FloatArray = FloatArray(BorderLine.entries.size)) {

    operator fun get(line: BorderLine): Float = positions[line.ordinal]

    operator fun set(line: BorderLine, value: Float) {
        positions[line.ordinal] = value
    }

    fun copy(): BorderLines = BorderLines(positions.copyOf())

    /** Positions in [BorderLine] order, for passing between screens; rebuild with the constructor. */
    fun toArray(): FloatArray = positions.copyOf()

    private val leftWidth get() = this[BorderLine.INNER_LEFT] - this[BorderLine.OUTER_LEFT]
    private val rightWidth get() = this[BorderLine.OUTER_RIGHT] - this[BorderLine.INNER_RIGHT]
    private val topWidth get() = this[BorderLine.INNER_TOP] - this[BorderLine.OUTER_TOP]
    private val bottomWidth get() = this[BorderLine.OUTER_BOTTOM] - this[BorderLine.INNER_BOTTOM]

    fun reading(layout: CardLayout): CenteringReading = when (layout) {
        CardLayout.STANDARD -> CenteringReading(
            "L|R", share(leftWidth, rightWidth),
            "T|B", share(topWidth, bottomWidth),
        )
        CardLayout.E_READER -> CenteringReading(
            "T|R", share(topWidth, rightWidth),
            "L|B", share(leftWidth, bottomWidth),
        )
        CardLayout.EX_DOT_CODE -> CenteringReading(
            "L|R", share(leftWidth, rightWidth),
            "T|B", share(topWidth, bottomWidth),
            secondGraded = false,
        )
    }

    private fun share(a: Float, b: Float): Double =
        if (a + b <= 0f) 50.0 else a.toDouble() / (a + b) * 100.0
}
