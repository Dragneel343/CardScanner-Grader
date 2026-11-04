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

    suspend fun read(card: Bitmap): Reading {
        val text = recognize(card)
        val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        val height = card.height

        val bottomLines = lines.filter { it.boundingBox!!.top >= height * NUMBER_BAND }
        Log.d(TAG, "Bottom-band text: ${bottomLines.map { it.text }}")
        // The collector number is usually on the lowest line, so search bottom-up.
        val lowestFirst = bottomLines.sortedByDescending { it.boundingBox!!.top }
        val numbers = lowestFirst.flatMap { line -> collectorNumber.findAll(line.text).map { it.groupValues[1] } } +
            lowestFirst.flatMap { line ->
                runOnNumber.findAll(line.text)
                    .map { it.groupValues[1] }
                    .filter { it.length != 4 || it.toInt() !in copyrightYears }
                    .flatMap { run -> (3 downTo 1).map { run.take(it) } }
            }

        val names = lines
            .filter { it.boundingBox!!.bottom <= height * NAME_BAND }
            .sortedByDescending { it.boundingBox!!.height() }
            .map { cleanName(it.text) }
            .filter { it.length >= 3 }
            .distinct()
            .take(MAX_NAME_CANDIDATES)

        return Reading(nameCandidates = names, numberCandidates = numbers.distinct().take(MAX_NUMBER_CANDIDATES))
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
