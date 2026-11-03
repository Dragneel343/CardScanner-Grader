package com.inspectorade.cardscanner.grading

/**
 * Published centering tolerances, expressed as the largest share (%) the wider
 * border may take on an axis — 55.0 means "55/45 or better".
 *
 * Only PSA has been cross-checked against the grader's own published standard.
 * The others conflict between secondary sources and are flagged unverified (the
 * UI marks them with ≈); confirm against each grader's current rubric before
 * relying on them.
 */
object CenteringStandards {

    data class Tier(val label: String, val frontMax: Double, val backMax: Double)

    data class Grader(
        val name: String,
        val verified: Boolean,
        val tiers: List<Tier>,
        val belowLabel: String,
    )

    data class Result(val grader: String, val grade: String, val verified: Boolean)

    val graders = listOf(
        Grader(
            "PSA", verified = true, belowLabel = "≤2",
            tiers = listOf(
                Tier("10", 55.0, 75.0),
                Tier("9", 60.0, 90.0),
                Tier("8", 65.0, 90.0),
                Tier("7", 70.0, 90.0),
                Tier("6", 80.0, 90.0),
                Tier("5", 85.0, 90.0),
                Tier("3", 90.0, 90.0),
            )
        ),
        Grader(
            "BGS", verified = false, belowLabel = "<7",
            tiers = listOf(
                Tier("10", 50.0, 60.0),
                Tier("9.5", 55.0, 60.0),
                Tier("9", 55.0, 70.0),
                Tier("8.5", 60.0, 80.0),
                Tier("8", 65.0, 80.0),
                Tier("7", 75.0, 90.0),
            )
        ),
        Grader(
            "CGC", verified = false, belowLabel = "<9",
            tiers = listOf(
                Tier("10P", 50.0, 55.0),
                Tier("10", 55.0, 75.0),
                Tier("9", 60.0, 90.0),
            )
        ),
        Grader(
            "TAG", verified = false, belowLabel = "<10",
            tiers = listOf(
                Tier("10P", 51.0, 52.0),
                Tier("10", 55.0, 65.0),
            )
        ),
    )

    /** [worstShare] is the wider border's share (%) on whichever axis is worse. */
    fun grade(worstShare: Double, isFront: Boolean): List<Result> = graders.map { grader ->
        val tier = grader.tiers.firstOrNull { worstShare <= if (isFront) it.frontMax else it.backMax }
        Result(grader.name, tier?.label ?: grader.belowLabel, grader.verified)
    }
}
