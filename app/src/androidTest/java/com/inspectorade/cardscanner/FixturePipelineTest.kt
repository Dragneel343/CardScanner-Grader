package com.inspectorade.cardscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inspectorade.cardscanner.grading.BorderDetector
import com.inspectorade.cardscanner.grading.BorderLine
import com.inspectorade.cardscanner.grading.CardFlattener
import com.inspectorade.cardscanner.grading.CornerEdgeAnalyzer
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Runs flattening and border detection on saved capture photos, so detection
 * changes can be checked against the same photos every time, without a camera.
 *
 * Put photos (JPEG) in the app's external files dir under `fixtures/` (adb push
 * or `adb shell cp` from Pictures/CardScanner). For each one this logs (tag
 * "Fixture") whether the card was found, its outline error and the centering,
 * then corners & edges (grades, whitened % and chips per side, whitened % per
 * corner), and writes to `fixture_out/` the photo with the rough (red) and final (green)
 * outline, the flattened card with its border lines, the flattened card alone,
 * and the card with its wear painted in.
 */
@RunWith(AndroidJUnit4::class)
class FixturePipelineTest {

    @Test
    fun runFixtures() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.getExternalFilesDir(null)!!
        // Created by the app, not adb: a folder adb made before the app ever ran
        // can stay invisible to it.
        val fixtures = File(root, "fixtures").apply { mkdirs() }
        val photos = fixtures.listFiles { f -> f.extension.lowercase() == "jpg" }?.sortedBy { it.name }
        assumeTrue("No fixtures in ${root.path}/fixtures", !photos.isNullOrEmpty())
        check(OpenCVLoader.initLocal())
        val out = File(root, "fixture_out").apply { mkdirs() }

