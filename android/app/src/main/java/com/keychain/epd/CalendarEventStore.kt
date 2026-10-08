package com.keychain.epd

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Persists user-created calendar events as a single JSON array file, same on-disk
 * philosophy as HistoryStore (flat file in filesDir, no DB dependency) but wholesale
 * load/save since the expected dataset (a person's own tagged events) is small,
 * unlike the potentially-large image history.
 */
object CalendarEventStore {
    private const val TAG = "CalendarEventStore"
    private const val FILE_NAME = "calendar_events.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun list(context: Context): List<CalendarEvent> = load(context)

    fun eventsOn(context: Context, date: LocalDate): List<CalendarEvent> =
        load(context).filter { it.nextOccurrenceOnOrAfter(date) == date }

    /** Soonest occurrence across all events, at or after [from]; null if there are none. */
    fun nextUpcoming(context: Context, from: LocalDate): Pair<CalendarEvent, LocalDate>? =
        load(context)
            .mapNotNull { event -> event.nextOccurrenceOnOrAfter(from)?.let { event to it } }
            .minByOrNull { it.second }

    fun add(context: Context, event: CalendarEvent): CalendarEvent {
        val events = load(context)
        val withId = if (event.id != 0L) event else event.copy(id = System.currentTimeMillis())
        events.add(withId)
        save(context, events)
        return withId
    }

    fun update(context: Context, event: CalendarEvent) {
        val events = load(context)
        val index = events.indexOfFirst { it.id == event.id }
        if (index == -1) {
            Log.w(TAG, "update: no event with id ${event.id}, adding instead")
            events.add(event)
        } else {
            events[index] = event
        }
        save(context, events)
    }

    fun delete(context: Context, id: Long) {
        val events = load(context)
        events.removeAll { it.id == id }
        save(context, events)
    }

    /** Overwrite the on-disk list (used by bulk imports). Any event with id=0 gets a new unique id. */
    fun replaceAll(context: Context, events: List<CalendarEvent>) {
        val used = events.asSequence().map { it.id }.filter { it != 0L }.toHashSet()
        var next = System.currentTimeMillis()
        fun nextId(): Long {
            while (next in used) next++
            used.add(next)
            return next++
        }
        val withIds = events.map { if (it.id == 0L) it.copy(id = nextId()) else it }
        save(context, withIds)
    }

    /** Remove all events imported from a given [source] (e.g. "google_calendar"). */
    fun removeBySource(context: Context, source: String): Int {
        val events = load(context)
        val before = events.size
        events.removeAll { it.source == source }
        if (events.size != before) save(context, events)
        return before - events.size
    }

    private fun load(context: Context): MutableList<CalendarEvent> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return try {
            val array = JSONArray(f.readText())
            (0 until array.length()).mapNotNullTo(mutableListOf()) { i -> fromJson(array.getJSONObject(i)) }
        } catch (e: Exception) {
            Log.e(TAG, "load: failed to parse $FILE_NAME, treating as empty", e)
            mutableListOf()
        }
    }

    private fun save(context: Context, events: List<CalendarEvent>) {
        val array = JSONArray()
        events.forEach { array.put(toJson(it)) }
        file(context).writeText(array.toString())
    }

    private fun toJson(event: CalendarEvent): JSONObject = JSONObject().apply {
        put("id", event.id)
        put("date", event.date.toString()) // ISO-8601 yyyy-MM-dd
        put("title", event.title)
        put("category", event.category.name)
        put("recurrence", event.recurrence.name)
        put("backgroundIndex", event.backgroundIndex)

        // Optional time-of-day
        event.timeMinutes?.let { put("timeMinutes", it) }

        // Optional import metadata
        event.source?.let { put("source", it) }
        event.sourceEventId?.let { put("sourceEventId", it) }
        event.sourceBeginMillis?.let { put("sourceBeginMillis", it) }
    }

    private fun fromJson(json: JSONObject): CalendarEvent? = try {
        val source = json.optString("source", "").trim().ifEmpty { null }
        CalendarEvent(
            id = json.getLong("id"),
            date = LocalDate.parse(json.getString("date")),
            title = json.getString("title"),
            category = EventCategory.valueOf(json.getString("category")),
            recurrence = Recurrence.valueOf(json.getString("recurrence")),
            backgroundIndex = json.optInt("backgroundIndex", 0),
            timeMinutes = if (json.has("timeMinutes")) json.optInt("timeMinutes") else null,
            source = source,
            sourceEventId = if (json.has("sourceEventId")) json.optLong("sourceEventId") else null,
            sourceBeginMillis = if (json.has("sourceBeginMillis")) json.optLong("sourceBeginMillis") else null
        )
    } catch (e: Exception) {
        Log.w(TAG, "fromJson: skipping malformed entry", e)
        null
    }
}
