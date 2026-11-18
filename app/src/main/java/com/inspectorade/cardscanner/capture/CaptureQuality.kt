package com.inspectorade.cardscanner.capture

import org.opencv.core.Mat
import org.opencv.core.Point
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Live checks that a capture will be usable, updated from the camera preview:
 *
 * - Light: from the exposure the camera chose (exposure time × ISO). Auto-exposure
 *   brightens a dim scene, so how bright the preview looks says little; how hard
 *   the camera had to work to get there says how dark it really is. Dim light
 *   means focus hunts, long exposures blur and the image gets noisy.
 * - Sharpness: how many pixels the card's own cut edge is smeared across. The cut
 *   is a crisp step from the surface to the card whatever is printed on the card,
 *   so unlike an overall "detail" score this doesn't depend on the artwork.
 * - Steadiness: from the gyroscope — the phone isn't rotating.
 *
 * The thresholds are first estimates from testing; the values are logged so they
 * can be tuned.
 */
class CaptureQuality {

    enum class Issue { DARK, BLURRY, MOVING }

    @Volatile private var exposureSeconds: Double? = null
    @Volatile private var iso: Int? = null
    @Volatile private var edgeWidth: Double? = null
    @Volatile private var edgeWidthUpdatedAt = 0L
    private var steadySince = 0L
    private var steady = false

    /** From each preview frame's capture result. */
    fun updateExposure(exposureNs: Long?, sensitivity: Int?) {
        exposureSeconds = exposureNs?.let { it / 1e9 }
        iso = sensitivity
    }

    /** From the gyroscope: rotation speed in rad/s. */
    fun updateRotation(radiansPerSecond: Double, nowMs: Long) {
        if (radiansPerSecond > MAX_STEADY_ROTATION) {
            steady = false
            steadySince = nowMs
        } else if (!steady && nowMs - steadySince >= STEADY_FOR_MS) {
            steady = true
        }
    }

    /** From each analysis frame with a detected card; null when no card is in view. */
    fun updateEdgeWidth(width: Double?, nowMs: Long) {
        // Smoothed, so one noisy frame doesn't flip the verdict back and forth.
        edgeWidth = if (width == null) null else edgeWidth?.let { it * (1 - SMOOTHING) + width * SMOOTHING } ?: width
        edgeWidthUpdatedAt = nowMs
    }

    /** Exposure time × ISO / 100: how hard the camera works for light. Bright ~0.01–0.05. */
    val exposureEffort: Double? get() = exposureSeconds?.let { s -> iso?.let { s * it / 100 } }

    val currentEdgeWidth: Double? get() = edgeWidth
    val isSteady: Boolean get() = steady

    /** Problems right now, most important first; empty when all's well (or unknown). */
    fun issues(nowMs: Long): List<Issue> = buildList {
        exposureEffort?.let { if (it > MAX_EXPOSURE_EFFORT) add(Issue.DARK) }
        val width = edgeWidth.takeIf { nowMs - edgeWidthUpdatedAt < STALE_MS }
        if (width != null && width > MAX_EDGE_WIDTH) add(Issue.BLURRY)
        if (!steady) add(Issue.MOVING)
    }

    /** Whether the sharpness check had a card to measure recently. */
    fun hasSharpnessReading(nowMs: Long) = edgeWidth != null && nowMs - edgeWidthUpdatedAt < STALE_MS

