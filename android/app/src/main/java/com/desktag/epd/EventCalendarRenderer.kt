package com.desktag.epd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Renders a user-created CalendarEvent onto its category background, same 250x122
 * landscape output shape as CalendarRenderer.render() so it drops into the same
 * ImageProcessor -> HistoryStore -> BLE send pipeline (see MainActivity).
 */
object EventCalendarRenderer {
    const val WIDTH = 250
    const val HEIGHT = 122

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d")
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")

    // Strips "'s birthday"/"birthday"/"bday" (any case) off an event title to get
    // just the person's name, e.g. "Alex's Birthday" -> "Alex". Titles with no such
    // marker (user just typed a name) pass through unchanged.
    private val BIRTHDAY_SUFFIX = Regex("('s)?\\s*(birthday|bday)\\b", RegexOption.IGNORE_CASE)

    private fun birthdayName(title: String): String =
        BIRTHDAY_SUFFIX.replace(title, "").trim().trim('-', ':', ',').ifEmpty { title }

    fun render(context: Context, event: CalendarEvent, occursOn: LocalDate): Bitmap {
        val frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)

        val background = EventBackgrounds.load(context, event.category, event.backgroundIndex)
        canvas.drawBitmap(background, centerCropMatrix(background.width, background.height), null)

        val isBirthday = event.category == EventCategory.BIRTHDAY

        if (isBirthday) {
            drawBirthdayText(canvas, birthdayName(event.title))
            return frame
        }

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 20f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 14f
            textAlign = Paint.Align.CENTER
        }
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 14f
            textAlign = Paint.Align.CENTER
        }

        val timeText = formatTime(event.timeMinutes)

        // Pick a text box location by analyzing the already-cropped frame.
        val box = findSafeTextBox(frame, hasTime = (timeText != null))

        // White rounded panel behind text, so it stays readable without covering artwork-heavy areas.
        canvas.drawRoundRect(box, 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 255, 255)
        })
        canvas.drawRoundRect(box, 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.BLACK
        })

        val maxTextWidth = (box.width() - 14f).coerceAtLeast(10f)

        // 2 lines (title + date) or 3 lines (title + date + time)
        val cx = box.centerX()
        val titleY = box.top + 22f
        val dateY = box.top + 40f
        val timeY = box.top + 56f

        canvas.drawText(ellipsize(event.title, titlePaint, maxTextWidth), cx, titleY, titlePaint)
        canvas.drawText(ellipsize(occursOn.format(DATE_FORMAT), datePaint, maxTextWidth), cx, dateY, datePaint)
        if (timeText != null) {
            canvas.drawText(ellipsize(timeText, timePaint, maxTextWidth), cx, timeY, timePaint)
        }

        return frame
    }

    /** No box, just "Happy Birthday" on its own line and the name below it, centered
     *  over the cake/cupcake art with no background panel. */
    private fun drawBirthdayText(canvas: Canvas, name: String) {
        val maxTextWidth = (WIDTH - 20).toFloat()
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 17f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val cx = WIDTH / 2f
        canvas.drawText(ellipsize("Happy Birthday", linePaint, maxTextWidth), cx, HEIGHT / 2f - 30f, linePaint)
        canvas.drawText(ellipsize("$name!", linePaint, maxTextWidth), cx, HEIGHT / 2f - 4f, linePaint)
    }

    /**
     * Finds a low-ink region to place the text so we don't cover the baked-in icons
     * in the event backgrounds. Operates on the already-rendered 250x122 frame.
     */
    private fun findSafeTextBox(frame: Bitmap, hasTime: Boolean): RectF {
        val boxW = 190
        val boxH = if (hasTime) 58 else 42

        val xs = intArrayOf(10, (WIDTH - boxW) / 2, WIDTH - boxW - 10)
        val ys = intArrayOf(6, 18, 30, 42, HEIGHT - boxH - 6)

        val pixels = IntArray(WIDTH * HEIGHT)
        frame.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)

        fun inkScore(x: Int, y: Int): Float {
            var ink = 0
            val area = boxW * boxH
            var yy = y
            while (yy < y + boxH) {
                val row = yy * WIDTH
                var xx = x
                while (xx < x + boxW) {
                    val c = pixels[row + xx]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    // Treat near-white as empty. Backgrounds are mostly white with light gray shading.
                    if (r < 245 || g < 245 || b < 245) ink++
                    xx++
                }
                yy++
            }
            return ink.toFloat() / area.toFloat()
        }

        var bestX = xs[1]
        var bestY = ys[1]
        var best = Float.MAX_VALUE

        for (x in xs) {
            for (y in ys) {
                val s = inkScore(x, y)
                // Slight preference for center-ish placements when tie-ish.
                val centerBias = (abs((x + boxW / 2) - WIDTH / 2) + abs((y + boxH / 2) - HEIGHT / 2)) / 1000f
                val score = s + centerBias
                if (score < best) {
                    best = score
                    bestX = x
                    bestY = y
                }
            }
        }

        return RectF(bestX.toFloat(), bestY.toFloat(), (bestX + boxW).toFloat(), (bestY + boxH).toFloat())
    }

    private fun formatTime(timeMinutes: Int?): String? {
        if (timeMinutes == null) return null
        val h = (timeMinutes / 60).coerceIn(0, 23)
        val m = (timeMinutes % 60).coerceIn(0, 59)
        return LocalTime.of(h, m).format(TIME_FORMAT)
    }

    /** Scales+crops [srcW]x[srcH] to fill WIDTHxHEIGHT without distortion (like ImageView's centerCrop). */
    private fun centerCropMatrix(srcW: Int, srcH: Int): Matrix {
        val scale = maxOf(WIDTH.toFloat() / srcW, HEIGHT.toFloat() / srcH)
        val dx = (WIDTH - srcW * scale) / 2f
        val dy = (HEIGHT - srcH * scale) / 2f
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
    }

    // Matches CalendarRenderer.ellipsize / NowPlayingRenderer.ellipsize
    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }
}
