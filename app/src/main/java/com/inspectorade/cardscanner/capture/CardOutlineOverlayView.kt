package com.inspectorade.cardscanner.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

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

    private val dimPaint = Paint().apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val guideRect = computeGuideRect()

        // Dim everything outside the guide rectangle.
        canvas.drawRect(0f, 0f, width.toFloat(), guideRect.top, dimPaint)
        canvas.drawRect(0f, guideRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, guideRect.top, guideRect.left, guideRect.bottom, dimPaint)
        canvas.drawRect(guideRect.right, guideRect.top, width.toFloat(), guideRect.bottom, dimPaint)

        canvas.drawRect(guideRect, guidePaint)
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
