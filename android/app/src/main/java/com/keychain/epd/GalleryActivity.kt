package com.keychain.epd

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class GalleryActivity : ComponentActivity() {

    companion object {
        var selectedFrame: ByteArray? = null
        var selectedThumbnail: Bitmap? = null
    }

    private lateinit var gridView: GridView
    private lateinit var txtEmpty: TextView
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        // Keep header/content below the status bar/notch.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }

        gridView = findViewById(R.id.gridHistory)
        txtEmpty = findViewById(R.id.txtEmpty)

        adapter = HistoryAdapter(HistoryStore.list(this).toMutableList())
        gridView.adapter = adapter
        updateEmptyState()

        gridView.setOnItemClickListener { _, _, position, _ ->
            val entry = adapter.getItem(position)
            selectedFrame = HistoryStore.loadFrame(entry)
            selectedThumbnail = HistoryStore.loadThumbnail(entry)
            setResult(RESULT_OK)
            finish()
        }

        gridView.setOnItemLongClickListener { _, _, position, _ ->
            val entry = adapter.getItem(position)
            val starred = HistoryStore.isStarred(entry)
            AlertDialog.Builder(this)
                .setTitle("Image options")
                .setPositiveButton("Delete") { _, _ ->
                    HistoryStore.delete(entry)
                    adapter.remove(entry)
                    updateEmptyState()
                }
                .setNeutralButton(if (starred) "Unstar" else "Star") { _, _ ->
                    HistoryStore.setStarred(entry, !starred)
                    adapter.refresh(HistoryStore.list(this))
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        findViewById<Button>(R.id.btnClose).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun updateEmptyState() {
        txtEmpty.visibility = if (adapter.entriesEmpty) View.VISIBLE else View.GONE
    }

    private inner class HistoryAdapter(
        private var entries: MutableList<HistoryStore.HistoryEntry>
    ) : BaseAdapter() {

        val entriesEmpty: Boolean get() = entries.isEmpty()

        fun remove(entry: HistoryStore.HistoryEntry) {
            entries.remove(entry)
            notifyDataSetChanged()
        }

        fun refresh(newEntries: List<HistoryStore.HistoryEntry>) {
            entries = newEntries.toMutableList()
            notifyDataSetChanged()
        }

        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): HistoryStore.HistoryEntry = entries[position]
        override fun getItemId(position: Int): Long = entries[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val entry = entries[position]

            val cell = (convertView as? FrameLayout) ?: FrameLayout(this@GalleryActivity).apply {
                val heightPx = (110 * resources.displayMetrics.density).toInt()
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)
                addView(ImageView(this@GalleryActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(0xFFE3E7EC.toInt())
                })
                addView(TextView(this@GalleryActivity).apply {
                    text = "★"
                    setTextColor(Color.YELLOW)
                    typeface = Typeface.DEFAULT_BOLD
                    setShadowLayer(3f, 0f, 0f, Color.BLACK)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.END
                    )
                })
            }

            (cell.getChildAt(0) as ImageView).setImageBitmap(HistoryStore.loadThumbnail(entry))
            cell.getChildAt(1).visibility = if (HistoryStore.isStarred(entry)) View.VISIBLE else View.GONE
            return cell
        }
    }
}
