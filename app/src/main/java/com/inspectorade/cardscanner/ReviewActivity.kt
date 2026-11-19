package com.inspectorade.cardscanner

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.inspectorade.cardscanner.databinding.ActivityReviewBinding

/**
 * Shown right after a capture so the user can check framing/focus before
 * committing to it. Retake discards the just-captured file(s) from MediaStore
 * and returns to the camera for another attempt. Confirm keeps the files and
 * moves on to card selection (front) or centering (back); when that's done, its
 * RESULT_OK (with the centering
 * summary) is forwarded up the chain to CaptureActivity and then to whoever
 * launched it — e.g. CardSidesActivity marking that side done.
 */
class ReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val jpegUri = intent.getStringExtra(EXTRA_JPEG_URI)?.toUri()
        val rawUri = intent.getStringExtra(EXTRA_RAW_URI)?.toUri()
        val side = intent.getStringExtra(EXTRA_SIDE) ?: CaptureActivity.SIDE_FRONT
        val isFront = side == CaptureActivity.SIDE_FRONT

        binding.reviewImage.setImageURI(jpegUri)
        binding.reviewTitle.text = getString(
            if (isFront) R.string.review_title_front else R.string.review_title_back
        )

        binding.retakeButton.setOnClickListener {
            deleteUri(jpegUri)
            deleteUri(rawUri)
            finish()
        }

        // The front goes through card selection first; the back has nothing to identify.
        // A turned second photo is only wanted for itself, by corners & edges.
        binding.confirmButton.setOnClickListener {
            if (intent.getBooleanExtra(EXTRA_TURNED, false)) {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_JPEG_URI, jpegUri.toString()))
                finish()
                return@setOnClickListener
            }
            nextLauncher.launch(
                if (isFront) {
                    Intent(this, CardSelectActivity::class.java)
                        .putExtra(CardSelectActivity.EXTRA_JPEG_URI, jpegUri.toString())
                        .putExtra(CardSelectActivity.EXTRA_SIDE, side)
                } else {
                    Intent(this, CenteringActivity::class.java)
                        .putExtra(CenteringActivity.EXTRA_JPEG_URI, jpegUri.toString())
                        .putExtra(CenteringActivity.EXTRA_SIDE, side)
                }
            )
        }
    }

    // Backing out of the next screen returns here so the user can still retake.
    private val nextLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                setResult(Activity.RESULT_OK, result.data)
                finish()
            }
        }

    private fun deleteUri(uri: Uri?) {
        uri ?: return
        contentResolver.delete(uri, null, null)
    }

    companion object {
        const val EXTRA_JPEG_URI = "extra_jpeg_uri"
        const val EXTRA_RAW_URI = "extra_raw_uri"
        const val EXTRA_SIDE = "extra_side"
        const val EXTRA_TURNED = "extra_turned"
    }
}
