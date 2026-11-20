package com.inspectorade.cardscanner.surface

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.hypot

/**
 * Combines a tilt scan's frames (see [TiltScan]) into views of the surface:
 * each frame is aligned onto the most level one ([CardAligner]), then
 * - glare-free: every pixel at its darkest across the frames — a reflection
 *   lands on a given spot in only some of them;
 * - shine: brightest minus darkest, how much each spot changed as the light
 *   moved. Foil and holo light up, and so do scratches and dents, which catch
 *   the light at angles the flat surface around them doesn't.
 * A frame that doesn't line up to within [MAX_MEDIAN_RESIDUAL] /
 * [MAX_WORST_RESIDUAL] pixels is left out: misaligned, every printed edge
 * would show in the shine map as a false line.
 */
object GlareFusion {

    class Result(
        val glareFree: Bitmap,
        val shine: Bitmap,
        /** The frame everything was aligned to. */
        val reference: Bitmap,
        val framesUsed: Int,
        val framesDropped: Int,
    )

    /** [frames] are a scan's saved frames; [progress] gets (aligned so far, total). Null if none load. */
    fun fuse(frames: List<File>, progress: (Int, Int) -> Unit = { _, _ -> }): Result? {
        // Motion blur first: a frame taken mid-swing smears the print, and the
        // card's edge (the capture's sharpness check) can still look crisp along
        // the swing. Fine detail inside the card shows it.
        val scored = frames.mapNotNull { file -> sharpness(file)?.let { file to it } }
        val sharpest = scored.maxOfOrNull { it.second } ?: return null
        val sharp = scored.filter { it.second >= sharpest * MIN_RELATIVE_SHARPNESS }
        scored.sortedBy { it.first.name }.forEach { (file, score) ->
            Log.d(TAG, "%s sharpness %.0f%s".format(file.name, score, if (score < sharpest * MIN_RELATIVE_SHARPNESS) " (blurred, left out)" else ""))
        }
        var dropped = scored.size - sharp.size

        // The reference: the sharpest of the frames taken near level.
        fun tilt(file: File) = TiltScan.tiltOf(file.name)?.let { (x, y) -> hypot(x, y) } ?: Float.MAX_VALUE
        val nearLevel = sharp.filter { tilt(it.first) <= REFERENCE_MAX_TILT }.ifEmpty { sharp.sortedBy { tilt(it.first) }.take(3) }
        val referenceFile = nearLevel.maxBy { it.second }.first
        Log.i(TAG, "Reference ${referenceFile.name}; ${sharp.size} of ${scored.size} frames sharp enough")
        val reference = load(referenceFile) ?: return null
        val darkest = reference.clone()
        val brightest = reference.clone()
        var used = 1
        val others = sharp.map { it.first } - referenceFile
        for ((i, file) in others.withIndex()) {
            progress(i, others.size)
            val frame = load(file) ?: continue
            if (frame.size() != reference.size()) {
                frame.release()
                continue
            }
            val aligned = CardAligner.align(reference, frame)
            val residual = aligned.residual
            Log.d(TAG, "${file.name}: $residual${if (aligned.bent) " (bent)" else ""}")
            if (residual.medianPx <= MAX_MEDIAN_RESIDUAL && residual.worstPx <= MAX_WORST_RESIDUAL) {
                Core.min(darkest, aligned.aligned, darkest)
                Core.max(brightest, aligned.aligned, brightest)
                used++
            } else {
                dropped++
            }
            aligned.aligned.release()
            frame.release()
        }
        progress(others.size, others.size)
        Log.i(TAG, "Fused $used frames, dropped $dropped")

        val shine = Mat()
        Core.subtract(brightest, darkest, shine)
        Imgproc.cvtColor(shine, shine, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(shine, shine, Imgproc.COLOR_GRAY2RGBA)
        val result = Result(toBitmap(darkest), toBitmap(shine), toBitmap(reference), used, dropped)
        listOf(reference, darkest, brightest, shine).forEach { it.release() }
        return result
    }

    /**
     * How much fine detail [file] holds: the variance of its Laplacian, on the
     * middle of the card (the cut and the surface around it left out). Only
     * meaningful against other frames of the same card.
     */
    private fun sharpness(file: File): Double? {
        val rgba = load(file) ?: return null
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val inset = (gray.cols() * SHARPNESS_INSET).toInt()
        val middle = gray.submat(inset, gray.rows() - inset, inset, gray.cols() - inset)
        val laplacian = Mat()
        Imgproc.Laplacian(middle, laplacian, CvType.CV_64F)
        val mean = MatOfDouble()
        val deviation = MatOfDouble()
        Core.meanStdDev(laplacian, mean, deviation)
        val sd = deviation.toArray()[0]
        listOf(gray, middle, laplacian, mean, deviation).forEach { it.release() }
        return sd * sd
    }

    private fun load(file: File): Mat? {
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        bitmap.recycle()
        return mat
    }

    private fun toBitmap(rgba: Mat): Bitmap =
        Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgba, it) }

    private const val TAG = "GlareFusion"
    // First limits, from the fixture pairs (same-side repeats ≈0.2–0.35 px median).
    private const val MAX_MEDIAN_RESIDUAL = 1.0
    private const val MAX_WORST_RESIDUAL = 2.5
    // A frame with less than this share of the sharpest frame's detail is
    // treated as blurred.
    private const val MIN_RELATIVE_SHARPNESS = 0.5
    private const val SHARPNESS_INSET = 0.05
    // Frames within this tilt (degrees) count as level enough to be the reference.
    private const val REFERENCE_MAX_TILT = 6f
}
