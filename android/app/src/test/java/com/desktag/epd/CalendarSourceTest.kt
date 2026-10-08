package com.desktag.epd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// CalendarSource.nextEvent(contentResolver, ...) itself is NOT covered here: it takes an
// android.content.ContentResolver and queries CalendarContract.Instances, which are real
// Android framework types. This module has no Robolectric/Mockito dependency (see
// android/app/build.gradle.kts — only junit:junit), so there is no way to construct or fake
// a working ContentResolver/Cursor in a plain JVM unit test; calling the real Android jar's
// methods here would just throw "not mocked". That leaves UpcomingEvent's data class
// semantics as the pure-JVM-testable surface of this file.
class CalendarSourceTest {

    private fun sample(
        title: String = "Standup",
        start: Long = 1_000L,
        end: Long = 2_000L,
        allDay: Boolean = false,
        inProgress: Boolean = false
    ) = UpcomingEvent(title, start, end, allDay, inProgress)

    @Test
    fun fields_areExposedAsConstructed() {
        val event = sample(title = "Dentist", start = 100L, end = 200L, allDay = true, inProgress = true)
        assertEquals("Dentist", event.title)
        assertEquals(100L, event.startMillis)
        assertEquals(200L, event.endMillis)
        assertTrue(event.allDay)
        assertTrue(event.inProgress)
    }

    @Test
    fun copy_changesOnlyTheGivenField() {
        val original = sample()
        val renamed = original.copy(title = "Renamed")
        assertEquals("Renamed", renamed.title)
        assertEquals(original.startMillis, renamed.startMillis)
        assertEquals(original.endMillis, renamed.endMillis)
        assertEquals(original.allDay, renamed.allDay)
        assertEquals(original.inProgress, renamed.inProgress)
    }

    @Test
    fun equals_isStructural() {
        val a = sample()
        val b = sample()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun equals_detectsDifference() {
        val a = sample(inProgress = false)
        val b = sample(inProgress = true)
        assertNotEquals(a, b)
        assertFalse(a == b)
    }
}
