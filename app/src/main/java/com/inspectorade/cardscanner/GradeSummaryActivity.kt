package com.inspectorade.cardscanner

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.inspectorade.cardscanner.databinding.ActivityGradeSummaryBinding
import com.inspectorade.cardscanner.grading.GradeCategory
import com.inspectorade.cardscanner.grading.GradeSummary
import com.inspectorade.cardscanner.grading.SideResult

/**
 * The card's estimated overall grade per grader, from whatever has been graded
 * so far (see [GradeSummary]), with each side's results by category and what
 * is still missing — surface, until surface grading exists, so the grades are
 * shown as upper bounds.
 */
class GradeSummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGradeSummaryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGradeSummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        binding.summaryBackButton.setOnClickListener { finish() }

        val front = IntentCompat.getSerializableExtra(intent, EXTRA_FRONT, SideResult::class.java)
        val back = IntentCompat.getSerializableExtra(intent, EXTRA_BACK, SideResult::class.java)

        (front?.cardLabel ?: back?.cardLabel)?.let {
            binding.summaryCardLabel.text = it
            binding.summaryCardLabel.visibility = View.VISIBLE
        }
        val graders = GradeSummary.overall(front, back)
        binding.summaryOverallText.text = if (graders.isEmpty()) getString(R.string.grade_summary_nothing)
        else graders.joinToString("\n") { overall ->
            val grade = (if (overall.verified) "" else "≈") + overall.grade.label
            val prefix = if (overall.complete) "" else getString(R.string.grade_summary_up_to) + " "
            "%-4s %-10s %s".format(
                overall.grader, prefix + grade,
                overall.limitedBy.joinToString(", ") { it.label.lowercase() },
            )
        }
        binding.summaryBreakdownText.text = listOfNotNull(
            front?.let { breakdown(getString(R.string.side_front_button), it) },
            back?.let { breakdown(getString(R.string.side_back_button), it) },
        ).joinToString("\n\n")
        binding.summaryNotesText.text = notes(front, back).joinToString("\n\n")
    }

    /** A side's results, one category per line; "–" for not assessed. */
    private fun breakdown(title: String, side: SideResult): String {
        fun wear(grade: Int?) = grade?.let { "≈" + GradeSummary.wearLabel(it) } ?: "–"
        return listOf(
            title,
            "  %-10s %s".format(
                GradeCategory.CENTERING.label,
                if (side.centeringCounted) side.centeringText else getString(R.string.grade_summary_not_counted),
            ),
            "  %-10s %s".format(GradeCategory.CORNERS.label, wear(side.cornerGrade)),
            "  %-10s %s".format(GradeCategory.EDGES.label, wear(side.edgeGrade)),
            "  %-10s %s".format(GradeCategory.SURFACE.label, wear(side.surfaceGrade)),
        ).joinToString("\n")
    }

    /** What's missing, so the grade reads as the upper bound it is, and what ≈ means. */
    private fun notes(front: SideResult?, back: SideResult?): List<String> = buildList {
        if (front == null) add(getString(R.string.grade_summary_missing_side, getString(R.string.side_front_button).lowercase()))
        if (back == null) add(getString(R.string.grade_summary_missing_side, getString(R.string.side_back_button).lowercase()))
        for (side in listOfNotNull(front, back)) {
            if (!side.centeringCounted) {
                add(getString(R.string.grade_summary_centering_not_counted, if (side.isFront) "front" else "back"))
            }
            if (side.cornerGrade == null || side.edgeGrade == null) {
                add(getString(R.string.grade_summary_missing_wear, if (side.isFront) "front" else "back"))
            }
        }
        if (listOfNotNull(front, back).any { it.surfaceGrade == null }) add(getString(R.string.grade_summary_missing_surface))
        add(getString(R.string.grade_summary_how))
    }

    companion object {
        const val EXTRA_FRONT = "extra_front"
        const val EXTRA_BACK = "extra_back"
    }
}
