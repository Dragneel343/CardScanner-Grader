package com.inspectorade.cardscanner.identify

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads the text that identifies a card — its name and collector number — from a flattened, upright card image, using on-device ML Kit text
 * recognition (offline, free).
 */
object CardTextReader {

    data class Reading(
        /** Most likely first: the largest lines of text near the top of the card. */
        val nameCandidates: List<String>,
        /**
         * Possible collector numbers (leading digits only, e.g. "38" from "38/97"
         * or "103" from "103a/147"), most likely first. Several, because the bottom
         * band holds other codes too; the lookup keeps the first one that exists
         * for the card's name.
         */
        val numberCandidates: List<String>,
        /** Raw text of the bottom band's lines, for fuzzy matching a misread number. */
        val bottomLines: List<String> = emptyList(),
    )

    private const val TAG = "CardTextReader"

    // Just the leading digits, ending where a "/" or letter starts. Everything
    // after is unreliable tiny print ("103a/147" came back as "103al147"); the
    // user confirms the printing from the matches instead. Up to 3 digits, so a
    // copyright year ("2002") never qualifies.
    private val collectorNumber = Regex("""(?<!\d)(\d{1,3})(?=\s*[/|\p{L}])""")

    // A number whose slash OCR dropped altogether ("38/97" read as "3897"): the
    // split point is unknown, so each leading 3, 2 and 1 digits are candidates.
    private val runOnNumber = Regex("""(?<!\d)(\d{4,6})(?!\d)""")
    private val copyrightYears = 1995..2035

    // Card names live in the top band; collector numbers in the bottom band.
    private const val NAME_BAND = 0.18
    private const val NUMBER_BAND = 0.80
    private const val MAX_NAME_CANDIDATES = 3
    private const val MAX_NUMBER_CANDIDATES = 6

    // Where text sits tells which way up the card is: the recognizer returns even
    // upside-down text the right way round ("70HP"), but on an upside-down card it
    // sits in the bottom band of the image instead of the top.
    private val hpText = Regex("""\d+\s*HP""", RegexOption.IGNORE_CASE)
    private val footerText = Regex("""©|Pok[eé]mon|Nintendo|Illus""", RegexOption.IGNORE_CASE)

    private class Scan(val lines: List<Text.Line>, val height: Int) {
        fun top() = lines.filter { it.boundingBox!!.bottom <= height * NAME_BAND }
        fun bottom() = lines.filter { it.boundingBox!!.top >= height * NUMBER_BAND }
    }

    /**
     * Reads [card] both ways up. Returns the reading and whether the card was
     * photographed upside down (its image needs turning 180° to be upright).
     */
    suspend fun readEitherWayUp(card: Bitmap): Pair<Reading, Boolean> {
        val upright = scan(card)
        val turnedCard = rotate180(card)
        try {
            val turned = scan(turnedCard)
            val uprightScore = orientationScore(upright)
            val turnedScore = orientationScore(turned)
            Log.d(TAG, "Orientation score upright $uprightScore, turned $turnedScore")
            return if (turnedScore > uprightScore) reading(turnedCard, turned) to true
            else reading(card, upright) to false
        } finally {
            turnedCard.recycle()
        }
    }

    suspend fun read(card: Bitmap): Reading = reading(card, scan(card))

