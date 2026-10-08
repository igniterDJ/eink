package com.desktag.epd

import java.time.LocalDate

enum class EventCategory(val label: String, val assetPrefix: String) {
    ANNIVERSARY("Anniversary", "Anniversary"),
    BIRTHDAY("Birthday", "Birthday"),
    FRIENDS("Friends", "Friends"),
    HEALTH("Health", "health"),
    TRAVEL("Travel", "Travel")
}

enum class Recurrence(val label: String) {
    ONE_TIME("One-time"),
    YEARLY("Every year")
}

data class CalendarEvent(
    val id: Long,
    val date: LocalDate,
    val title: String,
    val category: EventCategory,
    val recurrence: Recurrence,
    val backgroundIndex: Int = 0,
    /**
     * Local time-of-day for when this event should show.
     *
     * Stored as minutes since midnight; null means "All day".
     */
    val timeMinutes: Int? = null,
    // Optional metadata for imported events (e.g. Google Calendar via CalendarContract).
    val source: String? = null,
    val sourceEventId: Long? = null,
    val sourceBeginMillis: Long? = null
) {
    /**
     * The next date on/after [from] this event falls on. One-time events occur once,
     * on [date] itself, and stop existing (return null) after that. Yearly events
     * re-project [date]'s month/day onto [from]'s year (or the next year if that
     * month/day has already passed in [from]'s year) — Feb 29 falls back to Feb 28
     * in a non-leap year rather than throwing.
     */
    fun nextOccurrenceOnOrAfter(from: LocalDate): LocalDate? {
        if (recurrence == Recurrence.ONE_TIME) {
            return if (!date.isBefore(from)) date else null
        }

        val thisYear = safeWithYear(from.year)
        return if (!thisYear.isBefore(from)) thisYear else safeWithYear(from.year + 1)
    }

    private fun safeWithYear(year: Int): LocalDate {
        val maxDay = date.month.length(java.time.Year.isLeap(year.toLong()))
        val day = minOf(date.dayOfMonth, maxDay)
        return LocalDate.of(year, date.month, day)
    }
}
