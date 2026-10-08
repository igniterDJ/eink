package com.desktag.epd

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One independent text layer inside TextCanvasView's multi-text canvas. */
data class TextItem(
    var text: String,
    var centerX: Float,
    var centerY: Float,
    var scale: Float = 1f,
    var rotationDegrees: Float = 0f,
    var color: Int
)

/**
 * Pure geometry helpers for TextCanvasView's tap-to-select and two-finger
 * rotate+scale gestures. Kept free of android.graphics.* so it can run as a
 * plain JUnit test on the JVM (see TextGeometryTest).
 */
object TextGeometry {

    /** Angle in degrees of the vector from (x0,y0) to (x1,y1). Matches Canvas.rotate's
     *  clockwise-positive convention, since Y grows downward on a View's canvas. */
    fun angleDegrees(x0: Float, y0: Float, x1: Float, y1: Float): Float =
        Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()

    fun distance(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * True if (px,py) falls inside [item]'s bounding box (half-size halfW x halfH,
     * centered on the item, rotated by item.rotationDegrees) — i.e. hit-testing by
     * rotating the point into the item's own unrotated local space.
     */
    fun hitTest(item: TextItem, px: Float, py: Float, halfW: Float, halfH: Float): Boolean {
        val dx = px - item.centerX
        val dy = py - item.centerY
        val rad = Math.toRadians(-item.rotationDegrees.toDouble())
        val cosR = cos(rad).toFloat()
        val sinR = sin(rad).toFloat()
        val localX = dx * cosR - dy * sinR
        val localY = dx * sinR + dy * cosR
        return localX >= -halfW && localX <= halfW && localY >= -halfH && localY <= halfH
    }
}
