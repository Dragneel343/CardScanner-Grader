package com.inspectorade.cardscanner.grading

import java.io.Serializable

/** What graders judge a card on. A side's grade is capped by its weakest category. */
enum class GradeCategory(val label: String) {
    CENTERING("Centering"),
    CORNERS("Corners"),
    EDGES("Edges"),
    SURFACE("Surface"),
}

/**
 * One side's results, carried from centering through corners & edges back to
 * the front/back hub. A null grade is a category not assessed (yet) — surface
 * until surface grading exists, corners and edges when the card's outline
 * wasn't found.
 */
data class SideResult(
    val isFront: Boolean,
    val cardLabel: String?,
    /** The wider border's share (%) on the worse graded axis — see [CenteringReading.worstShare]. */
    val centeringWorstShare: Double,
    /** The centering as shown, e.g. "L|R 50/50 · T|B 55/45". */
    val centeringText: String,
    val cornerGrade: Int? = null,
    val edgeGrade: Int? = null,
    val surfaceGrade: Int? = null,
    /**
     * False when the card's outline wasn't found (or came out skewed) and the
     * user left the lines as detected: then they measure the photo, not the
     * card, and the centering isn't counted.
     */
    val centeringCounted: Boolean = true,
    /** Corners (by label) a turned second photo didn't confirm — possible dings, not graded. */
    val possibleDings: List<String> = emptyList(),
) : Serializable

/**
 * Combines both sides' results into an estimated overall grade per grader.
 *
 * The overall grade is capped by the weakest category on either side: graders
 * don't average a card's strengths against its flaws. Until every category is
 * assessed on both sides the result is only an upper bound ("up to"): a
 * category not assessed yet can only bring the grade down.
 */
object GradeSummary {

    /** A grade as shown ([label]) with a [value] to compare by ("10P" above 10, "<7" below 7). */
    data class Grade(val value: Double, val label: String)

    data class Overall(
        val grader: String,
        val grade: Grade,
        /** The categories at the grade's level — what holds the card there. */
        val limitedBy: List<GradeCategory>,
        /** False while a side or a category is missing: then [grade] is an upper bound. */
        val complete: Boolean,
        /** Only when every input is checked against the grader's published standard. */
        val verified: Boolean,
    )

    /** Empty if nothing on either side could be counted. */
    fun overall(front: SideResult?, back: SideResult?): List<Overall> {
        val sides = listOfNotNull(front, back)
        if (sides.none { it.centeringCounted || it.cornerGrade != null || it.edgeGrade != null || it.surfaceGrade != null }) {
            return emptyList()
        }
        val complete = front != null && back != null && sides.all {
            it.centeringCounted && it.cornerGrade != null && it.edgeGrade != null && it.surfaceGrade != null
        }
        return CenteringStandards.graders.map { grader ->
            val centering = sides.filter { it.centeringCounted }.map { side ->
                val result = CenteringStandards.grade(side.centeringWorstShare, side.isFront).first { it.grader == grader.name }
                centeringGrade(result.grade)
            }.minByOrNull { it.value }
            val byCategory = buildMap {
                centering?.let { put(GradeCategory.CENTERING, it) }
                sides.mapNotNull { it.cornerGrade }.minOrNull()?.let { put(GradeCategory.CORNERS, wearGrade(it)) }
                sides.mapNotNull { it.edgeGrade }.minOrNull()?.let { put(GradeCategory.EDGES, wearGrade(it)) }
                sides.mapNotNull { it.surfaceGrade }.minOrNull()?.let { put(GradeCategory.SURFACE, wearGrade(it)) }
            }
            val lowest = byCategory.values.minBy { it.value }
            Overall(
                grader = grader.name,
                grade = lowest,
                limitedBy = byCategory.filterValues { it.value == lowest.value }.keys.toList(),
                complete = complete,
                // Corner, edge and surface thresholds aren't calibrated against graded cards yet.
                verified = grader.verified && byCategory.keys == setOf(GradeCategory.CENTERING),
            )
        }
    }

    /** Label for a corner/edge/surface grade: the scale bottoms out at "≤5". */
    fun wearLabel(grade: Int): String = if (grade <= 5) "≤5" else "$grade"

    private fun wearGrade(grade: Int) = Grade(grade.toDouble(), wearLabel(grade))

    /** [CenteringStandards] tier labels: "10P" (pristine), "9.5", or below the scale ("<7", "≤2"). */
    private fun centeringGrade(label: String): Grade = when {
        label.endsWith("P") -> Grade(label.dropLast(1).toDouble() + 0.5, label)
        label.startsWith("≤") -> Grade(label.drop(1).toDouble(), label)
        label.startsWith("<") -> Grade(label.drop(1).toDouble() - 0.5, label)
        else -> Grade(label.toDouble(), label)
    }
}
