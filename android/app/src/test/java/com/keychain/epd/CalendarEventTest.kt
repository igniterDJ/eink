package com.keychain.epd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

// CalendarEvent.nextOccurrenceOnOrAfter is pure (java.time only, no Android framework
// types), so unlike CalendarSource/CalendarRenderer it's fully testable in this
// Robolectric-free, junit-only module (see CalendarSourceTest.kt/CalendarRendererTest.kt
// for why those two aren't).
class CalendarEventTest {

    private fun event(
        date: LocalDate,
        recurrence: Recurrence,
        category: EventCategory = EventCategory.BIRTHDAY
    ) = CalendarEvent(id = 1L, date = date, title = "Test", category = category, recurrence = recurrence)

    @Test
    fun oneTime_occursOnlyOnItsOwnDate() {
        val e = event(LocalDate.of(2026, 3, 10), Recurrence.ONE_TIME)
        assertEquals(LocalDate.of(2026, 3, 10), e.nextOccurrenceOnOrAfter(LocalDate.of(2026, 1, 1)))
        assertEquals(LocalDate.of(2026, 3, 10), e.nextOccurrenceOnOrAfter(LocalDate.of(2026, 3, 10)))
    }

    @Test
    fun oneTime_isNullOnceItsDateHasPassed() {
        val e = event(LocalDate.of(2026, 3, 10), Recurrence.ONE_TIME)
        assertNull(e.nextOccurrenceOnOrAfter(LocalDate.of(2026, 3, 11)))
    }

    @Test
    fun yearly_projectsOntoTheCurrentYearWhenNotYetPassed() {
        val e = event(LocalDate.of(2020, 6, 15), Recurrence.YEARLY)
        assertEquals(LocalDate.of(2026, 6, 15), e.nextOccurrenceOnOrAfter(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun yearly_rollsOverToNextYearOnceThisYearsDateHasPassed() {
        val e = event(LocalDate.of(2020, 6, 15), Recurrence.YEARLY)
        assertEquals(LocalDate.of(2027, 6, 15), e.nextOccurrenceOnOrAfter(LocalDate.of(2026, 6, 16)))
    }

    @Test
    fun yearly_onItsOwnAnniversaryDateReturnsThatSameDate() {
        val e = event(LocalDate.of(2020, 6, 15), Recurrence.YEARLY)
        assertEquals(LocalDate.of(2026, 6, 15), e.nextOccurrenceOnOrAfter(LocalDate.of(2026, 6, 15)))
    }

    @Test
    fun yearly_feb29FallsBackToFeb28InNonLeapYears() {
        val e = event(LocalDate.of(2020, 2, 29), Recurrence.YEARLY)
        assertEquals(LocalDate.of(2026, 2, 28), e.nextOccurrenceOnOrAfter(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun yearly_feb29LandsOnFeb29InALeapYear() {
        val e = event(LocalDate.of(2020, 2, 29), Recurrence.YEARLY)
        assertEquals(LocalDate.of(2028, 2, 29), e.nextOccurrenceOnOrAfter(LocalDate.of(2028, 1, 1)))
    }
}
