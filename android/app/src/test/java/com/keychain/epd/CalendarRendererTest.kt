package com.keychain.epd

import org.junit.Assert.assertEquals
import org.junit.Test

// CalendarRenderer.render(event: UpcomingEvent?) is not exercised here: it calls
// android.graphics.Bitmap.createBitmap / Canvas / Paint, which in a plain JVM unit test
// (no Robolectric — see android/app/build.gradle.kts, only junit:junit is a test
// dependency) are the unmocked Android stub jar and throw "not mocked" at runtime. Adding
// Robolectric was out of scope for this task (don't add a new dependency for this).
// The date/time formatting logic (formatTime) and the ellipsis logic (ellipsize) that
// would otherwise be worth unit testing are both private and have no android.graphics
// dependency internally, but they're unreachable from a test without either making them
// internal/public (an API change to CalendarRenderer.kt, which this task was told not to
// modify) or reflection (not the style used by the existing tests in this module).
// So the only pure-JVM-testable surface of this object is its public constants.
class CalendarRendererTest {

    @Test
    fun dimensions_matchTheEinkPanelNativeSize() {
        // 122 x 250 native per ARCHITECTURE.md; CalendarRenderer draws in landscape (250x122)
        // like NowPlayingRenderer, rotated to native by ImageProcessor downstream.
        assertEquals(250, CalendarRenderer.WIDTH)
        assertEquals(122, CalendarRenderer.HEIGHT)
    }
}
