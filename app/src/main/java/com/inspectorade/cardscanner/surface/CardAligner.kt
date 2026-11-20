package com.inspectorade.cardscanner.surface

import android.os.SystemClock
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.hypot

/**
 * Lines up a photo of a card with another photo of the same card, pixel to
 * pixel, so the two can be compared: glare fusion and defect diffs see a
 * misalignment of even a pixel as a ghost line along every printed edge.
 *
 * Both inputs are the card alone, already flattened from its outline to the
 * same size (see [com.inspectorade.cardscanner.grading.CardFlattener.cardOnly]),
 * which leaves them a few pixels apart. Then:
 * 1. a homography fitted to the printed design (ECC, which ignores overall
 *    brightness and contrast, coarse to fine, glare left out), for what the
 *    outline got slightly wrong;
 * 2. a smooth bend fitted to what is left, for a card that isn't quite flat (bow,
 *    curl). It only bends gradually, so a crease stays a crease.
 *
 * Lens distortion isn't corrected: the camera pipeline already does that for
 * the JPEGs used here, and what's left is smooth enough for step 2.
 */
object CardAligner {

    class Result(
        /** The frame warped onto the reference, same size and type. Caller releases. */
        val aligned: Mat,
        /** Misalignment left, see [residual]. */
        val residual: Residual,
        /** Whether the design fit converged; if not, only the outline aligned it. */
        val fitted: Boolean,
        /** Whether the bend correction was applied (it's dropped if it didn't help). */
        val bent: Boolean,
    )

    /**
     * How far apart two images still are: the shift between them measured by
     * phase correlation in tiles across the card, over the tiles with enough
     * printed detail. In pixels at the images' scale.
     */
    class Residual(val medianPx: Double, val worstPx: Double, val tiles: Int) {
        override fun toString() = "median %.2f px, 90%% within %.2f px (%d tiles)".format(medianPx, worstPx, tiles)
    }

    /**
     * Aligns [frame] onto [reference]: both RGBA, the same size. Neither is
     * released. [smoothBend] turns step 2 off when false, for comparison.
     */
    fun align(reference: Mat, frame: Mat, smoothBend: Boolean = true): Result {
        val start = SystemClock.elapsedRealtime()
        val refGray = gray(reference)
        val frameGray = gray(frame)
        val homography = fitHomography(refGray, frameGray)
        val fitted = homography != null
        val fitDone = SystemClock.elapsedRealtime()
        val h = homography ?: Mat.eye(3, 3, CvType.CV_32F)

        // Source position in the frame for every reference pixel: through the
        // homography, plus the bend measured after it — kept only if it brings
        // the two closer, since lighting that differs between them (holo, a
        // light on the other side) can pull it off.
        val grid = pixelGrid(reference.cols(), reference.rows())
        var aligned = warpThrough(frame, h, grid)
        var alignedGray = gray(aligned)
        var residual = residual(refGray, alignedGray)
        var bent = false
        val warpDone = SystemClock.elapsedRealtime()
        val bend = if (smoothBend) bendField(refGray, alignedGray) else null
        if (bend != null) {
            Core.add(grid, bend, grid)
            val bentAligned = warpThrough(frame, h, grid)
            val bentGray = gray(bentAligned)
            val bentResidual = residual(refGray, bentGray)
            if (bentResidual.medianPx <= residual.medianPx && bentResidual.worstPx <= residual.worstPx) {
                aligned.release()
                alignedGray.release()
                aligned = bentAligned
                alignedGray = bentGray
                residual = bentResidual
                bent = true
            } else {
                bentAligned.release()
                bentGray.release()
            }
            bend.release()
        }
        listOf(refGray, frameGray, h, grid, alignedGray).forEach { it.release() }
        val end = SystemClock.elapsedRealtime()
        Log.d(TAG, "Aligned in ${end - start} ms: design fit ${fitDone - start}, warp ${warpDone - fitDone}, bend ${end - warpDone}")
        return Result(aligned, residual, fitted, bent)
    }

