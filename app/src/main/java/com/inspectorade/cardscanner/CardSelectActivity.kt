package com.inspectorade.cardscanner

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.inspectorade.cardscanner.databinding.ActivityCardSelectBinding
import com.inspectorade.cardscanner.grading.CardFlattener
import com.inspectorade.cardscanner.grading.CardLayout
import com.inspectorade.cardscanner.identify.CardIdentifier
import com.inspectorade.cardscanner.identify.CardTextReader
import com.inspectorade.cardscanner.identify.TcgdexClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader

/**
 * "Which card is this?" — between Review and Centering for the front. Reads the
 * card's name and number, looks up the possible printings, and shows the user's
 * card beside the official image of whichever match is selected so they can
 * pick theirs (or search by name, or skip). The confirmed card decides the
 * centering layout and is passed on to [CenteringActivity].
 */
class CardSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCardSelectBinding
    private var matches: List<CardIdentifier.Match> = emptyList()
    private var selected: CardIdentifier.Match? = null
    private val thumbnails = mutableMapOf<String, Bitmap>()
    private val thumbViews = mutableMapOf<String, View>()
    private var officialJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCardSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.skipButton.setOnClickListener { launchCentering(null, CardLayout.STANDARD) }
        binding.confirmCardButton.setOnClickListener { confirmSelected() }
        binding.searchButton.setOnClickListener { search() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else false
        }

        val jpegUri = intent.getStringExtra(EXTRA_JPEG_URI)?.toUri()
        if (jpegUri == null || !OpenCVLoader.initLocal()) {
            Toast.makeText(this, R.string.centering_failed, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        lifecycleScope.launch {
            // The card alone, at 2× so the tiny collector number is readable.
            val card = try {
                withContext(Dispatchers.Default) {
                    val photo = CardFlattener.loadUpright(contentResolver, jpegUri)
                    try {
                        val flattened = CardFlattener.flatten(photo)
                        val corners = flattened.cornersInPhoto
                        if (corners == null) {
                            flattened.bitmap
                        } else {
                            flattened.bitmap.recycle()
                            CardFlattener.cardOnly(photo, corners, OCR_SCALE)
                        }
                    } finally {
                        photo.release()
                    }
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Couldn't prepare the card image", exc)
                binding.yourCardProgress.visibility = View.GONE
                binding.cardSelectStatus.setText(R.string.card_select_failed)
                return@launch
            }

            binding.yourCardProgress.visibility = View.GONE
            binding.yourCardImage.setImageBitmap(
                Bitmap.createScaledBitmap(card, DISPLAY_WIDTH, DISPLAY_WIDTH * card.height / card.width, true)
            )
            identify(card)
        }
    }

    private suspend fun identify(card: Bitmap) {
        binding.cardSelectStatus.setText(R.string.card_select_looking_up)
        val result = try {
            val reading = CardTextReader.read(card)
            Log.i(TAG, "Card text: names=${reading.nameCandidates} numbers=${reading.numberCandidates}")
            CardIdentifier.identify(reading)
        } catch (exc: Exception) {
            Log.w(TAG, "Card identification failed", exc)
            binding.cardSelectStatus.setText(R.string.card_select_offline)
            return
        } finally {
            card.recycle()
        }
        Log.i(TAG, "Card matches (confident=${result.confident}): ${result.matches.map { it.id }}")
        showMatches(result.matches, preselect = result.confident)
        binding.cardSelectStatus.text = when {
            result.matches.isEmpty() -> getString(R.string.card_select_no_match)
            result.confident -> getString(R.string.card_select_confident)
            else -> resources.getQuantityString(
                R.plurals.card_select_choose, result.matches.size, result.matches.size
            )
        }
    }

    private fun search() {
        val query = binding.searchInput.text.toString().trim()
        if (query.isEmpty()) return
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.searchInput.clearFocus()
        binding.cardSelectStatus.setText(R.string.card_select_searching)
        lifecycleScope.launch {
            val found = try {
                CardIdentifier.search(query)
            } catch (exc: Exception) {
                Log.w(TAG, "Card search failed", exc)
                binding.cardSelectStatus.setText(R.string.card_select_offline)
                return@launch
            }
            showMatches(found, preselect = found.size == 1)
            binding.cardSelectStatus.text = when (found.size) {
                0 -> getString(R.string.card_select_search_none, query)
                1 -> getString(R.string.card_select_confident)
                else -> resources.getQuantityString(R.plurals.card_select_choose, found.size, found.size)
            }
        }
    }

    /** Fills the candidate strip; thumbnails load in as they arrive. */
    private fun showMatches(found: List<CardIdentifier.Match>, preselect: Boolean) {
        matches = found
        thumbViews.clear()
        binding.candidateRow.removeAllViews()
        select(if (preselect) found.firstOrNull() else null)

        val density = resources.displayMetrics.density
        for (match in found) {
            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((THUMB_WIDTH_DP * density).toInt(), (THUMB_HEIGHT_DP * density).toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
                // A card-shaped slot, so tiles with no official image still read as tappable.
                background = GradientDrawable().apply {
                    cornerRadius = 4 * density
                    setColor(EMPTY_THUMB_COLOR)
                }
                thumbnails[match.id]?.let(::setImageBitmap)
            }
            val caption = TextView(this).apply {
                text = getString(R.string.card_select_thumb_caption, match.setName, match.localId)
                setTextColor(getColor(R.color.white))
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 3
                width = (THUMB_WIDTH_DP * density).toInt()
            }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = (4 * density).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(pad, 0, pad, 0) }
                contentDescription = match.label
                addView(image)
                addView(caption)
                setOnClickListener { select(match) }
            }
            thumbViews[match.id] = tile
            binding.candidateRow.addView(tile)

            if (match.id !in thumbnails && match.imageBase != null) {
                lifecycleScope.launch {
                    val thumb = runCatching { TcgdexClient.image(match.imageBase) }.getOrNull() ?: return@launch
                    thumbnails[match.id] = thumb
                    image.setImageBitmap(thumb)
                }
            }
        }
        highlightSelected()
        binding.candidateScroll.post { binding.candidateScroll.scrollTo(0, 0) }
    }

    private fun select(match: CardIdentifier.Match?) {
        selected = match
        binding.confirmCardButton.isEnabled = match != null
        // The tinted background doesn't dim on its own when disabled.
        binding.confirmCardButton.alpha = if (match != null) 1f else DISABLED_ALPHA
        binding.selectedLabel.text = match?.label ?: ""
        highlightSelected()

        officialJob?.cancel()
        binding.officialImage.setImageDrawable(null)
        binding.officialProgress.visibility = View.GONE
        // TCGdex has no scan for some cards (e.g. Aquapolis 103a).
        binding.officialMissing.visibility =
            if (match != null && match.imageBase == null) View.VISIBLE else View.GONE
        val imageBase = match?.imageBase ?: return
        thumbnails[match.id]?.let(binding.officialImage::setImageBitmap)
        binding.officialProgress.visibility = View.VISIBLE
        officialJob = lifecycleScope.launch {
            val high = runCatching { TcgdexClient.image(imageBase, quality = "high") }.getOrNull()
            binding.officialProgress.visibility = View.GONE
            if (high != null) binding.officialImage.setImageBitmap(high)
        }
    }

    private fun highlightSelected() {
        val density = resources.displayMetrics.density
        for ((id, view) in thumbViews) {
            view.background = if (id == selected?.id) {
                GradientDrawable().apply {
                    cornerRadius = 6 * density
                    setStroke((3 * density).toInt(), getColor(R.color.accent))
                }
            } else null
        }
    }

    private fun confirmSelected() {
        val match = selected ?: return
        binding.confirmCardButton.isEnabled = false
        lifecycleScope.launch {
            val layout = CardIdentifier.layoutFor(match)
            binding.confirmCardButton.isEnabled = true
            launchCentering(match, layout)
        }
    }

    private fun launchCentering(match: CardIdentifier.Match?, layout: CardLayout) {
        val intent = Intent(this, CenteringActivity::class.java)
            .putExtra(CenteringActivity.EXTRA_JPEG_URI, getIntent().getStringExtra(EXTRA_JPEG_URI))
            .putExtra(CenteringActivity.EXTRA_SIDE, getIntent().getStringExtra(EXTRA_SIDE))
            .putExtra(CenteringActivity.EXTRA_LAYOUT, layout.name)
        if (match != null) {
            intent.putExtra(CenteringActivity.EXTRA_CARD_ID, match.id)
                .putExtra(CenteringActivity.EXTRA_CARD_LABEL, match.label)
                .putExtra(CenteringActivity.EXTRA_CARD_IMAGE, match.imageBase)
        }
        centeringLauncher.launch(intent)
    }

    // Backing out of centering returns here so the user can pick a different card.
    private val centeringLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                setResult(Activity.RESULT_OK, result.data)
                finish()
            }
        }

    companion object {
        private const val TAG = "CardSelectActivity"
        private const val OCR_SCALE = 2
        private const val DISPLAY_WIDTH = 600
        private const val THUMB_WIDTH_DP = 64
        private const val THUMB_HEIGHT_DP = 89
        private const val DISABLED_ALPHA = 0.4f
        private const val EMPTY_THUMB_COLOR = 0xFF2A2A2A.toInt()

        const val EXTRA_JPEG_URI = "extra_jpeg_uri"
        const val EXTRA_SIDE = "extra_side"
    }
}
