package com.inspectorade.cardscanner

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.inspectorade.cardscanner.databinding.ActivityCenteringBinding
import com.inspectorade.cardscanner.grading.BorderDetector
import com.inspectorade.cardscanner.grading.BorderLines
import com.inspectorade.cardscanner.grading.CardFlattener
import com.inspectorade.cardscanner.grading.CardLayout
import com.inspectorade.cardscanner.grading.CenteringReading
import com.inspectorade.cardscanner.grading.CenteringStandards
import com.inspectorade.cardscanner.grading.LineStyle
import com.inspectorade.cardscanner.identify.TcgdexClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.util.Locale

/**
 * Flattens the confirmed capture, auto-places the outer/inner border lines, and
 * lets the user refine them while live centering ratios and per-grader centering
 * grades update. The card (if identified on [CardSelectActivity]) and its layout
 * arrive as extras. Done returns a one-line summary to the caller.
 */
class CenteringActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCenteringBinding
    private var detectedLines: BorderLines? = null
    private var currentBitmap: Bitmap? = null
    private var isFront = true
    private var layout = CardLayout.STANDARD
    private var cardLabel: String? = null

    // Whatever the hint said before switching to an e-Reader layout (it may be a
    // "card not found"/"too small" warning), restored when switching back.
    private var standardHint: CharSequence = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCenteringBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        isFront = (intent.getStringExtra(EXTRA_SIDE) ?: CaptureActivity.SIDE_FRONT) == CaptureActivity.SIDE_FRONT
        binding.centeringTitle.text = getString(
            if (isFront) R.string.centering_title_front else R.string.centering_title_back
        )
        val initialLayout = intent.getStringExtra(EXTRA_LAYOUT)
            ?.let { name -> CardLayout.entries.firstOrNull { it.name == name } }
            ?: CardLayout.STANDARD
        showIdentifiedCard()

        binding.centeringView.onLinesChanged = ::updateReadout
        binding.resetLinesButton.setOnClickListener {
            val lines = detectedLines ?: return@setOnClickListener
            val bitmap = currentBitmap ?: return@setOnClickListener
            binding.centeringView.setContent(bitmap, lines.copy())
            updateReadout()
        }
        binding.centeringDoneButton.setOnClickListener { finishWithSummary() }
        binding.lineColorsButton.setOnClickListener { showLineColorsDialog() }
        binding.layoutButton.setOnClickListener { cycleLayout() }

        val jpegUri = intent.getStringExtra(EXTRA_JPEG_URI)?.toUri()
        if (jpegUri == null || !OpenCVLoader.initLocal()) {
            Toast.makeText(this, R.string.centering_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val analysis = try {
                withContext(Dispatchers.Default) {
                    val photo = CardFlattener.loadUpright(contentResolver, jpegUri)
                    try {
                        val flattened = CardFlattener.flatten(photo)
                        flattened to BorderDetector.detect(flattened.bitmap, CardFlattener.MARGIN)
                    } finally {
                        photo.release()
                    }
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Centering analysis failed", exc)
                Toast.makeText(this@CenteringActivity, R.string.centering_failed, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            val (flattened, lines) = analysis
            currentBitmap = flattened.bitmap
            detectedLines = lines
            binding.centeringProgress.visibility = View.GONE
            binding.centeringView.setContent(flattened.bitmap, lines.copy())
            binding.centeringDoneButton.isEnabled = true
            binding.layoutButton.isEnabled = true
            val sourceHeight = flattened.sourceCardHeight
            if (!flattened.cardFound) {
                binding.hintText.setText(R.string.centering_card_not_found)
            } else if (sourceHeight != null && sourceHeight < MIN_ACCURATE_CARD_HEIGHT_PX) {
                binding.hintText.setText(R.string.centering_card_too_small)
            }
            standardHint = binding.hintText.text
            applyLayout(initialLayout)
        }
    }

    /** The card chosen on the previous screen, with its official thumbnail. */
    private fun showIdentifiedCard() {
        cardLabel = intent.getStringExtra(EXTRA_CARD_LABEL) ?: return
        binding.cardIdentityText.text = getString(R.string.identify_result, cardLabel)
        binding.cardIdentityText.visibility = View.VISIBLE
        val imageBase = intent.getStringExtra(EXTRA_CARD_IMAGE) ?: return
        lifecycleScope.launch {
            val thumb = runCatching { TcgdexClient.image(imageBase) }.getOrNull() ?: return@launch
            binding.referenceThumb.setImageBitmap(thumb)
            binding.referenceThumb.visibility = View.VISIBLE
        }
    }

    private fun updateReadout() {
        val reading = binding.centeringView.currentLines()?.reading(layout) ?: return
        binding.ratioText.text = String.format(
            Locale.US, "%s %.1f / %.1f    %s %.1f / %.1f%s",
            reading.firstLabel, reading.firstShare, 100 - reading.firstShare,
            reading.secondLabel, reading.secondShare, 100 - reading.secondShare,
            if (reading.secondGraded) "" else "*",
        )
        binding.gradeText.text = gradeSummary(reading)
    }

    private fun cycleLayout() {
        val all = CardLayout.entries
        applyLayout(all[(all.indexOf(layout) + 1) % all.size])
    }

    private fun applyLayout(newLayout: CardLayout) {
        layout = newLayout
        binding.layoutButton.setText(
            when (layout) {
                CardLayout.STANDARD -> R.string.layout_standard
                CardLayout.E_READER -> R.string.layout_e_reader
                CardLayout.EX_DOT_CODE -> R.string.layout_ex_dot_code
            }
        )
        when (layout) {
            CardLayout.STANDARD -> binding.hintText.text = standardHint
            CardLayout.E_READER -> binding.hintText.setText(R.string.centering_e_reader_hint)
            CardLayout.EX_DOT_CODE -> binding.hintText.setText(R.string.centering_ex_dot_code_hint)
        }
        updateReadout()
    }

    private fun showLineColorsDialog() {
        var outer = LineStyle.outerColor(this)
        var inner = LineStyle.innerColor(this)
        val padding = (20 * resources.displayMetrics.density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        fun apply() {
            LineStyle.save(this, outer, inner)
            binding.centeringView.setLineColors(outer, inner)
        }
        content.addView(sectionLabel(R.string.line_colors_outer))
        content.addView(swatchGrid({ outer }) { outer = it; apply() })
        content.addView(sectionLabel(R.string.line_colors_inner))
        content.addView(swatchGrid({ inner }) { inner = it; apply() })

        AlertDialog.Builder(this)
            .setTitle(R.string.line_colors_title)
            .setView(content)
            .setPositiveButton(R.string.line_colors_done, null)
            .show()
    }

    private fun sectionLabel(text: Int) = TextView(this).apply {
        setText(text)
        setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
    }

    /** Color swatches in a grid; the selected one gets a thick ring. Named for TalkBack. */
    private fun swatchGrid(selected: () -> Int, onPick: (Int) -> Unit): GridLayout {
        val density = resources.displayMetrics.density
        val size = (36 * density).toInt()
        val gap = (6 * density).toInt()
        val grid = GridLayout(this).apply { columnCount = 5 }
        val swatches = mutableListOf<Pair<View, LineStyle.Swatch>>()

        fun refresh() {
            for ((view, swatch) in swatches) {
                val isSelected = swatch.color == selected()
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(swatch.color)
                    setStroke(
                        ((if (isSelected) 4 else 1) * density).toInt(),
                        if (isSelected) getColor(R.color.accent) else Color.GRAY,
                    )
                }
                view.contentDescription =
                    if (isSelected) getString(R.string.line_color_selected, swatch.name) else swatch.name
            }
        }

        for (swatch in LineStyle.palette) {
            val view = View(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(gap, gap, gap, gap)
                }
                setOnClickListener {
                    onPick(swatch.color)
                    refresh()
                }
            }
            swatches += view to swatch
            grid.addView(view)
        }
        refresh()
        return grid
    }

    private fun gradeSummary(reading: CenteringReading): String =
        CenteringStandards.grade(reading.worstShare, isFront).joinToString("   ") {
            "${it.grader} ${if (it.verified) "" else "≈"}${it.grade}"
        }

    private fun finishWithSummary() {
        val reading = binding.centeringView.currentLines()?.reading(layout) ?: return
        val psa = CenteringStandards.grade(reading.worstShare, isFront).first { it.grader == "PSA" }
        val summary = String.format(
            Locale.US, "%s%s%s %.0f/%.0f · %s %.0f/%.0f · PSA %s",
            cardLabel?.let { "$it\n" } ?: "",
            when (layout) {
                CardLayout.STANDARD -> ""
                CardLayout.E_READER -> "e-Reader · "
                CardLayout.EX_DOT_CODE -> "EX dot-code · "
            },
            reading.firstLabel, reading.firstShare, 100 - reading.firstShare,
            reading.secondLabel, reading.secondShare, 100 - reading.secondShare,
            psa.grade,
        )
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CENTERING_SUMMARY, summary))
        finish()
    }

    companion object {
        private const val TAG = "CenteringActivity"

        // Below this, a ~4% border is under ~50 source pixels wide, so a single
        // pixel of edge error moves the ratio by a percent or more.
        private const val MIN_ACCURATE_CARD_HEIGHT_PX = 1200.0

        const val EXTRA_JPEG_URI = "extra_jpeg_uri"
        const val EXTRA_SIDE = "extra_side"
        const val EXTRA_CENTERING_SUMMARY = "extra_centering_summary"
        const val EXTRA_LAYOUT = "extra_layout"
        const val EXTRA_CARD_ID = "extra_card_id"
        const val EXTRA_CARD_LABEL = "extra_card_label"
        const val EXTRA_CARD_IMAGE = "extra_card_image"
    }
}
