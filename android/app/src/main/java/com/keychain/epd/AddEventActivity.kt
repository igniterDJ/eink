package com.keychain.epd

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Add/edit screen for a single CalendarEvent, launched by CalendarMonthActivity for
 *  either a specific day (new event) or an existing event (edit). Writes straight to
 *  CalendarEventStore and finishes; the caller reloads its list in onResume(). */
class AddEventActivity : ComponentActivity() {

    companion object {
        const val EXTRA_DATE = "date"
        const val EXTRA_EVENT_ID = "event_id"
        private val HEADER_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
    }

    private lateinit var date: LocalDate
    private var existingId: Long = 0L
    private var backgroundIndex: Int = 0

    private lateinit var edtTitle: EditText
    private lateinit var groupRecurrence: RadioGroup
    private lateinit var groupCategory: RadioGroup
    private lateinit var imgBackgroundPreview: ImageView
    private lateinit var btnDeleteEvent: Button

    private lateinit var btnPickTime: Button
    private var timeMinutes: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_event)

        // Keep the title/date fields below the status bar/notch.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }

        date = LocalDate.parse(intent.getStringExtra(EXTRA_DATE) ?: LocalDate.now().toString())
        existingId = intent.getLongExtra(EXTRA_EVENT_ID, 0L)

        findViewById<TextView>(R.id.txtDate).text = date.format(HEADER_FORMAT)
        edtTitle = findViewById(R.id.edtTitle)
        edtTitle.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        groupRecurrence = findViewById(R.id.groupRecurrence)
        groupCategory = findViewById(R.id.groupCategory)
        imgBackgroundPreview = findViewById(R.id.imgBackgroundPreview)
        btnDeleteEvent = findViewById(R.id.btnDeleteEvent)
        btnPickTime = findViewById(R.id.btnPickTime)

        val existing = if (existingId != 0L) {
            CalendarEventStore.list(this).find { it.id == existingId }
        } else null

        if (existing != null) {
            edtTitle.setText(existing.title)
            groupRecurrence.check(if (existing.recurrence == Recurrence.YEARLY) R.id.radioYearly else R.id.radioOneTime)
            groupCategory.check(radioIdFor(existing.category))
            backgroundIndex = existing.backgroundIndex
            timeMinutes = existing.timeMinutes
            btnDeleteEvent.visibility = android.view.View.VISIBLE
        }

        updateTimeLabel()
        btnPickTime.setOnClickListener { onPickTimeClicked() }

        groupCategory.setOnCheckedChangeListener { _, _ ->
            backgroundIndex = 0
            updatePreview()
        }
        updatePreview()

        findViewById<Button>(R.id.btnNextImage).setOnClickListener {
            backgroundIndex++
            updatePreview()
        }

        findViewById<Button>(R.id.btnPreviewEvent).setOnClickListener { onPreviewClicked() }

        findViewById<Button>(R.id.btnSaveEvent).setOnClickListener { onSaveClicked() }
        findViewById<Button>(R.id.btnCancelEvent).setOnClickListener { finish() }
        btnDeleteEvent.setOnClickListener { onDeleteClicked() }
    }

    private fun onSaveClicked() {
        val title = edtTitle.text.toString().trim()
        if (title.isEmpty()) {
            Toast.makeText(this, "Enter a title", Toast.LENGTH_SHORT).show()
            return
        }

        val event = CalendarEvent(
            id = existingId,
            date = date,
            title = title,
            category = selectedCategory(),
            recurrence = if (groupRecurrence.checkedRadioButtonId == R.id.radioYearly) Recurrence.YEARLY else Recurrence.ONE_TIME,
            backgroundIndex = backgroundIndex,
            timeMinutes = timeMinutes
        )

        if (existingId != 0L) {
            CalendarEventStore.update(this, event)
        } else {
            CalendarEventStore.add(this, event)
        }
        setResult(RESULT_OK)
        finish()
    }

    private fun onDeleteClicked() {
        AlertDialog.Builder(this)
            .setTitle("Delete event?")
            .setPositiveButton("Delete") { _, _ ->
                CalendarEventStore.delete(this, existingId)
                setResult(RESULT_OK)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updatePreview() {
        imgBackgroundPreview.setImageBitmap(EventBackgrounds.load(this, selectedCategory(), backgroundIndex))
    }

    private fun onPickTimeClicked() {
        // Lazy: one dialog with three obvious choices.
        val options = arrayOf(
            "All day",
            "Morning (9:00 AM)",
            "Afternoon (1:00 PM)",
            "Evening (6:00 PM)",
            "Custom time..."
        )
        AlertDialog.Builder(this)
            .setTitle("Event time")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> timeMinutes = null
                    1 -> timeMinutes = 9 * 60
                    2 -> timeMinutes = 13 * 60
                    3 -> timeMinutes = 18 * 60
                    else -> showCustomTimePicker()
                }
                updateTimeLabel()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomTimePicker() {
        val initial = timeMinutes ?: (9 * 60)
        val initialH = (initial / 60).coerceIn(0, 23)
        val initialM = (initial % 60).coerceIn(0, 59)

        android.app.TimePickerDialog(
            this,
            { _, hourOfDay, minute ->
                timeMinutes = (hourOfDay.coerceIn(0, 23) * 60) + minute.coerceIn(0, 59)
                updateTimeLabel()
            },
            initialH,
            initialM,
            false
        ).show()
    }

    private fun updateTimeLabel() {
        btnPickTime.text = formatTimeLabel(timeMinutes)
    }

    private fun formatTimeLabel(minutes: Int?): String {
        if (minutes == null) return "All day"
        val h = (minutes / 60).coerceIn(0, 23)
        val m = (minutes % 60).coerceIn(0, 59)
        return LocalTime.of(h, m).format(DateTimeFormatter.ofPattern("h:mm a"))
    }

    private fun onPreviewClicked() {
        val title = edtTitle.text.toString().trim().ifEmpty { "(Untitled)" }
        val tmp = CalendarEvent(
            id = existingId,
            date = date,
            title = title,
            category = selectedCategory(),
            recurrence = if (groupRecurrence.checkedRadioButtonId == R.id.radioYearly) Recurrence.YEARLY else Recurrence.ONE_TIME,
            backgroundIndex = backgroundIndex,
            timeMinutes = timeMinutes
        )
        val occursOn = tmp.nextOccurrenceOnOrAfter(date) ?: date
        val bitmap = EventCalendarRenderer.render(this, tmp, occursOn)
        imgBackgroundPreview.setImageBitmap(bitmap)
        Toast.makeText(this, "Preview updated", Toast.LENGTH_SHORT).show()
    }

    private fun selectedCategory(): EventCategory = when (groupCategory.checkedRadioButtonId) {
        R.id.radioBirthday -> EventCategory.BIRTHDAY
        R.id.radioFriends -> EventCategory.FRIENDS
        R.id.radioHealth -> EventCategory.HEALTH
        R.id.radioTravel -> EventCategory.TRAVEL
        else -> EventCategory.ANNIVERSARY
    }

    private fun radioIdFor(category: EventCategory): Int = when (category) {
        EventCategory.ANNIVERSARY -> R.id.radioAnniversary
        EventCategory.BIRTHDAY -> R.id.radioBirthday
        EventCategory.FRIENDS -> R.id.radioFriends
        EventCategory.HEALTH -> R.id.radioHealth
        EventCategory.TRAVEL -> R.id.radioTravel
    }
}
