package com.inspectorade.cardscanner.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Draws a centered guide rectangle matching a standard trading card's
 * aspect ratio (2.5in x 3.5in) so the user can line the physical card up
 * with the camera before capture.
 */
class CardOutlineOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val CARD_ASPECT_RATIO = 2.5f / 3.5f // width / height, portrait orientation
    }

    private val guidePaint = Paint().apply {
        color = Color.argb(220, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    // Dashed and green once focus/exposure/white-balance lock onto the card — a
    // clear "about to capture" warning, shown in place of the plain solid guide
    // whenever lock is held (manually via tap or automatically via auto-capture).
    private val lockedPaint = Paint().apply {
        color = Color.argb(230, 76, 217, 100)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
    }

    // Dashed, neutral color: a card was detected by live edge-detection but
    // isn't locked yet. Once locked, lockedPaint (green) takes over instead.
    private val detectedPaint = Paint().apply {
        color = Color.argb(230, 255, 213, 79)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
    }

    private var isLocked = false

    // Four corners (view coordinates) of the live-detected card outline, or null
    // when no card is currently detected in frame.
    private var detectedQuad: Array<Pair<Float, Float>>? = null

    /** Called by CaptureActivity whenever 3A lock state changes. */
    fun setLocked(locked: Boolean) {
        if (isLocked == locked) return
        isLocked = locked
        invalidate()
    }

    /**
     * Called by CaptureActivity as live card-edge detection runs on the camera
     * stream. `quad` is four (x, y) corners in this view's own coordinate space
     * (same space as touch events), in contour order — or null when no card-shaped
     * quadrilateral is currently found, in which case the static guide box shows
     * instead.
     */
    fun updateDetectedQuad(quad: Array<Pair<Float, Float>>?) {
        detectedQuad = quad
        invalidate()
    }

    private val dimPaint = Paint().apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val levelRingPaint = Paint().apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val levelDotLevelPaint = Paint().apply {
        color = Color.argb(230, 76, 217, 100)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val levelDotTiltedPaint = Paint().apply {
        color = Color.argb(230, 255, 82, 82)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Degrees of tilt on each axis at which the dot reaches the edge of the ring;
    // beyond this it's clamped, since the ring is a bounded visual aid, not a gauge.
    private val maxDotTiltDegrees = 10f

    private var tiltXDegrees = 0f
    private var tiltYDegrees = 0f
    private var isLevel = false

    /** Called by CaptureActivity as the device orientation sensor updates. */
    fun updateTilt(xDegrees: Float, yDegrees: Float, level: Boolean) {
        tiltXDegrees = xDegrees
        tiltYDegrees = yDegrees
        isLevel = level
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val guideRect = computeGuideRect()

        // Dim everything outside the guide rectangle.
        canvas.drawRect(0f, 0f, width.toFloat(), guideRect.top, dimPaint)
        canvas.drawRect(0f, guideRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, guideRect.top, guideRect.left, guideRect.bottom, dimPaint)
        canvas.drawRect(guideRect.right, guideRect.top, width.toFloat(), guideRect.bottom, dimPaint)

        val quad = detectedQuad
        if (quad != null && quad.size == 4) {
            val path = Path().apply {
                moveTo(quad[0].first, quad[0].second)
                lineTo(quad[1].first, quad[1].second)
                lineTo(quad[2].first, quad[2].second)
                lineTo(quad[3].first, quad[3].second)
                close()
            }
            canvas.drawPath(path, if (isLocked) lockedPaint else detectedPaint)
        } else {
            canvas.drawRect(guideRect, if (isLocked) lockedPaint else guidePaint)
        }
        drawLevelIndicator(canvas, guideRect)
    }

    private fun drawLevelIndicator(canvas: Canvas, guideRect: RectF) {
        val centerX = guideRect.centerX()
        val centerY = guideRect.centerY()
        val ringRadius = min(guideRect.width(), guideRect.height()) * 0.12f

        canvas.drawCircle(centerX, centerY, ringRadius, levelRingPaint)

        val offsetX = (tiltXDegrees / maxDotTiltDegrees).coerceIn(-1f, 1f) * ringRadius
        val offsetY = (tiltYDegrees / maxDotTiltDegrees).coerceIn(-1f, 1f) * ringRadius
        val dotPaint = if (isLevel) levelDotLevelPaint else levelDotTiltedPaint
        canvas.drawCircle(centerX + offsetX, centerY + offsetY, ringRadius * 0.2f, dotPaint)
    }

    private fun computeGuideRect(): RectF {
        val horizontalMargin = width * 0.08f
        val maxGuideWidth = width - (horizontalMargin * 2)
        val maxGuideHeight = height * 0.85f

        var guideWidth = maxGuideWidth
        var guideHeight = guideWidth / CARD_ASPECT_RATIO

        if (guideHeight > maxGuideHeight) {
            guideHeight = maxGuideHeight
            guideWidth = guideHeight * CARD_ASPECT_RATIO
        }

        val left = (width - guideWidth) / 2f
        val top = (height - guideHeight) / 2f

        return RectF(left, top, left + guideWidth, top + guideHeight)
    }

    /** Guide rectangle in view coordinates, exposed so capture logic can check framing. */
    fun guideRectInViewCoordinates(): RectF = computeGuideRect()
}