    /** See [Residual]. [a] and [b] are 8-bit gray or RGBA, the same size. */
    fun residual(a: Mat, b: Mat): Residual {
        val shifts = tileShifts(a, b, TILE).map { hypot(it.dx, it.dy) }.sorted()
        if (shifts.isEmpty()) return Residual(Double.NaN, Double.NaN, 0)
        return Residual(shifts[shifts.size / 2], shifts[(shifts.size * 9 / 10).coerceAtMost(shifts.size - 1)], shifts.size)
    }

    /** [b]'s offset from [a] in one tile centred on ([x], [y]): b(p + offset) ≈ a(p). */
    private class TileShift(val x: Double, val y: Double, val dx: Double, val dy: Double, val weight: Double)

    /**
     * Phase-correlation shifts of [b] against [a], in [TILE]-sized tiles every
     * [step] pixels, over the tiles with enough printed detail, little glare
     * and a clear correlation peak.
     */
    private fun tileShifts(a: Mat, b: Mat, step: Int): List<TileShift> {
        val grayA = if (a.channels() == 1) a else gray(a)
        val grayB = if (b.channels() == 1) b else gray(b)
        val window = Mat()
        Imgproc.createHanningWindow(window, Size(TILE.toDouble(), TILE.toDouble()), CvType.CV_64F)
        val shifts = mutableListOf<TileShift>()
        val tileA = Mat()
        val tileB = Mat()
        val mean = MatOfDouble()
        val deviation = MatOfDouble()
        var y = 0
        while (y + TILE <= grayA.rows()) {
            var x = 0
            while (x + TILE <= grayA.cols()) {
                val rect = Rect(x, y, TILE, TILE)
                val subA = grayA.submat(rect)
                val subB = grayB.submat(rect)
                Core.meanStdDev(subA, mean, deviation)
                val glare = glarePixels(subA) + glarePixels(subB)
                if (deviation.toArray()[0] >= MIN_TILE_DETAIL && glare < TILE * TILE / 20) {
                    subA.convertTo(tileA, CvType.CV_64F)
                    subB.convertTo(tileB, CvType.CV_64F)
                    val response = DoubleArray(1)
                    val shift = Imgproc.phaseCorrelate(tileA, tileB, window, response)
                    if (response[0] >= MIN_CORRELATION) {
                        shifts += TileShift(x + TILE / 2.0, y + TILE / 2.0, shift.x, shift.y, response[0])
                    }
                }
                subA.release()
                subB.release()
                x += step
            }
            y += step
        }
        listOf(window, tileA, tileB, mean, deviation).forEach { it.release() }
        if (grayA !== a) grayA.release()
        if (grayB !== b) grayB.release()
        return shifts
    }

    /** [frame] sampled at the frame positions [grid] (reference pixel → frame pixel before [h]) map to through [h]. */
    private fun warpThrough(frame: Mat, h: Mat, grid: Mat): Mat {
        val map = Mat()
        Core.perspectiveTransform(grid, map, h)
        val warped = Mat()
        val none = Mat()
        Imgproc.remap(frame, warped, map, none, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        map.release()
        none.release()
        return warped
    }

    /**
     * Homography taking reference pixels to frame pixels, fitted to the printed
     * design coarse to fine; null if it didn't converge. It stops at
     * FINEST_FIT_LEVEL: full resolution takes seconds (tens on some holos), and
     * the bend fit after it takes up what's left there.
     */
    private fun fitHomography(refGray: Mat, frameGray: Mat): Mat? {
        val refs = pyramid(refGray)
        val frames = pyramid(frameGray)
        val warp = Mat.eye(3, 3, CvType.CV_32F)
        val criteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, ECC_ITERATIONS, ECC_EPSILON)
        // One level finer: the image doubles, so the shift terms double and the
        // perspective terms halve.
        fun doubleUp() {
            warp.put(0, 2, warp.get(0, 2)[0] * 2)
            warp.put(1, 2, warp.get(1, 2)[0] * 2)
            warp.put(2, 0, warp.get(2, 0)[0] / 2)
            warp.put(2, 1, warp.get(2, 1)[0] / 2)
        }
        try {
            for (level in refs.indices.reversed()) {
                if (level != refs.lastIndex) doubleUp()
                if (level < FINEST_FIT_LEVEL) continue
                val mask = threshold(frames[level])
                Core.bitwise_not(mask, mask)
                try {
                    Video.findTransformECC(refs[level], frames[level], warp, Video.MOTION_HOMOGRAPHY, criteria, mask, ECC_BLUR)
                } finally {
                    mask.release()
                }
            }
            return warp
        } catch (e: Exception) {
            Log.w(TAG, "Design fit didn't converge: ${e.message}")
            warp.release()
            return null
        } finally {
            (refs.drop(1) + frames.drop(1)).forEach { it.release() }
        }
    }

