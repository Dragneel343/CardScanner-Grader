package com.inspectorade.cardscanner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
import com.inspectorade.cardscanner.databinding.ActivityCardSidesBinding

/**
 * Hub screen shown after "Grade a Card" — lets the user capture the front and
 * back in either order, checking off each side once its capture has been
 * reviewed and confirmed. Each side reuses the full CaptureActivity/
 * ReviewActivity flow (same features, independent instance per side).
 */
class CardSidesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCardSidesBinding
    private var frontDone = false
    private var backDone = false

    private val frontLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                frontDone = true
                updateCheckmarks()
            }
        }

    private val backLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                backDone = true
                updateCheckmarks()
            }
        }

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
    }

    private fun updateCheckmarks() {
        binding.frontCheckmark.visibility = if (frontDone) View.VISIBLE else View.INVISIBLE
        binding.backCheckmark.visibility = if (backDone) View.VISIBLE else View.INVISIBLE
    }
}
