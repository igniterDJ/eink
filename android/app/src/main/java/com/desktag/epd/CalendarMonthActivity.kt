package com.desktag.epd

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * In-app calendar: navigate months/years, tap a day to see/add its events. Doubles as
 * a picker back to MainActivity — selecting "Send to display" on an event returns a
 * rendered frame+thumbnail the same way GalleryActivity returns a history entry.
 */
class CalendarMonthActivity : ComponentActivity() {

    companion object {
        var selectedFrame: ByteArray? = null
        var selectedThumbnail: Bitmap? = null

        private const val TARGET_WIDTH = EventCalendarRenderer.WIDTH
        private const val TARGET_HEIGHT = EventCalendarRenderer.HEIGHT

        private val HEADER_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy")
    }

    private lateinit var txtMonthYear: TextView
    private lateinit var gridDays: GridView
    private lateinit var listEvents: ListView
    private lateinit var txtNoEvents: TextView

    private var currentMonth: YearMonth = YearMonth.now()
    private var selectedDate: LocalDate = LocalDate.now()
    private lateinit var dayAdapter: DayAdapter

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val addEventLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) refresh()
    }

    private val requestCalendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            importGoogleCalendar()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Calendar permission needed")
                .setMessage("Grant calendar access to import Google Calendar events.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar_month)

        // Keep header/content below the status bar/notch.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }

        txtMonthYear = findViewById(R.id.txtMonthYear)
        gridDays = findViewById(R.id.gridDays)
        listEvents = findViewById(R.id.listEvents)
        txtNoEvents = findViewById(R.id.txtNoEvents)

        findViewById<Button>(R.id.btnImportGoogleCalendar).setOnClickListener { importGoogleCalendar() }
        findViewById<Button>(R.id.btnRemoveImportedGoogleCalendar).setOnClickListener { confirmRemoveImportedGoogleCalendar() }

        dayAdapter = DayAdapter()
        gridDays.adapter = dayAdapter
        gridDays.setOnItemClickListener { _, _, position, _ ->
            val date = dayAdapter.dateAt(position)
            if (date.month != currentMonth.month || date.year != currentMonth.year) {
                currentMonth = YearMonth.of(date.year, date.month)
            }
            selectedDate = date
            refresh()
        }

        txtMonthYear.setOnClickListener { openMonthPicker() }
        findViewById<Button>(R.id.btnPrevMonth).setOnClickListener {
            currentMonth = currentMonth.minusMonths(1)
            refresh()
        }
        findViewById<Button>(R.id.btnNextMonth).setOnClickListener {
            currentMonth = currentMonth.plusMonths(1)
            refresh()
        }
        findViewById<Button>(R.id.btnAddEvent).setOnClickListener {
            addEventLauncher.launch(
                android.content.Intent(this, AddEventActivity::class.java)
                    .putExtra(AddEventActivity.EXTRA_DATE, selectedDate.toString())
            )
        }

        listEvents.setOnItemClickListener { _, _, position, _ ->
            val event = CalendarEventStore.eventsOn(this, selectedDate)[position]
            addEventLauncher.launch(
                android.content.Intent(this, AddEventActivity::class.java)
                    .putExtra(AddEventActivity.EXTRA_DATE, selectedDate.toString())
                    .putExtra(AddEventActivity.EXTRA_EVENT_ID, event.id)
            )
        }
        listEvents.setOnItemLongClickListener { _, _, position, _ ->
            val event = CalendarEventStore.eventsOn(this, selectedDate)[position]
            AlertDialog.Builder(this)
                .setTitle(event.title)
                .setItems(arrayOf("Send to display")) { _, _ -> sendToDisplay(event) }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
    }

    private fun importGoogleCalendar() {
        val perm = android.Manifest.permission.READ_CALENDAR
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            requestCalendarPermissionLauncher.launch(perm)
            return
        }

        ioExecutor.execute {
            try {
                val existing = CalendarEventStore.list(this)
                val (added, merged) = GoogleCalendarImporter.importNextYear(contentResolver, existing)

                if (added > 0) CalendarEventStore.replaceAll(this, merged)

                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Import complete")
                        .setMessage(if (added > 0) "Imported $added birthday events." else "No new birthday events to import.")
                        .setPositiveButton("OK", null)
                        .show()
                    refresh()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Import failed")
                        .setMessage(e.message ?: "Unknown error")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun confirmRemoveImportedGoogleCalendar() {
        AlertDialog.Builder(this)
            .setTitle("Remove imported events?")
            .setMessage("This will delete events previously imported from Google Calendar (your manually created events will stay).")
            .setPositiveButton("Remove") { _, _ -> removeImportedGoogleCalendar() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeImportedGoogleCalendar() {
        ioExecutor.execute {
            try {
                val removed = CalendarEventStore.removeBySource(this, "google_calendar")
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Removed")
                        .setMessage("Removed $removed imported events.")
                        .setPositiveButton("OK", null)
                        .show()
                    refresh()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Remove failed")
                        .setMessage(e.message ?: "Unknown error")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun openMonthPicker() {
        val anchor = currentMonth.atDay(1)
        DatePickerDialog(
            this,
            { _, year, month, _ ->
                currentMonth = YearMonth.of(year, month + 1)
                selectedDate = currentMonth.atDay(1)
                refresh()
            },
            anchor.year, anchor.monthValue - 1, 1
        ).show()
    }

    private fun sendToDisplay(event: CalendarEvent) {
        val occursOn = event.nextOccurrenceOnOrAfter(selectedDate) ?: selectedDate
        val bitmap = EventCalendarRenderer.render(this, event, occursOn)

        val argb = IntArray(TARGET_WIDTH * TARGET_HEIGHT)
        bitmap.getPixels(argb, 0, TARGET_WIDTH, 0, 0, TARGET_WIDTH, TARGET_HEIGHT)
        val result = ImageProcessor.process(argb, TARGET_WIDTH, TARGET_HEIGHT)
        HistoryStore.save(this, result.frame, bitmap)

        selectedFrame = result.frame
        selectedThumbnail = bitmap
        setResult(RESULT_OK)
        finish()
    }

    private fun refresh() {
        txtMonthYear.text = currentMonth.atDay(1).format(HEADER_FORMAT)
        dayAdapter.notifyDataSetChanged()

        val events = CalendarEventStore.eventsOn(this, selectedDate)
        txtNoEvents.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        listEvents.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
        listEvents.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            events.map {
                "${it.title}  ·  ${it.category.label} · ${formatTimeLabel(it.timeMinutes)}${if (it.recurrence == Recurrence.YEARLY) " · yearly" else ""}"
            }
        )
    }

    private fun formatTimeLabel(minutes: Int?): String {
        if (minutes == null) return "all day"
        val h = (minutes / 60).coerceIn(0, 23)
        val m = (minutes % 60).coerceIn(0, 59)
        return LocalTime.of(h, m).format(DateTimeFormatter.ofPattern("h:mm a"))
    }

    /** 6 full weeks (42 cells) starting on the Monday on/before the 1st, so every month's
     *  grid is a stable size and always shows a couple of adjacent-month days for context. */
    private inner class DayAdapter : BaseAdapter() {
        private fun gridStart(): LocalDate {
            val first = currentMonth.atDay(1)
            val offset = first.dayOfWeek.value - 1 // Monday=1 -> 0
            return first.minusDays(offset.toLong())
        }

        fun dateAt(position: Int): LocalDate = gridStart().plusDays(position.toLong())

        override fun getCount(): Int = 42
        override fun getItem(position: Int): LocalDate = dateAt(position)
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val date = dateAt(position)
            val cell = (convertView as? FrameLayout) ?: FrameLayout(this@CalendarMonthActivity).apply {
                val size = (44 * resources.displayMetrics.density).toInt()
                layoutParams = ViewGroup.LayoutParams(size, size)
                addView(TextView(this@CalendarMonthActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                    gravity = Gravity.CENTER
                    textSize = 14f
                })
                addView(View(this@CalendarMonthActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(6, 6, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                        bottomMargin = (4 * resources.displayMetrics.density).toInt()
                    }
                    setBackgroundColor(Color.parseColor("#2E6E82"))
                })
            }

            val label = cell.getChildAt(0) as TextView
            val dot = cell.getChildAt(1)

            label.text = date.dayOfMonth.toString()
            val inCurrentMonth = date.month == currentMonth.month && date.year == currentMonth.year
            val isSelected = date == selectedDate
            val isToday = date == LocalDate.now()

            label.setTextColor(
                when {
                    isSelected -> Color.WHITE
                    !inCurrentMonth -> Color.parseColor("#B7BFC9")
                    isToday -> Color.parseColor("#2E6E82")
                    else -> Color.parseColor("#1E2A3A")
                }
            )
            cell.setBackgroundColor(if (isSelected) Color.parseColor("#2E6E82") else Color.TRANSPARENT)
            dot.visibility = if (hasEvent(date)) View.VISIBLE else View.GONE

            return cell
        }

        private fun hasEvent(date: LocalDate): Boolean =
            CalendarEventStore.list(this@CalendarMonthActivity).any { it.nextOccurrenceOnOrAfter(date) == date }
    }
}