    companion object {
        // Exposure effort above this is dim enough to cause trouble: e.g. 1/30 s at
        // ISO 750. Well-lit indoor scenes sit far below.
        const val MAX_EXPOSURE_EFFORT = 0.25

        // The card's cut, 10–90% of the way from surface to card, measured 2.3–3.1
        // pixels at analysis resolution in focus (good light, dim, flashlight) and
        // 3.3–3.8 visibly out of focus. A narrow gap, so retune with more cards.
        const val MAX_EDGE_WIDTH = 3.2

        private const val MAX_STEADY_ROTATION = 0.08 // rad/s
        private const val STEADY_FOR_MS = 400L
        private const val SMOOTHING = 0.3
        private const val STALE_MS = 1000L

        // Edge sampling: points per side, along its middle part, and how far the
        // profile reaches either side of the edge.
        private const val SAMPLES_PER_SIDE = 12
        private const val PROFILE_REACH = 8
        private const val MIN_EDGE_CONTRAST = 20.0

        /**
         * Median width, in pixels, of the card's cut edge across the four sides of
         * [corners] in [gray] (single-channel, 8-bit): at points along each side, the
         * distance a profile across the edge takes to climb from 10% to 90% of the
         * way between its two sides. Null if too few points had enough contrast.
         */
        fun edgeWidth(gray: Mat, corners: Array<Point>): Double? {
            val width = gray.cols()
            val height = gray.rows()
            val pixels = ByteArray(width * height)
            gray.get(0, 0, pixels)
            fun at(x: Double, y: Double): Double {
                // Bilinear, so sub-pixel widths are measurable.
                val x0 = floor(x).toInt().coerceIn(0, width - 2)
                val y0 = floor(y).toInt().coerceIn(0, height - 2)
                val fx = (x - x0).coerceIn(0.0, 1.0)
                val fy = (y - y0).coerceIn(0.0, 1.0)
                fun p(px: Int, py: Int) = (pixels[py * width + px].toInt() and 0xFF).toDouble()
                return p(x0, y0) * (1 - fx) * (1 - fy) + p(x0 + 1, y0) * fx * (1 - fy) +
                    p(x0, y0 + 1) * (1 - fx) * fy + p(x0 + 1, y0 + 1) * fx * fy
            }

            val cx = corners.sumOf { it.x } / corners.size
            val cy = corners.sumOf { it.y } / corners.size
            val widths = mutableListOf<Double>()
            for (i in corners.indices) {
                val a = corners[i]
                val b = corners[(i + 1) % corners.size]
                val length = hypot(b.x - a.x, b.y - a.y)
                if (length < 1) continue
                // Unit normal pointing out of the card.
                var nx = -(b.y - a.y) / length
                var ny = (b.x - a.x) / length
                val mx = (a.x + b.x) / 2
                val my = (a.y + b.y) / 2
                if ((mx - cx) * nx + (my - cy) * ny < 0) { nx = -nx; ny = -ny }

                for (k in 0 until SAMPLES_PER_SIDE) {
                    val t = 0.2 + 0.6 * k / (SAMPLES_PER_SIDE - 1)
                    val px = a.x + (b.x - a.x) * t
                    val py = a.y + (b.y - a.y) * t
                    // From outside the card to inside, a quarter pixel at a time.
                    val profile = (-PROFILE_REACH * 4..PROFILE_REACH * 4).map { s ->
                        val d = -s / 4.0
                        at(px + nx * d, py + ny * d)
                    }
                    edgeWidthOf(profile)?.let { widths += it / 4 }
                }
            }
            if (widths.size < SAMPLES_PER_SIDE) return null
            return widths.sorted()[widths.size / 2]
        }

        /** 10–90% rise distance of a step-like profile, in samples; null if too flat. */
        private fun edgeWidthOf(profile: List<Double>): Double? {
            val quarter = profile.size / 4
            val outside = profile.take(quarter).average()
            val inside = profile.takeLast(quarter).average()
            val step = inside - outside
            if (abs(step) < MIN_EDGE_CONTRAST) return null
            fun crossing(fraction: Double): Double? {
                val level = outside + step * fraction
                for (i in 1 until profile.size) {
                    val before = profile[i - 1] - level
                    val after = profile[i] - level
                    if (before == 0.0) return (i - 1).toDouble()
                    if (before * after < 0) return i - 1 + abs(before) / (abs(before) + abs(after))
                }
                return null
            }
            val low = crossing(0.1) ?: return null
            val high = crossing(0.9) ?: return null
            return abs(high - low)
        }

        /** Rotation speed from a gyroscope event's three axes. */
        fun rotationSpeed(x: Float, y: Float, z: Float): Double = sqrt((x * x + y * y + z * z).toDouble())
    }
}
