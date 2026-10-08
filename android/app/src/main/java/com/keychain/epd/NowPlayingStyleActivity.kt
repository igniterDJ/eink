package com.keychain.epd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class NowPlayingStyleActivity : ComponentActivity() {

    companion object {
        var selectedStyle: NowPlayingStyle? = null
    }

    private lateinit var gridView: GridView
    private lateinit var adapter: StyleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing_style)

        // Keep header/content below the status bar/notch.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }

        val currentName = intent.getStringExtra("current")
        val current = NowPlayingStyle.entries.find { it.name == currentName }

        val sampleArt = samplePlaceholderArt()
        val styles = NowPlayingStyle.entries
        val previews = styles.associateWith {
            val composite = NowPlayingRenderer.render(
                it,
                art = sampleArt,
                title = "Song Title",
                artist = "Artist Name"
            )
            ditherToDevicePalette(composite)
        }

        gridView = findViewById(R.id.gridStyles)
        adapter = StyleAdapter(styles, previews, current)
        gridView.adapter = adapter

        gridView.setOnItemClickListener { _, _, position, _ ->
            selectedStyle = styles.getOrNull(position)
            setResult(RESULT_OK)
            finish()
        }

        findViewById<Button>(R.id.btnClose).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private inner class StyleAdapter(
        private val styles: List<NowPlayingStyle>,
        private val previews: Map<NowPlayingStyle, Bitmap>,
        private val current: NowPlayingStyle?
    ) : BaseAdapter() {

        override fun getCount(): Int = styles.size
        override fun getItem(position: Int): NowPlayingStyle = styles[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val style = styles[position]
            val preview = previews[style]

            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@NowPlayingStyleActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = dp(8)
                setPadding(pad, pad, pad, pad)

                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )

                addView(ImageView(this@NowPlayingStyleActivity).apply {
                    id = View.generateViewId()
                    val imgW = dp(180)
                    val imgH = (imgW * NowPlayingRenderer.HEIGHT / NowPlayingRenderer.WIDTH.toFloat()).toInt()
                    layoutParams = LinearLayout.LayoutParams(imgW, imgH)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.parseColor("#B0B8C1"))
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                })

                addView(TextView(this@NowPlayingStyleActivity).apply {
                    id = View.generateViewId()
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(8)
                    }
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(Color.parseColor("#1E2A3A"))
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                })
            }

            val imageView = cell.getChildAt(0) as ImageView
            val textView = cell.getChildAt(1) as TextView

            if (preview != null) {
                imageView.setImageBitmap(preview)
            } else {
                imageView.setImageDrawable(null)
            }
            textView.text = style.label

            val isCurrent = style == current
            if (isCurrent) {
                cell.setBackgroundColor(Color.parseColor("#D6EAF8"))
            } else {
                cell.setBackgroundColor(Color.TRANSPARENT)
            }

            return cell
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    // Runs a preview frame through the same Floyd-Steinberg dither the device
    // pipeline uses (ImageProcessor.process), so the picker shows the actual
    // black/white/red result instead of the full-color render.
    private fun ditherToDevicePalette(bitmap: Bitmap): Bitmap {
        val w = NowPlayingRenderer.WIDTH
        val h = NowPlayingRenderer.HEIGHT
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        val result = ImageProcessor.process(argb, w, h)
        val landscape = ImageProcessor.previewLandscape(result)
        return Bitmap.createBitmap(landscape, w, h, Bitmap.Config.ARGB_8888)
    }

    // Stand-in album cover so the Art +/- styles preview with an actual image
    // instead of a flat gray box; the real app fills this from the now-playing album art.
    // Black-to-red gradient so it dithers into a visible stipple pattern instead
    // of washing out to near-white under the 3-color palette.
    private fun samplePlaceholderArt(): Bitmap {
        val size = 240
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val gradient = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            Color.BLACK, Color.RED,
            Shader.TileMode.CLAMP
        )
        canvas.drawPaint(Paint().apply { shader = gradient })

        val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 255, 255)
            textSize = size * 0.45f
            textAlign = Paint.Align.CENTER
        }
        val metrics = notePaint.fontMetrics
        val noteY = size / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText("♪", size / 2f, noteY, notePaint)

        return bitmap
    }
}
