package com.desktag.epd

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.CalendarContract

data class UpcomingEvent(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val inProgress: Boolean
)

/**
 * Reads the soonest calendar instance across all visible calendars, on-device only
 * (CalendarContract.Instances, no network/OAuth). Not pure-JVM like ImageProcessor
 * since it needs a ContentResolver, but kept free of UI/BLE concerns.
 */
object CalendarSource {
    private val PROJECTION = arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.VISIBLE
    )

    /**
     * @throws SecurityException if READ_CALENDAR is not granted (caller's responsibility
     * to have requested it; this can still race if revoked mid-session).
     */
    fun nextEvent(contentResolver: ContentResolver, lookaheadMillis: Long): UpcomingEvent? {
        val now = System.currentTimeMillis()
        val windowEnd = now + lookaheadMillis

        // Instances.CONTENT_URI + appendId(begin, end) is what the Instances.query()
        // convenience helper does internally; built manually here so a selection
        // (VISIBLE = 1) can still be applied, which that helper doesn't support.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, now)
            ContentUris.appendId(this, windowEnd)
        }.build()

        val selection = "${CalendarContract.Instances.VISIBLE} = 1"

        contentResolver.query(uri, PROJECTION, selection, null, "${CalendarContract.Instances.BEGIN} ASC")
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val title = cursor.getString(cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE))
                        ?: "(No title)"
                    val begin = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN))
                    val end = cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Instances.END))
                    val allDay = cursor.getInt(cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)) != 0

                    return UpcomingEvent(
                        title = title,
                        startMillis = begin,
                        endMillis = end,
                        allDay = allDay,
                        inProgress = begin <= now && now < end
                    )
                }
            }
        return null
    }
}
