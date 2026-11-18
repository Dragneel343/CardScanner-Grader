package com.inspectorade.cardscanner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
import com.inspectorade.cardscanner.databinding.ActivityCardSidesBinding
import com.inspectorade.cardscanner.grading.SideResult

/**
 * Hub screen shown after "Grade a Card" — lets the user capture the front and
 * back in either order, checking off each side once its capture has been
 * reviewed and confirmed. Each side reuses the full CaptureActivity/
 * ReviewActivity flow (same features, independent instance per side). Once a
 * side is graded, Overall Grade combines what's done so far.
 */
class CardSidesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCardSidesBinding
    private var frontDone = false
    private var backDone = false
    private var frontSummary: String? = null
    private var backSummary: String? = null
    private var frontResult: SideResult? = null
    private var backResult: SideResult? = null

    private val frontLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                frontDone = true
                frontSummary = result.data?.getStringExtra(CenteringActivity.EXTRA_CENTERING_SUMMARY)
                frontResult = result.data?.sideResult()
                updateCheckmarks()
            }
        }

    private val backLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                backDone = true
                backSummary = result.data?.getStringExtra(CenteringActivity.EXTRA_CENTERING_SUMMARY)
                backResult = result.data?.sideResult()
                updateCheckmarks()
            }
        }

    private fun Intent.sideResult() =
        IntentCompat.getSerializableExtra(this, CenteringActivity.EXTRA_SIDE_RESULT, SideResult::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCardSidesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val backButtonBaseTopMargin = binding.backButton.marginTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.backButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = backButtonBaseTopMargin + systemBars.top
            }
            insets
        }

        binding.backButton.setOnClickListener { finish() }

        binding.frontButton.setOnClickListener {
            frontLauncher.launch(
                Intent(this, CaptureActivity::class.java)
                    .putExtra(CaptureActivity.EXTRA_SIDE, CaptureActivity.SIDE_FRONT)
            )
        }

        binding.backSideButton.setOnClickListener {
            backLauncher.launch(
                Intent(this, CaptureActivity::class.java)
                    .putExtra(CaptureActivity.EXTRA_SIDE, CaptureActivity.SIDE_BACK)
            )
        }

        binding.gradeSummaryButton.setOnClickListener {
            startActivity(
                Intent(this, GradeSummaryActivity::class.java)
                    .putExtra(GradeSummaryActivity.EXTRA_FRONT, frontResult)
                    .putExtra(GradeSummaryActivity.EXTRA_BACK, backResult)
            )
        }
    }

    private fun updateCheckmarks() {
        binding.frontCheckmark.visibility = if (frontDone) View.VISIBLE else View.INVISIBLE
        binding.backCheckmark.visibility = if (backDone) View.VISIBLE else View.INVISIBLE
        binding.gradeSummaryButton.visibility =
            if (frontResult != null || backResult != null) View.VISIBLE else View.GONE
        binding.frontCheckmark.text = checkmarkText(frontSummary)
        binding.backCheckmark.text = checkmarkText(backSummary)
    }

    private fun checkmarkText(summary: String?): String {
        val captured = getString(R.string.side_checkmark)
        return if (summary == null) captured else "$captured\n$summary"
    }
}
