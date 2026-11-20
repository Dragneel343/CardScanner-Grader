package com.inspectorade.cardscanner

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.inspectorade.cardscanner.grading.CardFlattener
import com.inspectorade.cardscanner.surface.CardAligner
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Runs [CardAligner] on pairs of fixture photos of the same card (see
 * [FixturePipelineTest]): each photo with its `_turned` partner, the repeat
 * shots of one side (`name_1`, `name_2`, … against `name_1`), and a photo
 * against a copy of itself bent by a known amount. Logs (tag "Align") the
 * misalignment left after the outline alone, after the design fit, and after
 * the bend correction; saves amplified differences to `fixture_out/align/`.
 */
@RunWith(AndroidJUnit4::class)
class AlignmentFixturesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun alignFixtures() {
        val fixtures = File(context.getExternalFilesDir(null)!!, "fixtures").apply { mkdirs() }
        val photos = fixtures.listFiles { f -> f.extension.lowercase() == "jpg" }?.associateBy { it.nameWithoutExtension }
        assumeTrue("No fixtures", !photos.isNullOrEmpty())
        check(OpenCVLoader.initLocal())
        val out = File(context.getExternalFilesDir(null)!!, "fixture_out/align").apply { deleteRecursively(); mkdirs() }

        val pairs = buildList {
            for (name in photos!!.keys.sorted()) {
                if (name.endsWith(TURNED)) photos[name.removeSuffix(TURNED)]?.let { add(Triple(it, photos[name]!!, true)) }
                val repeat = Regex("(.*)_(\\d+)").matchEntire(name)
                if (repeat != null && repeat.groupValues[2] != "1") {
                    photos[repeat.groupValues[1] + "_1"]?.let { add(Triple(it, photos[name]!!, false)) }
                }
            }
        }

        for ((refFile, frameFile, turned) in pairs) {
            val label = "${refFile.nameWithoutExtension} ← ${frameFile.nameWithoutExtension}"
            val reference = card(refFile, false) ?: run { Log.i(TAG, "$label: no card"); null } ?: continue
            val frame = card(frameFile, turned) ?: run { Log.i(TAG, "$label: no card"); reference.release(); null } ?: continue
            report(label, reference, frame, out, "${refFile.nameWithoutExtension}__${frameFile.nameWithoutExtension}")
            reference.release()
            frame.release()
        }

        // A known bend: a gentle bow across the card plus a small perspective
        // slip, as a card that isn't flat would show.
        photos!!["moltres_pair"]?.let { file ->
            val reference = card(file, false) ?: return@let
            val bent = bend(reference)
            report("moltres_pair ← synthetic bend", reference, bent, out, "synthetic_bend")
            reference.release()
            bent.release()
        }
    }

    private fun report(label: String, reference: Mat, frame: Mat, out: File, fileName: String) {
        val outline = CardAligner.residual(reference, frame)
        val designOnly = CardAligner.align(reference, frame, smoothBend = false)
        val start = SystemClock.elapsedRealtime()
        val full = CardAligner.align(reference, frame)
        Log.i(TAG, "$label  (${SystemClock.elapsedRealtime() - start} ms)")
        Log.i(TAG, "    outline only : $outline")
        Log.i(TAG, "    + design fit : ${designOnly.residual}${if (designOnly.fitted) "" else "  (fit failed)"}")
        Log.i(TAG, "    + bend       : ${full.residual}${if (full.bent) "" else "  (bend not used)"}")
        save(difference(reference, frame), File(out, "${fileName}_before.png"))
        save(difference(reference, full.aligned), File(out, "${fileName}_after.png"))
        designOnly.aligned.release()
        full.aligned.release()
    }

    /** The card alone from [file], at the standard size, turned back upright if [turned]. */
    private fun card(file: File, turned: Boolean): Mat? {
        val photo = CardFlattener.loadUpright(context.contentResolver, Uri.fromFile(file))
        try {
            val corners = CardFlattener.flatten(photo).cornersInPhoto ?: return null
            val bitmap = CardFlattener.cardOnly(photo, corners, 1)
            val card = Mat()
            Utils.bitmapToMat(bitmap, card)
            bitmap.recycle()
            if (turned) Core.rotate(card, card, Core.ROTATE_180)
            return card
        } finally {
            photo.release()
        }
    }

    private fun bend(card: Mat): Mat {
        val width = card.cols()
        val height = card.rows()
        val slip = Imgproc.getPerspectiveTransform(
            MatOfPoint2f(Point(0.0, 0.0), Point(width.toDouble(), 0.0), Point(width.toDouble(), height.toDouble()), Point(0.0, height.toDouble())),
            MatOfPoint2f(Point(3.0, -2.0), Point(width + 1.5, 2.5), Point(width - 2.0, height + 1.0), Point(-1.0, height - 3.0)),
        )
        val map = Mat(height, width, CvType.CV_32FC2)
        val row = FloatArray(width * 2)
        for (y in 0 until height) {
            for (x in 0 until width) {
                row[2 * x] = (x + 2.5 * sin(PI * y / height)).toFloat()
                row[2 * x + 1] = (y + 2.0 * sin(PI * x / width)).toFloat()
            }
            map.put(y, 0, row)
        }
        Core.perspectiveTransform(map, map, slip)
        val bent = Mat()
        Imgproc.remap(card, bent, map, Mat(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        slip.release()
        map.release()
        return bent
    }

    /** |a − b| in gray, ×4 so a pixel of misalignment shows as bright outlines. */
    private fun difference(a: Mat, b: Mat): Mat {
        val grayA = Mat()
        val grayB = Mat()
        Imgproc.cvtColor(a, grayA, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(b, grayB, Imgproc.COLOR_RGBA2GRAY)
        val diff = Mat()
        Core.absdiff(grayA, grayB, diff)
        diff.convertTo(diff, -1, 4.0)
        grayA.release()
        grayB.release()
        return diff
    }

    private fun save(gray: Mat, file: File) {
        val rgba = Mat()
        Imgproc.cvtColor(gray, rgba, Imgproc.COLOR_GRAY2RGBA)
        val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        rgba.release()
        gray.release()
    }

    private companion object {
        const val TAG = "Align"
        const val TURNED = "_turned"
    }
}
