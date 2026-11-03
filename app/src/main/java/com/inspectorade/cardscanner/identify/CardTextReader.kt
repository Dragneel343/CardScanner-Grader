package com.inspectorade.cardscanner.identify

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads the text that identifies a card — its name and collector number (e.g.
 * "38/97") — from a flattened, upright card image, using on-device ML Kit text
 * recognition (offline, free).
 */
object CardTextReader {

    data class Reading(
        /** Most likely first: the largest lines of text near the top of the card. */
        val nameCandidates: List<String>,
        /** Collector number as printed, e.g. "38", "017" or "103a". */
        val number: String?,
        /** The "/97" part — the set's official card count — when printed. */
        val setTotal: Int?,
    )

    private const val TAG = "CardTextReader"

    // Some e-Card numbers carry a letter for alternate printings ("103a/147").
    // The slash is tiny print and OCR often reads it as l, I or | ("103al147").
    private val collectorNumber = Regex("""(\d{1,3}[ab]?)\s*[/|lI]\s*(\d{1,3})""")

    // Card names live in the top band; collector numbers in the bottom band.
    private const val NAME_BAND = 0.18
    private const val NUMBER_BAND = 0.80
    private const val MAX_NAME_CANDIDATES = 3

    suspend fun read(card: Bitmap): Reading {
        val text = recognize(card)
        val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        val height = card.height

        val bottomLines = lines.filter { it.boundingBox!!.top >= height * NUMBER_BAND }
        Log.d(TAG, "Bottom-band text: ${bottomLines.map { it.text }}")
        val number = bottomLines
            .mapNotNull { line -> collectorNumber.find(line.text)?.let { line to it } }
            .maxByOrNull { (line, _) -> line.boundingBox!!.top }
            ?.second

        val names = lines
            .filter { it.boundingBox!!.bottom <= height * NAME_BAND }
            .sortedByDescending { it.boundingBox!!.height() }
            .map { cleanName(it.text) }
            .filter { it.length >= 3 }
            .distinct()
            .take(MAX_NAME_CANDIDATES)

        return Reading(
            nameCandidates = names,
            number = number?.groupValues?.get(1),
            setTotal = number?.groupValues?.get(2)?.toIntOrNull(),
        )
    }

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
