package com.keychain.epd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Multi-layer text editor: letterboxes a fixed 250:122 viewport rect inside this
 * View's bounds. Each TextItem is independently selectable, draggable, and
 * pinch-scaled/rotated (two-finger gesture, like Instagram/Canva text tools), then
 * the visible viewport is rendered as an exact 250x122 ARGB_8888 bitmap.
 */
class TextCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val ASPECT_W = 250f
        private const val ASPECT_H = 122f
        private const val BASE_TEXT_SIZE = 28f
        private const val MIN_SCALE = 0.3f
        private const val MAX_SCALE = 6f
        private const val HIT_PADDING = 16f
    }

    private enum class Mode { NONE, DRAG, TRANSFORM }

    private val viewportRect = RectF()

    private val items = mutableListOf<TextItem>()
    private var selectedIndex: Int? = null
    var onSelectionChanged: ((TextItem?) -> Unit)? = null

    private var backgroundFillColor = Color.WHITE
    private var backgroundImage: Bitmap? = null
    private val backgroundImageDestRect = RectF()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val backgroundPaint = Paint().apply { color = backgroundFillColor }
    private val backgroundImagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#2196F3") // editor chrome only; never rendered into renderResult()
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private var mode = Mode.NONE
    private var pointerId0 = MotionEvent.INVALID_POINTER_ID
    private var pointerId1 = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var prevDist = 0f
    private var prevAngle = 0f

    fun setBackgroundFillColor(color: Int) {
        backgroundFillColor = color
        backgroundPaint.color = color
        backgroundImage = null
        invalidate()
    }

    /** Sets a photo as the canvas background (stretched to fill), replacing the flat fill color. */
    fun setBackgroundImage(bitmap: Bitmap) {
        backgroundImage = bitmap
        invalidate()
    }

    /** Adds a new empty text layer centered in the viewport and selects it. */
    fun addItem(color: Int) {
        val cx = if (!viewportRect.isEmpty) viewportRect.centerX() else width / 2f
        val cy = if (!viewportRect.isEmpty) viewportRect.centerY() else height / 2f
        items.add(TextItem(text = "", centerX = cx, centerY = cy, color = color))
        selectItem(items.size - 1)
    }

    fun setSelectedText(text: String) {
        selectedItem()?.text = text
        invalidate()
    }

    fun setSelectedColor(color: Int) {
        selectedItem()?.color = color
        invalidate()
    }

    fun deleteSelected() {
        val idx = selectedIndex ?: return
        items.removeAt(idx)
        selectItem(null)
    }

    private fun selectedItem(): TextItem? = selectedIndex?.let { items.getOrNull(it) }

    private fun selectItem(index: Int?) {
        selectedIndex = index
        invalidate()
        onSelectionChanged?.invoke(selectedItem())
    }

    /** Renders the exact visible viewport content (all text layers) as a 250x122 ARGB_8888 bitmap. */
    fun renderResult(): Bitmap {
        val output = Bitmap.createBitmap(250, 122, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val bg = backgroundImage
        if (bg != null) {
            canvas.drawBitmap(bg, null, RectF(0f, 0f, ASPECT_W, ASPECT_H), backgroundImagePaint)
        } else {
            canvas.drawColor(backgroundFillColor)
        }

        if (viewportRect.width() > 0f) {
            val outputScale = ASPECT_W / viewportRect.width()
            for (item in items) {
                val cx = (item.centerX - viewportRect.left) * outputScale
                val cy = (item.centerY - viewportRect.top) * outputScale
                drawTextAt(canvas, item.text, cx, cy, item.rotationDegrees, item.color, BASE_TEXT_SIZE * item.scale * outputScale)
            }
        }
        return output
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeViewportRect(w.toFloat(), h.toFloat())
    }

    private fun computeViewportRect(w: Float, h: Float) {
        val targetAspect = ASPECT_W / ASPECT_H
        var vw = w
        var vh = vw / targetAspect
        if (vh > h) {
            vh = h
            vw = vh * targetAspect
        }
        val left = (w - vw) / 2f
        val top = (h - vh) / 2f
        viewportRect.set(left, top, left + vw, top + vh)
    }

    private fun drawTextAt(canvas: Canvas, text: String, cx: Float, cy: Float, rotationDegrees: Float, color: Int, textSizePx: Float) {
        if (text.isEmpty()) return
        textPaint.textSize = textSizePx
        textPaint.color = color
        canvas.save()
        canvas.rotate(rotationDegrees, cx, cy)
        val fm = textPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, cx, baseline, textPaint)
        canvas.restore()
    }

    /** Half-extent (halfW, halfH) of [item]'s tappable/selection box, in view-space pixels. */
    private fun itemHalfExtent(item: TextItem, viewScale: Float): Pair<Float, Float> {
        textPaint.textSize = BASE_TEXT_SIZE * item.scale * viewScale
        val halfW = textPaint.measureText(item.text) / 2f + HIT_PADDING
        val fm = textPaint.fontMetrics
        val halfH = (fm.descent - fm.ascent) / 2f + HIT_PADDING
        return halfW to halfH
    }

    private fun hitTestIndexAt(x: Float, y: Float): Int? {
        val viewScale = if (viewportRect.width() > 0f) viewportRect.width() / ASPECT_W else 1f
        for (i in items.indices.reversed()) {
            val item = items[i]
            val (halfW, halfH) = itemHalfExtent(item, viewScale)
            if (TextGeometry.hitTest(item, x, y, halfW, halfH)) return i
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId0 = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                selectItem(hitTestIndexAt(lastX, lastY))
                mode = Mode.DRAG
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2 && selectedItem() != null) {
                    pointerId0 = event.getPointerId(0)
                    pointerId1 = event.getPointerId(1)
                    val i0 = event.findPointerIndex(pointerId0)
                    val i1 = event.findPointerIndex(pointerId1)
                    prevDist = TextGeometry.distance(event.getX(i0), event.getY(i0), event.getX(i1), event.getY(i1))
                    prevAngle = TextGeometry.angleDegrees(event.getX(i0), event.getY(i0), event.getX(i1), event.getY(i1))
                    mode = Mode.TRANSFORM
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val item = selectedItem()
                if (item != null) {
                    when (mode) {
                        Mode.DRAG -> {
                            val idx = event.findPointerIndex(pointerId0)
                            if (idx != -1) {
                                val x = event.getX(idx)
                                val y = event.getY(idx)
                                item.centerX += x - lastX
                                item.centerY += y - lastY
                                lastX = x
                                lastY = y
                                invalidate()
                            }
                        }
                        Mode.TRANSFORM -> {
                            val i0 = event.findPointerIndex(pointerId0)
                            val i1 = event.findPointerIndex(pointerId1)
                            if (i0 != -1 && i1 != -1) {
                                val x0 = event.getX(i0); val y0 = event.getY(i0)
                                val x1 = event.getX(i1); val y1 = event.getY(i1)
                                val curDist = TextGeometry.distance(x0, y0, x1, y1)
                                val curAngle = TextGeometry.angleDegrees(x0, y0, x1, y1)
                                if (prevDist > 0f) {
                                    item.scale = (item.scale * (curDist / prevDist)).coerceIn(MIN_SCALE, MAX_SCALE)
                                }
                                item.rotationDegrees += curAngle - prevAngle
                                prevDist = curDist
                                prevAngle = curAngle
                                invalidate()
                            }
                        }
                        Mode.NONE -> {}
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.TRANSFORM) {
                    val liftedId = event.getPointerId(event.actionIndex)
                    val remainingId = if (liftedId == pointerId0) pointerId1 else pointerId0
                    val idx = event.findPointerIndex(remainingId)
                    if (idx != -1) {
                        lastX = event.getX(idx)
                        lastY = event.getY(idx)
                    }
                    pointerId0 = remainingId
                    mode = Mode.DRAG
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = Mode.NONE
                pointerId0 = MotionEvent.INVALID_POINTER_ID
                pointerId1 = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (viewportRect.isEmpty) return

        val bg = backgroundImage
        if (bg != null) {
            backgroundImageDestRect.set(viewportRect)
            canvas.drawBitmap(bg, null, backgroundImageDestRect, backgroundImagePaint)
        } else {
            canvas.drawRect(viewportRect, backgroundPaint)
        }

        canvas.save()
        canvas.clipRect(viewportRect)
        val viewScale = viewportRect.width() / ASPECT_W
        for (item in items) {
            drawTextAt(canvas, item.text, item.centerX, item.centerY, item.rotationDegrees, item.color, BASE_TEXT_SIZE * item.scale * viewScale)
        }
        selectedItem()?.let { drawSelectionBox(canvas, it, viewScale) }
        canvas.restore()

        canvas.save()
        canvas.clipOutRect(viewportRect)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        canvas.restore()

        canvas.drawRect(viewportRect, borderPaint)
    }

    private fun drawSelectionBox(canvas: Canvas, item: TextItem, viewScale: Float) {
        val (halfW, halfH) = itemHalfExtent(item, viewScale)
        canvas.save()
        canvas.rotate(item.rotationDegrees, item.centerX, item.centerY)
        canvas.drawRect(
            item.centerX - halfW, item.centerY - halfH,
            item.centerX + halfW, item.centerY + halfH,
            selectionPaint
        )
        canvas.restore()
    }
}
