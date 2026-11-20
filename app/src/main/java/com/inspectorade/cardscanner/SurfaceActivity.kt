package com.inspectorade.cardscanner

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.inspectorade.cardscanner.databinding.ActivitySurfaceBinding
import com.inspectorade.cardscanner.surface.GlareFusion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File

/**
 * A tilt scan's result (see [GlareFusion]): the surface with the glare taken
 * out, and the shine map of where light changed as the phone tilted. Not
 * graded yet — for looking at the surface, and for tuning the scan.
 */
class SurfaceActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySurfaceBinding
    private var result: GlareFusion.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySurfaceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        val isFront = (intent.getStringExtra(EXTRA_SIDE) ?: CaptureActivity.SIDE_FRONT) == CaptureActivity.SIDE_FRONT
        binding.surfaceTitle.setText(if (isFront) R.string.surface_title_front else R.string.surface_title_back)
        binding.surfaceDoneButton.setOnClickListener { finish() }
        binding.surfaceViewRow.setOnCheckedChangeListener { _, _ -> showSelected() }

        val dir = intent.getStringExtra(EXTRA_SURFACE_DIR)?.let { File(it) }
        val frames = dir?.listFiles { f -> f.extension == "jpg" }?.toList().orEmpty()
        binding.surfaceInfoText.text = getString(R.string.surface_aligning, 0, frames.size)
        lifecycleScope.launch {
            val fused = withContext(Dispatchers.Default) {
                check(OpenCVLoader.initLocal())
                GlareFusion.fuse(frames) { done, total ->
                    runOnUiThread { binding.surfaceInfoText.text = getString(R.string.surface_aligning, done, total) }
                }?.also { save(it) }
            }
            binding.surfaceProgress.visibility = View.GONE
            if (fused == null) {
                binding.surfaceInfoText.setText(R.string.surface_failed)
                return@launch
            }
            result = fused
            binding.surfaceInfoText.text = getString(R.string.surface_summary, fused.framesUsed, fused.framesDropped)
            listOf(binding.surfaceGlareFreeOption, binding.surfaceShineOption, binding.surfaceFrameOption)
                .forEach { it.isEnabled = true }
            showSelected()
        }
    }

    private fun showSelected() {
        val fused = result ?: return
        val (image, hint) = when (binding.surfaceViewRow.checkedRadioButtonId) {
            R.id.surfaceShineOption -> fused.shine to R.string.surface_hint_shine
            R.id.surfaceFrameOption -> fused.reference to R.string.surface_hint_frame
            else -> fused.glareFree to R.string.surface_hint_glare_free
        }
        binding.surfaceImage.setImageBitmap(image)
        binding.surfaceHintText.setText(hint)
    }

    /** Keeps the latest result per side in the app's files, to look at off the phone. */
    private fun save(fused: GlareFusion.Result) {
        val side = intent.getStringExtra(EXTRA_SIDE) ?: CaptureActivity.SIDE_FRONT
        val out = File(getExternalFilesDir(null), "surface_out/$side").apply { deleteRecursively(); mkdirs() }
        listOf("glare_free" to fused.glareFree, "shine" to fused.shine, "reference" to fused.reference).forEach { (name, bitmap) ->
            File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        Log.i(TAG, "Saved surface views to $out")
    }

    companion object {
        private const val TAG = "SurfaceActivity"
        const val EXTRA_SURFACE_DIR = CaptureActivity.EXTRA_SURFACE_DIR
        const val EXTRA_SIDE = "extra_side"
    }
}