    /**
     * How far [frameGray] still sits from [refGray] at every pixel (reference
     * pixel + bend = frame pixel), as a 2-channel float image: a cubic surface
     * fitted to the tile shifts, tiles that disagree with it dropped. A cubic
     * follows a bow or a curl but can't kink, so a crease isn't straightened
     * away. Null if too few tiles measured, or the fit wanders implausibly far.
     */
    private fun bendField(refGray: Mat, frameGray: Mat): Mat? {
        val width = refGray.cols()
        val height = refGray.rows()
        fun terms(x: Double, y: Double): DoubleArray {
            val u = 2 * x / width - 1
            val v = 2 * y / height - 1
            return doubleArrayOf(1.0, u, v, u * u, u * v, v * v, u * u * u, u * u * v, u * v * v, v * v * v)
        }

        var tiles = tileShifts(refGray, frameGray, TILE / 2)
        var fit: Pair<DoubleArray, DoubleArray>? = null
        repeat(BEND_FIT_ROUNDS) {
            if (tiles.size < MIN_BEND_TILES) return null
            val a = Mat(tiles.size, BEND_TERMS, CvType.CV_64F)
            val bx = Mat(tiles.size, 1, CvType.CV_64F)
            val by = Mat(tiles.size, 1, CvType.CV_64F)
            tiles.forEachIndexed { i, t ->
                val w = kotlin.math.sqrt(t.weight)
                a.put(i, 0, *terms(t.x, t.y).map { it * w }.toDoubleArray())
                bx.put(i, 0, t.dx * w)
                by.put(i, 0, t.dy * w)
            }
            val cx = Mat()
            val cy = Mat()
            Core.solve(a, bx, cx, Core.DECOMP_SVD)
            Core.solve(a, by, cy, Core.DECOMP_SVD)
            val coefficients = DoubleArray(BEND_TERMS) { cx.get(it, 0)[0] } to DoubleArray(BEND_TERMS) { cy.get(it, 0)[0] }
            listOf(a, bx, by, cx, cy).forEach { it.release() }
            fit = coefficients

            fun miss(t: TileShift): Double {
                val p = terms(t.x, t.y)
                return hypot(p.indices.sumOf { p[it] * coefficients.first[it] } - t.dx, p.indices.sumOf { p[it] * coefficients.second[it] } - t.dy)
            }
            val misses = tiles.map { miss(it) }.sorted()
            val limit = maxOf(MIN_OUTLIER_PX, OUTLIER_SPREAD * misses[misses.size / 2])
            tiles = tiles.filter { miss(it) <= limit }
        }
        val (fx, fy) = fit ?: return null

        // The surface is smooth, so evaluate it every BEND_STEP pixels (at the
        // centres of BEND_STEP-sized cells, so resizing up lines back up) and
        // resize: per pixel would take seconds.
        val columns = (width + BEND_STEP - 1) / BEND_STEP
        val rows = (height + BEND_STEP - 1) / BEND_STEP
        val coarse = Mat(rows, columns, CvType.CV_32FC2)
        val row = FloatArray(columns * 2)
        var furthest = 0.0
        for (j in 0 until rows) {
            for (i in 0 until columns) {
                val p = terms((i + 0.5) * BEND_STEP - 0.5, (j + 0.5) * BEND_STEP - 0.5)
                val dx = p.indices.sumOf { p[it] * fx[it] }
                val dy = p.indices.sumOf { p[it] * fy[it] }
                furthest = maxOf(furthest, hypot(dx, dy))
                row[2 * i] = dx.toFloat()
                row[2 * i + 1] = dy.toFloat()
            }
            coarse.put(j, 0, row)
        }
        if (furthest > MAX_BEND_PX) {
            Log.w(TAG, "Bend fit wandered %.1f px; not used".format(furthest))
            coarse.release()
            return null
        }
        val full = Mat()
        Imgproc.resize(coarse, full, Size((columns * BEND_STEP).toDouble(), (rows * BEND_STEP).toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val field = full.submat(0, height, 0, width).clone()
        coarse.release()
        full.release()
        return field
    }

    /** Pixel coordinates as a 2-channel float image: (x, y) at (x, y). */
    private fun pixelGrid(width: Int, height: Int): Mat {
        val xs = Mat(1, width, CvType.CV_32F)
        xs.put(0, 0, FloatArray(width) { it.toFloat() })
        val ys = Mat(height, 1, CvType.CV_32F)
        ys.put(0, 0, FloatArray(height) { it.toFloat() })
        val xPlane = Mat()
        val yPlane = Mat()
        Core.repeat(xs, height, 1, xPlane)
        Core.repeat(ys, 1, width, yPlane)
        val grid = Mat()
        Core.merge(listOf(xPlane, yPlane), grid)
        listOf(xs, ys, xPlane, yPlane).forEach { it.release() }
        return grid
    }

    /** [image] first, then each half the size of the last. */
    private fun pyramid(image: Mat): List<Mat> = buildList {
        add(image)
        repeat(PYRAMID_LEVELS - 1) {
            val smaller = Mat()
            Imgproc.pyrDown(last(), smaller)
            add(smaller)
        }
    }

    private fun gray(rgba: Mat): Mat = Mat().also { Imgproc.cvtColor(rgba, it, Imgproc.COLOR_RGBA2GRAY) }

    private fun glarePixels(gray: Mat): Int {
        val glare = threshold(gray)
        return Core.countNonZero(glare).also { glare.release() }
    }

    /** 255 where [gray] is glare, grown a little to cover its soft edge. */
    private fun threshold(gray: Mat): Mat {
        val glare = Mat()
        Imgproc.threshold(gray, glare, GLARE_LEVEL, 255.0, Imgproc.THRESH_BINARY)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(GLARE_GROW, GLARE_GROW))
        Imgproc.dilate(glare, glare, kernel)
        kernel.release()
        return glare
    }

    private const val TAG = "CardAligner"

    private const val PYRAMID_LEVELS = 4
    private const val FINEST_FIT_LEVEL = 1
    private const val ECC_ITERATIONS = 25
    private const val ECC_EPSILON = 1e-4
    private const val ECC_BLUR = 5
    // Bend fit: cubic in x and y (10 terms), refitted without tiles that miss it
    // by more than OUTLIER_SPREAD × the median miss.
    private const val BEND_TERMS = 10
    private const val BEND_FIT_ROUNDS = 3
    private const val MIN_BEND_TILES = 30
    private const val OUTLIER_SPREAD = 3.0
    private const val MIN_OUTLIER_PX = 0.5
    private const val MAX_BEND_PX = 8.0
    private const val BEND_STEP = 8
    private const val GLARE_LEVEL = 245.0
    private const val GLARE_GROW = 9.0
    private const val TILE = 100
    private const val MIN_TILE_DETAIL = 8.0
    private const val MIN_CORRELATION = 0.15
}