        val summary = StringBuilder()
        // X_turned.jpg is X.jpg's side photographed again with the card turned 180°:
        // analyzed with X, not on its own.
        val turnedPhotos = photos!!.filter { it.nameWithoutExtension.endsWith(TURNED) }
            .associateBy { it.nameWithoutExtension.removeSuffix(TURNED) }
        for (file in photos.filter { !it.nameWithoutExtension.endsWith(TURNED) }) {
            val photo = CardFlattener.loadUpright(context.contentResolver, Uri.fromFile(file))
            val result = CardFlattener.flatten(photo)
            // Named *back* for a card back, as the centering screen knows from the side.
            val lines = if ("back" in file.name) BorderDetector.detectBack(result.bitmap, CardFlattener.MARGIN)
            else BorderDetector.detect(result.bitmap, CardFlattener.MARGIN)
            fun share(outerA: BorderLine, innerA: BorderLine, outerB: BorderLine, innerB: BorderLine): Double {
                val a = kotlin.math.abs(lines[innerA] - lines[outerA])
                val b = kotlin.math.abs(lines[outerB] - lines[innerB])
                return 100.0 * a / (a + b)
            }
            val lr = share(BorderLine.OUTER_LEFT, BorderLine.INNER_LEFT, BorderLine.OUTER_RIGHT, BorderLine.INNER_RIGHT)
            val tb = share(BorderLine.OUTER_TOP, BorderLine.INNER_TOP, BorderLine.OUTER_BOTTOM, BorderLine.INNER_BOTTOM)
            val line = "%-18s found=%-5b error=%5.2f  L|R %5.1f  T|B %5.1f  lines %s".format(
                file.nameWithoutExtension, result.cardFound, result.outlineError, lr, tb,
                BorderLine.entries.joinToString(" ") { "${it.name.split('_').joinToString("") { p -> p.take(1) }}=${lines[it].toInt()}" },
            )
            Log.i(TAG, line)
            summary.appendLine(line)
            if (result.cardFound) {
                fun wearLine(label: String, wear: CornerEdgeAnalyzer.Result) =
                    "%-18s corners≈%s edges≈%s  %s  corners %s".format(
                        label, wear.cornerGrade, wear.edgeGrade,
                        wear.edges.joinToString(" ") {
                            if (!it.assessable) "${it.side.label.take(1)} –"
                            else "%s %.1f%%/%d/%.1f%%".format(it.side.label.take(1), it.whitenedFraction * 100, it.chips, it.dentedFraction * 100)
                        },
                        wear.corners.joinToString(" ") {
                            "%.0f%%/%.2fmm%s".format(it.whitenedFraction * 100, it.roundingMm, if (it.possibleDing) " ding?" else "")
                        },
                    ).also { Log.i(TAG, it); summary.appendLine(it) }
                val wear = CornerEdgeAnalyzer.analyze(result.bitmap, lines)
                wearLine("  one photo", wear)
                saveWear(result.bitmap, wear, File(out, "${file.nameWithoutExtension}_wear.png"))

                val turnedFile = turnedPhotos[file.nameWithoutExtension]
                if (turnedFile != null) {
                    val turnedPhoto = CardFlattener.loadUpright(context.contentResolver, Uri.fromFile(turnedFile))
                    val turned = CardFlattener.flatten(turnedPhoto)
                    turnedPhoto.release()
                    if (!turned.cardFound) {
                        Log.i(TAG, "  turned photo: card not found").also { summary.appendLine("  turned photo: card not found") }
                    } else {
                        val turnedBack = turnedAround(turned.bitmap)
                        val confirmed = CornerEdgeAnalyzer.analyze(result.bitmap, lines, turnedBack)
                        wearLine("  both photos", confirmed)
                        saveWear(result.bitmap, confirmed, File(out, "${file.nameWithoutExtension}_wear_both.png"))
                        File(out, "${file.nameWithoutExtension}_turned_card.png").outputStream().use {
                            turnedBack.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                }
            }
            File(out, "${file.nameWithoutExtension}_card.png").outputStream().use {
                result.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }

            saveOutline(photo, result, File(out, "${file.nameWithoutExtension}_photo.jpg"))
            saveLines(result.bitmap, lines, File(out, "${file.nameWithoutExtension}_lines.jpg"))
            photo.release()
        }
        File(out, "summary.txt").writeText(summary.toString())
    }

    private fun saveOutline(photo: Mat, result: CardFlattener.Result, file: File) {
        val scale = 1000.0 / maxOf(photo.cols(), photo.rows())
        val small = Mat()
        Imgproc.resize(photo, small, Size(), scale, scale, Imgproc.INTER_AREA)
        fun outline(corners: Array<Point>?, color: Scalar) {
            val points = corners?.map { Point(it.x * scale, it.y * scale) } ?: return
            for (i in points.indices) Imgproc.line(small, points[i], points[(i + 1) % points.size], color, 2)
        }
        outline(result.roughCorners, Scalar(255.0, 0.0, 0.0, 255.0))
        outline(result.cornersInPhoto, Scalar(0.0, 255.0, 0.0, 255.0))
        val bitmap = Bitmap.createBitmap(small.cols(), small.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(small, bitmap)
        small.release()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    /** [card] rotated 180°: a card photographed turned around, back the right way up. */
    private fun turnedAround(card: Bitmap): Bitmap =
        Bitmap.createBitmap(card, 0, 0, card.width, card.height, android.graphics.Matrix().apply { postRotate(180f) }, false)

    /** The card with whitening (magenta) and missing material (cyan) painted in, as on the screen. */
    private fun saveWear(card: Bitmap, wear: CornerEdgeAnalyzer.Result, file: File) {
        val copy = card.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(copy.width * copy.height)
        copy.getPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
        for (i in wear.whitenedPixels) pixels[i] = Color.MAGENTA
        for (i in wear.missingPixels) pixels[i] = Color.CYAN
        copy.setPixels(pixels, 0, copy.width, 0, 0, copy.width, copy.height)
        file.outputStream().use { copy.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun saveLines(card: Bitmap, lines: com.inspectorade.cardscanner.grading.BorderLines, file: File) {
        val copy = card.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val paint = Paint().apply { strokeWidth = 2f }
        for (line in BorderLine.entries) {
            paint.color = if (line.outer) Color.MAGENTA else Color.CYAN
            val at = lines[line]
            if (line.vertical) canvas.drawLine(at, 0f, at, copy.height.toFloat(), paint)
            else canvas.drawLine(0f, at, copy.width.toFloat(), at, paint)
        }
        file.outputStream().use { copy.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private companion object {
        const val TAG = "Fixture"
        const val TURNED = "_turned"
    }
}
