package com.inspectorade.cardscanner.grading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeSummaryTest {

    private fun side(front: Boolean, share: Double, corners: Int? = 10, edges: Int? = 10, surface: Int? = null) =
        SideResult(front, null, share, "", corners, edges, surface)

    private fun List<GradeSummary.Overall>.of(grader: String) = first { it.grader == grader }

    @Test
    fun cappedByWeakestCategoryOnEitherSide() {
        // Front centering 55/45 is PSA 10; back edges ≈8 cap it.
        val psa = GradeSummary.overall(side(true, 55.0), side(false, 60.0, edges = 8)).of("PSA")
        assertEquals("8", psa.grade.label)
        assertEquals(listOf(GradeCategory.EDGES), psa.limitedBy)
    }

    @Test
    fun centeringCapsWhenItIsWorst() {
        // Front 62/38 is PSA 8 on centering; corners and edges ≈10.
        val psa = GradeSummary.overall(side(true, 62.0), side(false, 50.0)).of("PSA")
        assertEquals("8", psa.grade.label)
        assertEquals(listOf(GradeCategory.CENTERING), psa.limitedBy)
    }

    @Test
    fun upperBoundUntilSurfaceAndBothSidesAreGraded() {
        assertFalse(GradeSummary.overall(side(true, 50.0), side(false, 50.0)).of("PSA").complete)
        assertFalse(GradeSummary.overall(side(true, 50.0, surface = 10), null).of("PSA").complete)
        assertTrue(GradeSummary.overall(side(true, 50.0, surface = 10), side(false, 50.0, surface = 10)).of("PSA").complete)
    }

    @Test
    fun surfaceCountsOnceGraded() {
        val psa = GradeSummary.overall(side(true, 50.0, surface = 7), side(false, 50.0, surface = 10)).of("PSA")
        assertEquals("7", psa.grade.label)
        assertEquals(listOf(GradeCategory.SURFACE), psa.limitedBy)
    }

    @Test
    fun wornCardShowsBottomOfScale() {
        val bgs = GradeSummary.overall(side(true, 51.0, corners = 5, edges = 5), side(false, 52.0, corners = 5, edges = 5)).of("BGS")
        assertEquals("≤5", bgs.grade.label)
        assertEquals(listOf(GradeCategory.CORNERS, GradeCategory.EDGES), bgs.limitedBy)
    }

    @Test
    fun pristineCenteringDoesNotLiftAboveOtherCategories() {
        // CGC 10P centering, but corners ≈9.
        val cgc = GradeSummary.overall(side(true, 50.0, corners = 9), side(false, 50.0)).of("CGC")
        assertEquals("9", cgc.grade.label)
    }

    @Test
    fun belowScaleCenteringLabelsCompareBelowTheirTier() {
        // TAG has no tier past 55/45 front, so 60/40 is "<10" — below a 10 on corners.
        val tag = GradeSummary.overall(side(true, 60.0), side(false, 50.0)).of("TAG")
        assertEquals("<10", tag.grade.label)
        assertEquals(listOf(GradeCategory.CENTERING), tag.limitedBy)
    }

    @Test
    fun uncountedCenteringIsLeftOut() {
        // Back lines measured the photo (card not found): 79/21 mustn't count.
        val back = side(false, 79.0, corners = null, edges = null).copy(centeringCounted = false)
        val psa = GradeSummary.overall(side(true, 50.0), back).of("PSA")
        assertEquals("10", psa.grade.label)
        assertFalse(psa.complete)
    }

    @Test
    fun nothingCountedGivesNoGrade() {
        val back = side(false, 79.0, corners = null, edges = null).copy(centeringCounted = false)
        assertTrue(GradeSummary.overall(null, back).isEmpty())
    }

    @Test
    fun wearGradesAreAlwaysEstimates() {
        assertFalse(GradeSummary.overall(side(true, 50.0), side(false, 50.0)).of("PSA").verified)
    }
}
