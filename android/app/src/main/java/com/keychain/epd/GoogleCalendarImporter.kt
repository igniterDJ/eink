package com.keychain.epd

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Imports events from the phone's Calendar provider.
 *
 * This is "Google Calendar" only insofar as the user's Google account is syncing
 * Calendar to the device. We intentionally do not use Google APIs or OAuth.
 */
object GoogleCalendarImporter {
    private const val SOURCE = "google_calendar"

    private val PROJECTION = arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.VISIBLE
    )

    /**
     * @return number of new events added to CalendarEventStore
     * @throws SecurityException if READ_CALENDAR is not granted
     */
    fun importNextYear(
        contentResolver: ContentResolver,
        existing: List<CalendarEvent>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Pair<Int, List<CalendarEvent>> {
        val now = System.currentTimeMillis()
        val windowEnd = now + 365L * 24 * 60 * 60 * 1000

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, now)
            ContentUris.appendId(this, windowEnd)
        }.build()

        val selection = "${CalendarContract.Instances.VISIBLE} = 1"

        val existingKeys = existing
            .asSequence()
            .filter { it.source == SOURCE }
            .mapNotNull { e ->
                val id = e.sourceEventId ?: return@mapNotNull null
                val begin = e.sourceBeginMillis ?: return@mapNotNull null
                id to begin
            }
            .toHashSet()

        val imported = mutableListOf<CalendarEvent>()
        contentResolver.query(uri, PROJECTION, selection, null, "${CalendarContract.Instances.BEGIN} ASC")
            ?.use { cursor ->
                val colId = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val colTitle = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val colBegin = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val colAllDay = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)

                while (cursor.moveToNext()) {
                    val eventId = cursor.getLong(colId)
                    val beginMillis = cursor.getLong(colBegin)
                    if ((eventId to beginMillis) in existingKeys) continue

                    val title = cursor.getString(colTitle)?.trim().orEmpty().ifEmpty { "(No title)" }
                    val allDay = cursor.getInt(colAllDay) != 0

                    // Import only birthdays; ignore all other calendar events for now.
                    val isBirthday = title.contains("birthday", ignoreCase = true) ||
                        title.contains("bday", ignoreCase = true)
                    if (!isBirthday) continue

                    val date = if (allDay) {
                        // All-day instances are already midnight-aligned in UTC for the calendar provider.
                        Instant.ofEpochMilli(beginMillis).atZone(zoneId).toLocalDate()
                    } else {
                        Instant.ofEpochMilli(beginMillis).atZone(zoneId).toLocalDate()
                    }

                    imported.add(
                        CalendarEvent(
                            id = 0L,
                            date = date,
                            title = title,
                            category = EventCategory.BIRTHDAY,
                            recurrence = Recurrence.ONE_TIME,
                            backgroundIndex = 0,
                            source = SOURCE,
                            sourceEventId = eventId,
                            sourceBeginMillis = beginMillis
                        )
                    )
                }
            }

        if (imported.isEmpty()) return 0 to existing

        // De-dupe within this import pass too (same eventId/beginMillis can appear from some providers).
        val unique = imported
            .distinctBy { (it.sourceEventId ?: 0L) to (it.sourceBeginMillis ?: 0L) }

        val merged = existing.toMutableList().apply { addAll(unique) }
        return unique.size to merged
    }
}
