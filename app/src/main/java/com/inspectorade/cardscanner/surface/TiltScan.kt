package com.inspectorade.cardscanner.surface

import java.util.Locale
import kotlin.math.hypot

/**
 * Guides a tilt scan of the surface: the card lies flat and the phone rocks
 * above it, side to side and then top to bottom, so reflections sweep across
 * the card while it stays put. Tracks which pass the scan is on, how far each
 * way the phone has tilted in it (relative to where the scan started), and
 * which tilt angles already have a frame, so frames get kept at a spread of
 * angles rather than many at one.
 *
 * Tilt is in degrees about the phone's axes: x is side to side (rocking about
 * the phone's long axis), y is top to bottom.
 */
class TiltScan(private val startX: Float, private val startY: Float, private val startMs: Long) {

    enum class Pass { SIDE_TO_SIDE, TOP_TO_BOTTOM, DONE }

    var pass = Pass.SIDE_TO_SIDE
        private set
    /** Whether this pass has reached [REACH_DEGREES] the negative / positive way. */
    var reachedNegative = false
        private set
    var reachedPositive = false
        private set
    private var passStartMs = startMs
    private val kept = mutableListOf<Pair<Float, Float>>()
    val framesKept: Int get() = synchronized(kept) { kept.size }

    /** Feeds the phone's current tilt; returns the pass the scan is now on. */
    fun update(tiltX: Float, tiltY: Float, nowMs: Long): Pass {
        val along = when (pass) {
            Pass.SIDE_TO_SIDE -> tiltX - startX
            Pass.TOP_TO_BOTTOM -> tiltY - startY
            Pass.DONE -> return pass
        }
        if (along <= -REACH_DEGREES) reachedNegative = true
        if (along >= REACH_DEGREES) reachedPositive = true
        if (reachedNegative && reachedPositive && nowMs - passStartMs >= MIN_PASS_MS) {
            pass = if (pass == Pass.SIDE_TO_SIDE) Pass.TOP_TO_BOTTOM else Pass.DONE
            reachedNegative = false
            reachedPositive = false
            passStartMs = nowMs
        }
        return pass
    }

    /** Ends the scan where it is (the user pressed Stop). */
    fun stop() {
        pass = Pass.DONE
    }

    /** Whether a frame at this tilt would add a new angle (call [keep] if it's kept). */
    fun wants(tiltX: Float, tiltY: Float): Boolean = synchronized(kept) {
        pass != Pass.DONE && kept.size < MAX_FRAMES &&
            kept.none { (x, y) -> hypot(x - tiltX, y - tiltY) < MIN_ANGLE_STEP }
    }

    fun keep(tiltX: Float, tiltY: Float) = synchronized(kept) { kept += tiltX to tiltY }

    companion object {
        // How far each way a pass needs to tilt: a full tilt, since some damage
        // only catches the light at a steep angle. Frames are kept beyond it too.
        const val REACH_DEGREES = 15f
        // Each pass lasts at least this long, so it gathers frames on the way.
        const val MIN_PASS_MS = 5000L
        const val MIN_ANGLE_STEP = 2f
        const val MAX_FRAMES = 50
        // Fewer usable frames than this and the scan says to try again.
        const val MIN_FRAMES = 8

        /** A saved frame's file name, carrying the tilt it was taken at. */
        fun frameName(index: Int, tiltX: Float, tiltY: Float) =
            String.format(Locale.US, "frame_%02d_%.1f_%.1f.jpg", index, tiltX, tiltY)

        /** The tilt (x, y) in a [frameName], or null if it isn't one. */
        fun tiltOf(fileName: String): Pair<Float, Float>? =
            Regex("frame_\\d+_(-?[\\d.]+)_(-?[\\d.]+)\\.jpg").matchEntire(fileName)
                ?.let { it.groupValues[1].toFloat() to it.groupValues[2].toFloat() }
    }
}
