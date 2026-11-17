package com.inspectorade.cardscanner

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.inspectorade.cardscanner.databinding.ActivityCornersEdgesBinding
import com.inspectorade.cardscanner.grading.BorderLine
import com.inspectorade.cardscanner.grading.BorderLines
import com.inspectorade.cardscanner.grading.CornerEdgeAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Corners & Edges, after centering for each side: finds whitening along the
 * edges and wear at the corners of the flattened card (see
 * [CornerEdgeAnalyzer]), highlights it on the card and in corner close-ups, and
 * gives estimated grades. Done returns the centering summary with these added.
 */
class CornersEdgesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCornersEdgesBinding
    private var result: CornerEdgeAnalyzer.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCornersEdgesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        val isFront = (intent.getStringExtra(EXTRA_SIDE) ?: CaptureActivity.SIDE_FRONT) == CaptureActivity.SIDE_FRONT
        binding.cornersEdgesTitle.setText(if (isFront) R.string.wear_title_front else R.string.wear_title_back)
        binding.wearBackButton.setOnClickListener { finish() }
        binding.wearDoneButton.setOnClickListener { finishWithSummary() }

        val path = intent.getStringExtra(EXTRA_CARD_PATH)
        val positions = intent.getFloatArrayExtra(EXTRA_LINES)
        if (path == null || positions == null || positions.size != BorderLine.entries.size) {
            Toast.makeText(this, R.string.wear_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val lines = BorderLines(positions)

        lifecycleScope.launch {
            val (card, analysis) = try {
                withContext(Dispatchers.Default) {
                    val card = BitmapFactory.decodeFile(path) ?: error("Couldn't read $path")
                    card to CornerEdgeAnalyzer.analyze(card, lines)
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Corner/edge analysis failed", exc)
                Toast.makeText(this@CornersEdgesActivity, R.string.wear_failed, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            result = analysis
            logResult(analysis)
            val highlighted = withContext(Dispatchers.Default) { highlighted(card, analysis) }
            binding.wearProgress.visibility = View.GONE
            binding.wearCardImage.setImageBitmap(highlighted)
            showGrades(analysis)
            showCornerTiles(highlighted, lines, analysis)
            binding.wearDoneButton.isEnabled = true
        }
    }

    /** The card with whitening and missing corner material painted in. */
    private fun highlighted(card: Bitmap, analysis: CornerEdgeAnalyzer.Result): Bitmap {
        val copy = card.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(copy.width * copy.height)
        copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
        for (i in analysis.whitenedPixels) pixels[i] = WHITENING_COLOR
        for (i in analysis.missingPixels) pixels[i] = MISSING_COLOR
        copy.setPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
        return copy
    }

    private fun showGrades(analysis: CornerEdgeAnalyzer.Result) {
        fun grade(value: Int?) = value?.let { if (it <= 5) "≈≤5" else "≈$it" } ?: getString(R.string.wear_not_assessable)
        binding.wearGradeText.text = getString(R.string.wear_grades, grade(analysis.cornerGrade), grade(analysis.edgeGrade))
        binding.wearDetailText.text = analysis.edges.joinToString("\n") { edge ->
            if (!edge.assessable) "%-6s  %s".format(edge.side.label, getString(R.string.wear_not_assessable))
            else String.format(
                Locale.US, "%-6s %4.1f%% whitened · %s · %3.1f%% dented",
                edge.side.label, edge.whitenedFraction * 100,
                resources.getQuantityString(R.plurals.wear_chips, edge.chips, edge.chips),
                edge.dentedFraction * 100,
            )
        }
    }

    /** Enlarged crops of each corner, labeled with its wear. */
    private fun showCornerTiles(card: Bitmap, lines: BorderLines, analysis: CornerEdgeAnalyzer.Result) {
        val density = resources.displayMetrics.density
        val size = (analysis.cornerRadius * TILE_RADII).roundToInt()
        val inset = (analysis.cornerRadius * TILE_OUTSIDE).roundToInt()
        val left = lines[BorderLine.OUTER_LEFT].roundToInt()
        val right = lines[BorderLine.OUTER_RIGHT].roundToInt()
        val top = lines[BorderLine.OUTER_TOP].roundToInt()
        val bottom = lines[BorderLine.OUTER_BOTTOM].roundToInt()
        binding.cornerTiles.removeAllViews()
        for (corner in analysis.corners) {
            val x = when (corner.corner) {
                CornerEdgeAnalyzer.Corner.TOP_LEFT, CornerEdgeAnalyzer.Corner.BOTTOM_LEFT -> left - inset
                else -> right + inset - size
            }.coerceIn(0, card.width - size)
            val y = when (corner.corner) {
                CornerEdgeAnalyzer.Corner.TOP_LEFT, CornerEdgeAnalyzer.Corner.TOP_RIGHT -> top - inset
                else -> bottom + inset - size
            }.coerceIn(0, card.height - size)
            val crop = Bitmap.createBitmap(card, x, y, size, size)

            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            tile.addView(ImageView(this).apply {
                setImageBitmap(crop)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams((TILE_DP * density).toInt(), (TILE_DP * density).toInt())
                contentDescription = corner.corner.label
            })
            tile.addView(TextView(this).apply {
                text = if (!corner.assessable) "${corner.corner.label}\n${getString(R.string.wear_not_assessable)}"
                else String.format(
                    Locale.US, "%s\n≈%s · %.2f mm*", corner.corner.label,
                    CornerEdgeAnalyzer.cornerGradeFor(corner).let { if (it <= 5) "≤5" else "$it" },
                    corner.roundingMm,
                )
                setTextColor(getColor(R.color.white))
                textSize = 11f
                gravity = Gravity.CENTER
            })
            binding.cornerTiles.addView(tile)
        }
    }

    private fun logResult(analysis: CornerEdgeAnalyzer.Result) {
        for (edge in analysis.edges) Log.i(TAG, "Edge %s: assessable=%b whitened=%.2f%% chips=%d dented=%.2f%%"
            .format(edge.side, edge.assessable, edge.whitenedFraction * 100, edge.chips, edge.dentedFraction * 100))
        for (corner in analysis.corners) Log.i(TAG, "Corner %s: assessable=%b rounding=%.2fmm whitened=%.1f%%"
            .format(corner.corner, corner.assessable, corner.roundingMm, corner.whitenedFraction * 100))
    }

    private fun finishWithSummary() {
        val analysis = result ?: return
        fun grade(value: Int?) = value?.let { if (it <= 5) "≈≤5" else "≈$it" } ?: "n/a"
        val summary = (intent.getStringExtra(EXTRA_CENTERING_SUMMARY) ?: "") +
            "\nCorners ${grade(analysis.cornerGrade)} · Edges ${grade(analysis.edgeGrade)}"
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SUMMARY, summary))
        finish()
    }

    companion object {
        private const val TAG = "CornersEdgesActivity"

        // Highlights: magenta for whitening, cyan for missing material — both
        // stand out on yellow, blue, silver and dark borders alike.
        private const val WHITENING_COLOR = 0xFFFF00FF.toInt()
        private const val MISSING_COLOR = 0xFF00FFFF.toInt()

        // Corner close-up: this many corner radii square, reaching this many
        // radii out past the card edge so the cut itself is in view.
        private const val TILE_RADII = 2.5
        private const val TILE_OUTSIDE = 0.4
        private const val TILE_DP = 80

        const val EXTRA_CARD_PATH = "extra_card_path"
        const val EXTRA_LINES = "extra_lines"
        const val EXTRA_SIDE = "extra_side"
        const val EXTRA_CENTERING_SUMMARY = "extra_centering_summary"
        const val EXTRA_SUMMARY = "extra_summary"
    }
}
