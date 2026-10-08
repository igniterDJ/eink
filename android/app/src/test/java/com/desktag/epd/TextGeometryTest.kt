package com.desktag.epd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextGeometryTest {

    @Test
    fun angleDegrees_pointingRight_isZero() {
        assertEquals(0f, TextGeometry.angleDegrees(0f, 0f, 10f, 0f), 0.01f)
    }

    @Test
    fun angleDegrees_pointingDown_is90() {
        assertEquals(90f, TextGeometry.angleDegrees(0f, 0f, 0f, 10f), 0.01f)
    }

    @Test
    fun distance_pythagorean() {
        assertEquals(5f, TextGeometry.distance(0f, 0f, 3f, 4f), 0.001f)
    }

    @Test
    fun hitTest_unrotated_insideAndOutside() {
        val item = TextItem("hi", centerX = 100f, centerY = 50f, color = 0)
        assertTrue(TextGeometry.hitTest(item, 105f, 55f, halfW = 20f, halfH = 10f))
        assertFalse(TextGeometry.hitTest(item, 200f, 55f, halfW = 20f, halfH = 10f))
    }

    @Test
    fun hitTest_rotated90_swapsAxes() {
        // A wide, short box (halfW=30, halfH=5) rotated 90 degrees stands up: a point
        // that's outside the unrotated box vertically now lands inside it, and vice versa.
        val item = TextItem("hi", centerX = 0f, centerY = 0f, rotationDegrees = 90f, color = 0)
        assertTrue(TextGeometry.hitTest(item, 0f, 25f, halfW = 30f, halfH = 5f))
        assertFalse(TextGeometry.hitTest(item, 25f, 0f, halfW = 30f, halfH = 5f))
    }
}
