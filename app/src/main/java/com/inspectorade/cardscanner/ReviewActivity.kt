package com.inspectorade.cardscanner

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.inspectorade.cardscanner.databinding.ActivityReviewBinding

/**
 * Shown right after a capture so the user can check framing/focus before
 * committing to it. Retake discards the just-captured file(s) from MediaStore
 * and returns to the camera for another attempt. Confirm keeps the files and
 * reports RESULT_OK back up the chain (to CaptureActivity, which forwards it
 * to whoever launched it — e.g. CardSidesActivity marking that side done).
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

        binding.confirmButton.setOnClickListener {
            setResult(Activity.RESULT_OK)
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
    }
}
