package com.inspectorade.cardscanner

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inspectorade.cardscanner.grading.CardFlattener
import com.inspectorade.cardscanner.identify.CardIdentifier
import com.inspectorade.cardscanner.identify.CardTextReader
import com.inspectorade.cardscanner.identify.CardImages
import com.inspectorade.cardscanner.identify.VisualMatch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.io.File

/**
 * Runs card identification as card selection does — text read off the card,
 * TCGdex lookup, and the artwork check on a confident match — for every front
 * photo in the app's `fixtures/` folder (see [FixturePipelineTest]): names
 * without "back" or "_turned". Logs (tag "Identify") what was read, the
 * matches, and how much the top match looks like the card. Needs network.
 */
@RunWith(AndroidJUnit4::class)
class IdentifyFixturesTest {

    @Test
    fun identifyFixtures() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtures = File(context.getExternalFilesDir(null)!!, "fixtures").apply { mkdirs() }
        val fronts = fixtures.listFiles { f ->
            f.extension.lowercase() == "jpg" && "back" !in f.name && !f.nameWithoutExtension.endsWith("_turned")
        }?.sortedBy { it.name }
        assumeTrue("No front fixtures", !fronts.isNullOrEmpty())
        check(OpenCVLoader.initLocal())

        for (file in fronts!!) {
            val photo = CardFlattener.loadUpright(context.contentResolver, Uri.fromFile(file))
            val card = try {
                val flattened = CardFlattener.flatten(photo)
                val corners = flattened.cornersInPhoto ?: continue
                CardFlattener.cardOnly(photo, corners, 2)
            } finally {
                photo.release()
            }
            val (reading, upsideDown) = CardTextReader.readEitherWayUp(card)
            val shown = (if (upsideDown) CardTextReader.rotate180(card) else card)
                .let { Bitmap.createScaledBitmap(it, 600, 600 * it.height / it.width, true) }
            val result = CardIdentifier.identify(reading)
            val top = result.matches.firstOrNull()
            val looks = top?.imageBase?.let { CardImages.load(it) }?.let { VisualMatch.similarity(shown, it) }
            Log.i(TAG, "%-20s names=%s numbers=%s".format(file.nameWithoutExtension, reading.nameCandidates, reading.numberCandidates))
            Log.i(TAG, "%-20s → %s%s (%d matches)  looks %s".format(
                "", top?.label ?: "nothing", if (result.confident) " [confident by text]" else "", result.matches.size,
                looks?.let { "%.2f".format(it) } ?: "– (no image)",
            ))
            if (result.matches.size in 2..6) Log.i(TAG, "%-20s   all: %s".format("", result.matches.joinToString(" | ") { "${it.id} ${it.label}" }))
        }
    }

    private companion object {
        const val TAG = "Identify"
    }
}
