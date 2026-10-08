package com.desktag.epd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Shows a bitmap under a fixed 250:122 viewport rect (letterboxed to fit this View's
 * bounds). Supports pinch-zoom, drag-pan and 90-degree rotation, then renders the
 * visible viewport content as an exact 250x122 ARGB_8888 bitmap for ImageProcessor.
 */
class CropFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val MAX_ZOOM = 6f
        private const val ASPECT_W = 250f
        private const val ASPECT_H = 122f
    }

    var sourceBitmap: Bitmap? = null
        private set

    private val imageMatrix = Matrix()
    private val viewportRect = RectF()
    private var coverScale = 1f

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applyScale(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    fun setBitmap(bitmap: Bitmap) {
        sourceBitmap = bitmap
        if (!viewportRect.isEmpty) {
            resetTransform()
        }
        invalidate()
    }

    /** Rotates the source bitmap itself by [degrees] (90-degree steps) and resets pan/zoom. */
    fun rotate(degrees: Float) {
        val bitmap = sourceBitmap ?: return
        val m = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (rotated != bitmap) bitmap.recycle()
        sourceBitmap = rotated
        resetTransform()
    }

    /** Restores the default centered cover-fit transform for the current bitmap. */
    fun resetTransform() {
        val bitmap = sourceBitmap ?: return
        if (viewportRect.isEmpty) return

        coverScale = max(
            viewportRect.width() / bitmap.width,
            viewportRect.height() / bitmap.height
        )

        imageMatrix.reset()
        imageMatrix.postScale(coverScale, coverScale)
        val scaledW = bitmap.width * coverScale
        val scaledH = bitmap.height * coverScale
        val dx = viewportRect.centerX() - scaledW / 2f
        val dy = viewportRect.centerY() - scaledH / 2f
        imageMatrix.postTranslate(dx, dy)
        invalidate()
    }

    /** Renders the exact visible viewport content as a 250x122 ARGB_8888 bitmap. */
    fun renderResult(): Bitmap {
        val bitmap = sourceBitmap ?: throw IllegalStateException("No source bitmap set")
        val outputMatrix = Matrix(imageMatrix)
        outputMatrix.postTranslate(-viewportRect.left, -viewportRect.top)
        outputMatrix.postScale(ASPECT_W / viewportRect.width(), ASPECT_H / viewportRect.height())

        val output = Bitmap.createBitmap(250, 122, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(bitmap, outputMatrix, bitmapPaint)
        return output
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeViewportRect(w.toFloat(), h.toFloat())
        resetTransform()
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

    private fun applyScale(factor: Float, focusX: Float, focusY: Float) {
        if (sourceBitmap == null) return
        val currentScale = getCurrentScale()
        if (currentScale <= 0f) return
        val newScale = (currentScale * factor).coerceIn(coverScale, coverScale * MAX_ZOOM)
        val actualFactor = newScale / currentScale
        imageMatrix.postScale(actualFactor, actualFactor, focusX, focusY)
        clampTranslation()
        invalidate()
    }

    private fun getCurrentScale(): Float {
        val values = FloatArray(9)
        imageMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    /** Clamps translation so the mapped bitmap rect always fully contains the viewport rect. */
    private fun clampTranslation() {
        val bitmap = sourceBitmap ?: return
        val mapped = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        imageMatrix.mapRect(mapped)

        var dx = 0f
        var dy = 0f
        if (mapped.left > viewportRect.left) dx += viewportRect.left - mapped.left
        if (mapped.right < viewportRect.right) dx += viewportRect.right - mapped.right
        if (mapped.top > viewportRect.top) dy += viewportRect.top - mapped.top
        if (mapped.bottom < viewportRect.bottom) dy += viewportRect.bottom - mapped.bottom

        if (dx != 0f || dy != 0f) imageMatrix.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                activePointerId = event.getPointerId(0)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex != -1) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)
                        imageMatrix.postTranslate(x - lastTouchX, y - lastTouchY)
                        clampTranslation()
                        invalidate()
                        lastTouchX = x
                        lastTouchY = y
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    lastTouchX = event.getX(newIndex)
                    lastTouchY = event.getY(newIndex)
                    activePointerId = event.getPointerId(newIndex)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = sourceBitmap ?: return

        canvas.drawBitmap(bitmap, imageMatrix, bitmapPaint)

        canvas.save()
        canvas.clipOutRect(viewportRect)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        canvas.restore()

        canvas.drawRect(viewportRect, borderPaint)
    }

    private fun max(a: Float, b: Float) = if (a > b) a else b
}
