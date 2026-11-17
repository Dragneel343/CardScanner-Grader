package com.inspectorade.cardscanner

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.CheckBox
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
import com.inspectorade.cardscanner.grading.BorderLine
import com.inspectorade.cardscanner.grading.BorderLines
import com.inspectorade.cardscanner.grading.CardFlattener
import com.inspectorade.cardscanner.grading.CardLayout
import com.inspectorade.cardscanner.grading.CenteringReading
import com.inspectorade.cardscanner.grading.CenteringStandards
import com.inspectorade.cardscanner.grading.LineStyle
import com.inspectorade.cardscanner.grading.ReferenceGuide
import com.inspectorade.cardscanner.identify.CardTextReader
import com.inspectorade.cardscanner.identify.TcgdexClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
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
    private var upsideDown = false
    private var layout = CardLayout.STANDARD
    private var cardLabel: String? = null

    // The identified card's official image (high quality), if it could be downloaded.
    @Volatile private var reference: Bitmap? = null

    // A problem with the photo ("card not found", "skewed", "too small"), shown
    // in the hint whatever the layout.
    private var warning: String? = null

    // Line colors chosen for this card's border and background (see LineStyle.autoColors).
    private var autoColors: Pair<Int, Int>? = null

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
        upsideDown = intent.getBooleanExtra(EXTRA_UPSIDE_DOWN, false)
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

        // Downloads while the photo is flattened; guides the inner lines if it arrives.
        val referenceImage = intent.getStringExtra(EXTRA_CARD_IMAGE)?.let { imageBase ->
            lifecycleScope.async { runCatching { TcgdexClient.image(imageBase, quality = "high") }.getOrNull() }
        }

        lifecycleScope.launch {
            val analysis = try {
                val flattened = withContext(Dispatchers.Default) {
                    val photo = CardFlattener.loadUpright(contentResolver, jpegUri)
                    val result = try {
                        CardFlattener.flatten(photo).also { if (isDebuggable()) saveDebugImages(photo, it) }
                    } finally {
                        photo.release()
                    }
                    if (upsideDown) turnedAround(result) else result
                }
                reference = referenceImage?.await()
                val oriented = withContext(Dispatchers.Default) { orientedByReference(flattened) }
                oriented to withContext(Dispatchers.Default) { detectLines(oriented.bitmap, initialLayout) }
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
            Log.i(TAG, "Card sides off parallel %.2f°".format(flattened.sideMismatchDegrees))
            warning = when {
                !flattened.cardFound -> getString(R.string.centering_card_not_found)
                flattened.sideMismatchDegrees > MAX_SIDE_MISMATCH_DEGREES -> getString(R.string.centering_card_skewed)
                sourceHeight != null && sourceHeight < MIN_ACCURATE_CARD_HEIGHT_PX ->
                    getString(R.string.centering_card_too_small)
                else -> null
            }
            applyLayout(initialLayout)
            updateAutoColors(flattened.bitmap, lines)
        }
    }

    /**
     * The card was photographed upside down (card selection read its text better
     * turned around): turn the flattened card 180° so top, bottom, left and right
     * are the card's own. The margin is the same on every side, so it stays put.
     */
    private fun turnedAround(result: CardFlattener.Result): CardFlattener.Result {
        val turned = CardTextReader.rotate180(result.bitmap)
        result.bitmap.recycle()
        return CardFlattener.Result(
            turned, result.cardFound, result.sourceCardHeight, result.cornersInPhoto,
            result.roughCorners, result.sideMismatchDegrees,
        )
    }

    /**
     * The final say on orientation, when the selected card has an official image:
     * if the flattened card matches the image better turned around, turn it. This
     * corrects the text-based guess from card selection either way.
     */
    private fun orientedByReference(result: CardFlattener.Result): CardFlattener.Result {
        val reference = reference ?: return result
        if (!result.cardFound) return result
        val upsideDown = runCatching { ReferenceGuide.isUpsideDown(result.bitmap, CardFlattener.MARGIN, reference) }
            .onFailure { Log.w(TAG, "Orientation check failed", it) }.getOrNull()
        return if (upsideDown == true) turnedAround(result) else result
    }

    private fun isDebuggable() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Debug builds only: saves the photo with the card outline drawn on it (red =
     * detector's corners, green = after edge refinement) and the flattened card,
     * to Android/data/<package>/files/debug, for diagnosing flattening problems.
     */
    private fun saveDebugImages(photo: Mat, result: CardFlattener.Result) {
        runCatching {
            val dir = getExternalFilesDir("debug") ?: return
            val side = if (isFront) "front" else "back"
            val scale = DEBUG_PHOTO_MAX_DIM / maxOf(photo.cols(), photo.rows())
            val small = Mat()
            Imgproc.resize(photo, small, Size(), scale, scale, Imgproc.INTER_AREA)
            fun outline(corners: Array<Point>?, color: Scalar) {
                val points = corners?.map { Point(it.x * scale, it.y * scale) } ?: return
                for (i in points.indices) Imgproc.line(small, points[i], points[(i + 1) % points.size], color, 2)
            }
            outline(result.roughCorners, Scalar(255.0, 0.0, 0.0, 255.0))
            outline(result.cornersInPhoto, Scalar(0.0, 255.0, 0.0, 255.0))
            val annotated = Bitmap.createBitmap(small.cols(), small.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(small, annotated)
            small.release()
            File(dir, "${side}_photo.jpg").outputStream().use { annotated.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            annotated.recycle()
            File(dir, "${side}_flattened.png").outputStream().use { result.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fun Array<Point>?.describe() = this?.joinToString(" ") { "(%.0f,%.0f)".format(it.x, it.y) } ?: "none"
            File(dir, "${side}_corners.txt").writeText(
                "photo ${photo.cols()}x${photo.rows()}\nrough ${result.roughCorners.describe()}\n" +
                    "final ${result.cornersInPhoto.describe()}\n"
            )
        }.onFailure { Log.w(TAG, "Couldn't save debug images", it) }
    }

    /** Border lines from the photo alone, refined by the official image when there is one. */
    private fun detectLines(bitmap: Bitmap, layout: CardLayout): BorderLines {
        val detected = BorderDetector.detect(bitmap, CardFlattener.MARGIN, layout)
        val reference = reference ?: return detected
        val guided = runCatching {
            ReferenceGuide.guide(bitmap, CardFlattener.MARGIN, reference, layout, detected)
        }.onFailure { Log.w(TAG, "Reference guide failed", it) }.getOrNull()
        Log.i(TAG, "Inner lines: detected ${detected.innerSummary()} guided ${guided?.innerSummary()}")
        return guided ?: detected
    }

    private fun BorderLines.innerSummary() = BorderLine.entries.filter { !it.outer }
        .joinToString(",") { "${it.name.removePrefix("INNER_").first()}=${this[it].toInt()}" }

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

    /**
     * Where the inner lines belong depends on the layout (which sides have a
     * dot-code strip to look past), so switching re-detects them — replacing any
     * manual adjustments, as Reset would.
     */
    private fun cycleLayout() {
        val all = CardLayout.entries
        val newLayout = all[(all.indexOf(layout) + 1) % all.size]
        val bitmap = currentBitmap ?: return
        binding.layoutButton.isEnabled = false
        lifecycleScope.launch {
            val lines = withContext(Dispatchers.Default) { detectLines(bitmap, newLayout) }
            detectedLines = lines
            binding.centeringView.setContent(bitmap, lines.copy())
            binding.layoutButton.isEnabled = true
            applyLayout(newLayout)
            updateAutoColors(bitmap, lines)
        }
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
        val layoutHint = getString(
            when (layout) {
                CardLayout.STANDARD -> R.string.centering_hint
                CardLayout.E_READER -> R.string.centering_e_reader_hint
                CardLayout.EX_DOT_CODE -> R.string.centering_ex_dot_code_hint
            }
        )
        // A warning replaces the general instructions but sits above a layout's
        // own explanation, which still matters.
        binding.hintText.text = when {
            warning == null -> layoutHint
            layout == CardLayout.STANDARD -> warning
            else -> "$warning\n$layoutHint"
        }
        updateReadout()
    }

    /** Auto-picked colors for this card when Auto is on (and they're ready), else the user's saved ones. */
    private fun shownColors(): Pair<Int, Int> =
        autoColors?.takeIf { LineStyle.isAuto(this) } ?: (LineStyle.outerColor(this) to LineStyle.innerColor(this))

    /** Picks colors for this card's lines and shows them, if Auto is on. */
    private suspend fun updateAutoColors(bitmap: Bitmap, lines: BorderLines) {
        autoColors = withContext(Dispatchers.Default) { LineStyle.autoColors(bitmap, CardFlattener.MARGIN, lines) }
        val (outer, inner) = shownColors()
        binding.centeringView.setLineColors(outer, inner)
    }

    private fun showLineColorsDialog() {
        var (outer, inner) = shownColors()
        val padding = (20 * resources.displayMetrics.density).toInt()
        val refreshers = mutableListOf<() -> Unit>()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        fun show() {
            binding.centeringView.setLineColors(outer, inner)
            refreshers.forEach { it() }
        }
        val auto = CheckBox(this).apply {
            setText(R.string.line_colors_auto)
            isChecked = LineStyle.isAuto(this@CenteringActivity)
            setOnCheckedChangeListener { _, checked ->
                LineStyle.setAuto(this@CenteringActivity, checked)
                shownColors().let { outer = it.first; inner = it.second }
                show()
            }
        }
        // Choosing a color by hand turns Auto off and keeps the choice.
        fun pick() {
            LineStyle.save(this, outer, inner)
            auto.isChecked = false
            show()
        }
        content.addView(auto)
        content.addView(sectionLabel(R.string.line_colors_outer))
        content.addView(swatchGrid({ outer }, refreshers) { outer = it; pick() })
        content.addView(sectionLabel(R.string.line_colors_inner))
        content.addView(swatchGrid({ inner }, refreshers) { inner = it; pick() })

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

    /**
     * Color swatches in a grid; the selected one gets a thick ring. Named for
     * TalkBack. Its refresh is added to [refreshers] so the dialog can redraw
     * the rings when the selection changes from outside (Auto toggled).
     */
    private fun swatchGrid(selected: () -> Int, refreshers: MutableList<() -> Unit>, onPick: (Int) -> Unit): GridLayout {
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
        refreshers += ::refresh
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
            Locale.US, "%s%s%s %.0f/%.0f · %s %.0f/%.0f%s · PSA %s",
            cardLabel?.let { "$it\n" } ?: "",
            when (layout) {
                CardLayout.STANDARD -> ""
                CardLayout.E_READER -> "e-Reader · "
                CardLayout.EX_DOT_CODE -> "EX dot-code · "
            },
            reading.firstLabel, reading.firstShare, 100 - reading.firstShare,
            reading.secondLabel, reading.secondShare, 100 - reading.secondShare,
            // Shown but not graded (EX dot-code top/bottom), as on the centering screen.
            if (reading.secondGraded) "" else "*",
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

        // Opposite sides of the card's outline in the photo further from parallel
        // than this means a corner was misplaced. A good outline is ~0.5°; the bad
        // one that prompted this check was 4.6°.
        private const val MAX_SIDE_MISMATCH_DEGREES = 1.5

        private const val DEBUG_PHOTO_MAX_DIM = 1600.0

        const val EXTRA_JPEG_URI = "extra_jpeg_uri"
        const val EXTRA_SIDE = "extra_side"
        const val EXTRA_CENTERING_SUMMARY = "extra_centering_summary"
        const val EXTRA_LAYOUT = "extra_layout"
        const val EXTRA_UPSIDE_DOWN = "extra_upside_down"
        const val EXTRA_CARD_ID = "extra_card_id"
        const val EXTRA_CARD_LABEL = "extra_card_label"
        const val EXTRA_CARD_IMAGE = "extra_card_image"
    }
}
