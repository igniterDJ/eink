package com.desktag.epd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

enum class NowPlayingStyle(val label: String) {
    NONE("None"),
    TEXT_ONLY("Text Only"),
    ART_AND_TEXT("Art + Text"),
    ART_ONLY("Art Only"),
    ART_SIDE_TEXT("Art + Side Text")
}

object NowPlayingRenderer {
    const val WIDTH = 250
    const val HEIGHT = 122

    fun render(style: NowPlayingStyle, art: Bitmap?, title: String, artist: String): Bitmap {
        return when (style) {
            NowPlayingStyle.NONE -> renderNone()
            NowPlayingStyle.TEXT_ONLY -> renderTextOnly(title, artist)
            NowPlayingStyle.ART_AND_TEXT -> renderArtAndText(art, title, artist)
            NowPlayingStyle.ART_ONLY -> renderArtOnly(art, title)
            NowPlayingStyle.ART_SIDE_TEXT -> renderArtSideText(art, title, artist)
        }
    }

    // Preview-only: the actual poll loop skips rendering/sending entirely when NONE
    // is selected, so this is only ever seen in the style picker.
    private fun renderNone(): Bitmap {
        val frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 20f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Display not updated", WIDTH / 2f, HEIGHT / 2f + 7f, paint)

        return frame
    }

    private fun renderTextOnly(title: String, artist: String): Bitmap {
        val frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        canvas.drawColor(Color.WHITE)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 28f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 18f
            textAlign = Paint.Align.CENTER
        }
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        val maxTextWidth = (WIDTH - 20).toFloat()
        val titleText = ellipsize(title, titlePaint, maxTextWidth)
        val artistText = ellipsize(artist, artistPaint, maxTextWidth)

        val titleBaseline = 52f
        val dividerY = 66f
        val artistBaseline = 98f

        canvas.drawText(titleText, (WIDTH / 2f), titleBaseline, titlePaint)
        canvas.drawLine(24f, dividerY, (WIDTH - 24).toFloat(), dividerY, dividerPaint)
        canvas.drawText(artistText, (WIDTH / 2f), artistBaseline, artistPaint)

        return frame
    }

    // Port of MainActivity.compositeNowPlayingFrame (same look)
    private fun renderArtAndText(art: Bitmap?, title: String, artist: String): Bitmap {
        val frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)

        if (art != null) {
            val scaled = centerCropToFrame(art, WIDTH, HEIGHT)
            canvas.drawBitmap(scaled, 0f, 0f, null)
            if (scaled != art) {
                scaled.recycle()
            }
        } else {
            canvas.drawColor(Color.rgb(40, 40, 40))
        }

        // Semi-transparent dark bar at the bottom for text legibility
        val barHeight = 44
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(100, 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawRect(
            0f,
            (HEIGHT - barHeight).toFloat(),
            WIDTH.toFloat(),
            HEIGHT.toFloat(),
            barPaint
        )

        // Text: title (bold white) and artist (smaller, lighter)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 19f
            isFakeBoldText = true
        }
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255)
            textSize = 15f
        }

        val maxTextWidth = (WIDTH - 16).toFloat()
        val titleText = ellipsize(title, titlePaint, maxTextWidth)
        val artistText = ellipsize(artist, artistPaint, maxTextWidth)

        val textX = 8f
        val titleY = (HEIGHT - barHeight + 16).toFloat()
        val artistY = (HEIGHT - barHeight + 34).toFloat()

        canvas.drawText(titleText, textX, titleY, titlePaint)
        canvas.drawText(artistText, textX, artistY, artistPaint)

        return frame
    }

    private fun renderArtOnly(art: Bitmap?, title: String): Bitmap {
        val frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)

        if (art != null) {
            val scaled = centerCropToFrame(art, WIDTH, HEIGHT)
            canvas.drawBitmap(scaled, 0f, 0f, null)
            if (scaled != art) {
                scaled.recycle()
            }
            return frame
        }

        // Fallback: no art
        canvas.drawColor(Color.rgb(240, 240, 240))
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 22f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val text = ellipsize(title, titlePaint, (WIDTH - 20).toFloat())
        canvas.drawText(text, (WIDTH / 2f), 70f, titlePaint)
        return frame
    }

    private fun renderArtSideText(art: Bitmap?, title: String, artist: String): Bitmap {
        val frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)

        val artW = 110
        val textLeft = artW + 10f

        // Right background
        canvas.drawColor(Color.rgb(245, 245, 245))

        // Left: art (or gray block)
        if (art != null) {
            val leftArt = centerCropToFrame(art, artW, HEIGHT)
            canvas.drawBitmap(leftArt, 0f, 0f, null)
            if (leftArt != art) {
                leftArt.recycle()
            }
        } else {
            val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(180, 180, 180) }
            canvas.drawRect(0f, 0f, artW.toFloat(), HEIGHT.toFloat(), blockPaint)
        }

        // Optional separator
        val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 0, 0, 0)
            strokeWidth = 1f
        }
        canvas.drawLine(artW.toFloat(), 0f, artW.toFloat(), HEIGHT.toFloat(), sepPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 18f
            isFakeBoldText = true
            textAlign = Paint.Align.LEFT
        }
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 14f
            textAlign = Paint.Align.LEFT
        }

        val maxTextWidth = (WIDTH - textLeft - 8f)
        val titleText = ellipsize(title, titlePaint, maxTextWidth)
        val artistText = ellipsize(artist, artistPaint, maxTextWidth)

        canvas.drawText(titleText, textLeft, 52f, titlePaint)
        canvas.drawText(artistText, textLeft, 76f, artistPaint)

        return frame
    }

    // Matches MainActivity.centerCropToFrame
    private fun centerCropToFrame(source: Bitmap, dstW: Int, dstH: Int): Bitmap {
        val srcW = source.width
        val srcH = source.height

        val scale = Math.max(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        // Float rounding can leave scaledW/H a pixel short of dstW/H (e.g. srcW=240,
        // dstW=250), which crashes the crop below; clamp up to guarantee a valid crop.
        val scaledW = Math.max(dstW, (srcW * scale).toInt())
        val scaledH = Math.max(dstH, (srcH * scale).toInt())

        val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)

        val cropX = (scaledW - dstW) / 2
        val cropY = (scaledH - dstH) / 2

        return Bitmap.createBitmap(scaled, cropX, cropY, dstW, dstH)
    }

    // Matches MainActivity.ellipsize
    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "\u2026") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "\u2026"
    }
}
