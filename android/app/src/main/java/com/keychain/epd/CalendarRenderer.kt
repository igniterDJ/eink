package com.keychain.epd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object CalendarRenderer {
    const val WIDTH = 250
    const val HEIGHT = 122

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")
    private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE")
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d")

    fun render(event: UpcomingEvent?): Bitmap {
        val frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        canvas.drawColor(Color.WHITE)

        if (event == null) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.GRAY
                textSize = 20f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("No upcoming events", WIDTH / 2f, HEIGHT / 2f + 7f, paint)
            return frame
        }

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 26f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            textSize = 18f
            textAlign = Paint.Align.CENTER
        }

        val maxTextWidth = (WIDTH - 20).toFloat()
        val titleText = ellipsize(event.title, titlePaint, maxTextWidth)
        val timeText = ellipsize(formatTime(event), timePaint, maxTextWidth)

        canvas.drawText(titleText, WIDTH / 2f, 58f, titlePaint)
        canvas.drawText(timeText, WIDTH / 2f, 88f, timePaint)

        return frame
    }

    // All-day BEGIN/END are UTC-midnight-aligned regardless of device timezone,
    // so all-day events are formatted from UTC, not the local zone.
    private fun formatTime(event: UpcomingEvent): String {
        val zone = ZoneId.systemDefault()

        if (event.allDay) {
            val date = LocalDate.ofInstant(Instant.ofEpochMilli(event.startMillis), ZoneId.of("UTC"))
            return if (date == LocalDate.now(zone)) "All day" else "All day — ${date.format(DATE_FORMAT)}"
        }

        val startTime = Instant.ofEpochMilli(event.startMillis).atZone(zone)
        val prefix = if (event.inProgress) "Now — " else ""
        val isToday = startTime.toLocalDate() == LocalDate.now(zone)

        return if (isToday) {
            "${prefix}Today ${startTime.format(TIME_FORMAT)}"
        } else {
            "$prefix${startTime.format(DAY_FORMAT)} ${startTime.format(TIME_FORMAT)}"
        }
    }

    // Matches NowPlayingRenderer.ellipsize
    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }
}
