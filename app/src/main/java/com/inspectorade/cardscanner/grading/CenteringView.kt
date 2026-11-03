package com.inspectorade.cardscanner.grading

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Shows a flattened card with its outer (solid, card edge) and inner (dashed,
 * printed border) centering lines, in user-chosen [LineStyle] colors. Dragging a
 * line moves it, with a magnified loupe
 * around the touch point so a line can be placed precisely against the border
 * without the finger hiding it.
 */
class CenteringView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onLinesChanged: (() -> Unit)? = null

    private var bitmap: Bitmap? = null
    private var lines: BorderLines? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = 28 * density
    private val loupeRadius = 80 * density
    private val loupeZoom = 4f

    private val bitmapMatrix = Matrix()
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private var dragging: BorderLine? = null
    private var dragStartValue = 0f
    private var dragStartTouch = 0f
    private var touchX = 0f
    private var touchY = 0f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val outerPaint = linePaint(LineStyle.outerColor(context))
    private val innerPaint = linePaint(LineStyle.innerColor(context))
    private val linePath = Path()
    private val loupeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    private val loupeBackgroundPaint = Paint().apply { color = Color.BLACK }

    private fun linePaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
    }

    fun setContent(bitmap: Bitmap, lines: BorderLines) {
        this.bitmap = bitmap
        this.lines = lines
        updateTransform()
        invalidate()
    }

    fun currentLines(): BorderLines? = lines

    fun setLineColors(outer: Int, inner: Int) {
        outerPaint.color = outer
        innerPaint.color = inner
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTransform()
    }

    private fun updateTransform() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        offsetX = (width - bmp.width * scale) / 2f
        offsetY = (height - bmp.height * scale) / 2f
        bitmapMatrix.setScale(scale, scale)
        bitmapMatrix.postTranslate(offsetX, offsetY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawContent(canvas, strokeScale = 1f)
        if (dragging != null) drawLoupe(canvas)
    }

    private fun drawContent(canvas: Canvas, strokeScale: Float) {
        val bmp = bitmap ?: return
        val lns = lines ?: return
        canvas.drawBitmap(bmp, bitmapMatrix, bitmapPaint)

        val left = offsetX
        val top = offsetY
        val right = offsetX + bmp.width * scale
        val bottom = offsetY + bmp.height * scale

        // Inner lines are dashed so the two line types differ by pattern, not just
        // color. Dash lengths are divided by strokeScale so they look the same size
        // inside the zoomed loupe. Drawn as paths: dashed drawLine isn't reliably
        // hardware-accelerated on older API levels.
        val dash = DashPathEffect(floatArrayOf(10 * density / strokeScale, 6 * density / strokeScale), 0f)
        innerPaint.pathEffect = dash
        for (line in BorderLine.entries) {
            val paint = if (line.outer) outerPaint else innerPaint
            paint.strokeWidth = (if (line == dragging) 3f else 2f) * density / strokeScale
            linePath.reset()
            if (line.vertical) {
                val x = offsetX + lns[line] * scale
                linePath.moveTo(x, top)
                linePath.lineTo(x, bottom)
            } else {
                val y = offsetY + lns[line] * scale
                linePath.moveTo(left, y)
                linePath.lineTo(right, y)
            }
            canvas.drawPath(linePath, paint)
        }
    }

    /** Magnified view of the area under the finger, placed in the opposite top corner. */
    private fun drawLoupe(canvas: Canvas) {
        val cx = if (touchX < width / 2f) width - loupeRadius - 12 * density else loupeRadius + 12 * density
        val cy = loupeRadius + 12 * density

        canvas.save()
        val clip = Path().apply { addCircle(cx, cy, loupeRadius, Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawCircle(cx, cy, loupeRadius, loupeBackgroundPaint)
        canvas.translate(cx, cy)
        canvas.scale(loupeZoom, loupeZoom)
        canvas.translate(-touchX, -touchY)
        drawContent(canvas, strokeScale = loupeZoom)
        canvas.restore()

        canvas.drawCircle(cx, cy, loupeRadius, loupeBorderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val lns = lines ?: return false
        touchX = event.x
        touchY = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val line = nearestLine(lns, event.x, event.y) ?: return false
                dragging = line
                dragStartValue = lns[line]
                dragStartTouch = if (line.vertical) event.x else event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val line = dragging ?: return false
                val touch = if (line.vertical) event.x else event.y
                lns[line] = constrain(lns, line, dragStartValue + (touch - dragStartTouch) / scale)
                onLinesChanged?.invoke()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = null
                invalidate()
                return true
            }
        }
        return false
    }

    private fun nearestLine(lns: BorderLines, x: Float, y: Float): BorderLine? =
        BorderLine.entries
            .map { line ->
                val screen = if (line.vertical) offsetX + lns[line] * scale else offsetY + lns[line] * scale
                line to abs((if (line.vertical) x else y) - screen)
            }
            .filter { it.second <= touchSlop }
            .minByOrNull { it.second }
            ?.first

    /** Keeps each side ordered outer → inner → inner → outer and inside the image. */
    private fun constrain(lns: BorderLines, line: BorderLine, value: Float): Float {
        val bmp = bitmap ?: return value
        val gap = 1f
        val (low, high) = when (line) {
            BorderLine.OUTER_LEFT -> 0f to lns[BorderLine.INNER_LEFT] - gap
            BorderLine.INNER_LEFT -> lns[BorderLine.OUTER_LEFT] + gap to lns[BorderLine.INNER_RIGHT] - gap
            BorderLine.INNER_RIGHT -> lns[BorderLine.INNER_LEFT] + gap to lns[BorderLine.OUTER_RIGHT] - gap
            BorderLine.OUTER_RIGHT -> lns[BorderLine.INNER_RIGHT] + gap to bmp.width.toFloat()
            BorderLine.OUTER_TOP -> 0f to lns[BorderLine.INNER_TOP] - gap
            BorderLine.INNER_TOP -> lns[BorderLine.OUTER_TOP] + gap to lns[BorderLine.INNER_BOTTOM] - gap
            BorderLine.INNER_BOTTOM -> lns[BorderLine.INNER_TOP] + gap to lns[BorderLine.OUTER_BOTTOM] - gap
            BorderLine.OUTER_BOTTOM -> lns[BorderLine.INNER_BOTTOM] + gap to bmp.height.toFloat()
        }
        return value.coerceIn(low, high)
    }
}