    fun rotate180(bitmap: Bitmap): Bitmap =
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(180f) }, true)

    /** HP belongs in the top band and the copyright/illustrator footer in the bottom one. */
    private fun orientationScore(scan: Scan): Int {
        fun List<Text.Line>.has(pattern: Regex) = any { pattern.containsMatchIn(it.text) }
        val top = scan.top()
        val bottom = scan.bottom()
        return (if (top.has(hpText)) 2 else 0) - (if (bottom.has(hpText)) 2 else 0) +
            (if (bottom.has(footerText)) 1 else 0) - (if (top.has(footerText)) 1 else 0)
    }

    // Grayscale, contrast doubled around mid-gray.
    private val highContrast = ColorMatrix().apply {
        setSaturation(0f)
        postConcat(ColorMatrix(floatArrayOf(
            2f, 0f, 0f, 0f, -128f,
            0f, 2f, 0f, 0f, -128f,
            0f, 0f, 2f, 0f, -128f,
            0f, 0f, 0f, 1f, 0f,
        )))
    }

    // Grayscale negative.
    private val inverted = ColorMatrix().apply {
        setSaturation(0f)
        postConcat(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )))
    }

    /** Text lines in [bitmap], optionally recolored by [filter] first. */
    private suspend fun linesOf(bitmap: Bitmap, filter: ColorMatrix? = null): List<Text.Line> {
        val source = filter?.let { matrix ->
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(bitmap, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
            }
        } ?: bitmap
        try {
            return recognize(source).textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        } finally {
            if (source !== bitmap) source.recycle()
        }
    }

    private suspend fun scan(bitmap: Bitmap) =
        Scan(recognize(bitmap).textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }, bitmap.height)

    private suspend fun reading(card: Bitmap, scan: Scan): Reading {
        // The recognizer scales big images down, which loses the collector
        // number's tiny print; read the bottom band on its own as well, at full
        // detail, and trust that read first.
        val bandTop = (card.height * NUMBER_BAND).toInt()
        val band = Bitmap.createBitmap(card, 0, bandTop, card.width, card.height - bandTop)
        val bandLines = try {
            val plain = linesOf(band)
            // Nothing number-like at full detail: try the band boosted to high
            // contrast (faint print, glare), then inverted (light text on a dark
            // border, as on some full-art cards).
            if (plain.any { collectorNumber.containsMatchIn(it.text) }) plain
            else plain + linesOf(band, highContrast) + linesOf(band, inverted)
        } finally {
            band.recycle()
        }
        // The collector number is usually on the lowest line, so search bottom-up.
        val bottomTexts = (bandLines.sortedByDescending { it.boundingBox!!.top } +
            scan.bottom().sortedByDescending { it.boundingBox!!.top }).map { it.text }
        Log.d(TAG, "Bottom-band text: $bottomTexts")

        val numbers = bottomTexts.flatMap { text -> collectorNumber.findAll(text).map { it.groupValues[1] } } +
            bottomTexts.flatMap { text ->
                runOnNumber.findAll(text)
                    .map { it.groupValues[1] }
                    .filter { it.length != 4 || it.toInt() !in copyrightYears }
                    .flatMap { run -> (3 downTo 1).map { run.take(it) } }
            }

        val names = scan.top()
            .sortedByDescending { it.boundingBox!!.height() }
            .map { cleanName(it.text) }
            .filter { it.length >= 3 && !stageLabel.matches(it) }
            .flatMap { name -> listOfNotNull(megaSpaced(name), name) }
            .distinct()
            .take(MAX_NAME_CANDIDATES)

        return Reading(
            nameCandidates = names,
            numberCandidates = numbers.distinct().take(MAX_NUMBER_CANDIDATES),
            bottomLines = bottomTexts,
        )
    }

    // Labels printed in the top band that aren't the card's name — often as large
    // as it ("MEGA" on a Mega card), and a name search matches substrings, so
    // "MEGA" alone found Meganium.
    private val stageLabel = Regex(
        """(basic|stage\s*\d|mega|break|restored|baby|lv\.?\s*x|level\s*up|trainer|item|supporter|stadium|""" +
            """pok[eé]mon tool|energy|evolves from.*|put .* on the .*)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * XY-era Mega cards print the name as a big "M" against the rest ("MGallade
     * EX"), which reads without the space TCGdex has ("M Gallade EX"); null if
     * [name] isn't one.
     */
    private fun megaSpaced(name: String): String? =
        Regex("""^M(\p{Lu}\p{Ll}.*)""").find(name)?.let { "M ${it.groupValues[1]}" }

    /** Drops HP values, energy-symbol noise and stray punctuation around the name. */
    private fun cleanName(raw: String): String =
        raw.replace(Regex("""\b\d+\s*HP\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[^\p{L}\s'.\-]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private suspend fun recognize(bitmap: Bitmap): Text = suspendCancellableCoroutine { continuation ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resumeWithException(it) }
            .addOnCompleteListener { recognizer.close() }
    }
}
