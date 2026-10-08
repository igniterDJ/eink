package com.desktag.epd

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), NowPlayingService.Listener {

    companion object {
        private const val TAG = "MainActivity"

        // Display native orientation in contract is 122x250; the ImageProcessor expects
        // a 250x122 landscape ARGB input (then rotates internally).
        private const val TARGET_WIDTH = 250
        private const val TARGET_HEIGHT = 122

        // Must match AndroidManifest provider authority and res/xml/file_paths.xml
        private const val FILE_PROVIDER_AUTHORITY = "com.desktag.epd.fileprovider"

        // "Nothing in the next 7 days" is a more useful idle state on a keychain
        // display glanced at once than "nothing in the next 24 hours".
        private const val CALENDAR_LOOKAHEAD_MILLIS = 7L * 24 * 60 * 60 * 1000
    }

    private lateinit var btnTakePhoto: Button
    private lateinit var btnUploadPhoto: Button
    private lateinit var btnAddText: Button
    private lateinit var btnCalendar: Button
    private lateinit var btnMyEvents: Button
    private lateinit var btnGallery: Button
    private lateinit var btnNowPlaying: Button
    private lateinit var btnNowPlayingStyle: Button
    private lateinit var btnScanConnect: Button
    private lateinit var btnSend: Button
    private lateinit var btnClearDisplay: Button
    private lateinit var imgPreview: ImageView
    private lateinit var txtStatus: TextView

    private var processedFrame: ByteArray? = null
    private var previewBitmap: Bitmap? = null

    private var isConnected: Boolean = false

    private var photoUri: Uri? = null

    // Status text captured right before launching PhotoAdjustActivity, restored on cancel.
    private var statusBeforeAdjust: String? = null

    private val imageExecutor = Executors.newSingleThreadExecutor()

    private fun lastDisplayPreviewFile(): File = File(filesDir, "last_display.png")

    // ---------- Now Playing / BLE service ----------
    // BLE connection and Now Playing polling live in NowPlayingService (a foreground
    // service) so they keep running once the user leaves this app for e.g. YouTube
    // Music, instead of stopping in onPause like they used to.
    private var nowPlayingService: NowPlayingService? = null
    private var serviceBound = false
    private var selectedStyle: NowPlayingStyle = NowPlayingStyle.ART_AND_TEXT

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as NowPlayingService.LocalBinder).getService()
            nowPlayingService = svc
            svc.setListener(this@MainActivity)
            isConnected = svc.isConnected
            updateUiState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            nowPlayingService = null
        }
    }

    // ---------- Photo adjust launcher ----------
    private val photoAdjustLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val adjusted = PhotoAdjustActivity.resultBitmap
        PhotoAdjustActivity.resultBitmap = null
        if (result.resultCode == RESULT_OK && adjusted != null) {
            finishProcessing(adjusted)
        } else {
            txtStatus.text = statusBeforeAdjust ?: txtStatus.text
        }
    }

    // ---------- Add Text launcher ----------
    private val textAdjustLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val texted = TextAdjustActivity.resultBitmap
        TextAdjustActivity.resultBitmap = null
        if (result.resultCode == RESULT_OK && texted != null) {
            finishProcessing(texted)
        } else {
            txtStatus.text = statusBeforeAdjust ?: txtStatus.text
        }
    }

    // ---------- Now Playing style launcher ----------
    private val nowPlayingStyleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val style = NowPlayingStyleActivity.selectedStyle
        NowPlayingStyleActivity.selectedStyle = null
        if (result.resultCode == RESULT_OK && style != null) {
            selectedStyle = style
            saveSelectedStyle(style)
            nowPlayingService?.setSelectedStyle(style) // force a re-render with the new style on the next poll
            txtStatus.text = "Now Playing style: ${style.label}"
        }
    }

    // ---------- My Events (calendar) launcher ----------
    private val calendarLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val frame = CalendarMonthActivity.selectedFrame
        val thumbnail = CalendarMonthActivity.selectedThumbnail
        CalendarMonthActivity.selectedFrame = null
        CalendarMonthActivity.selectedThumbnail = null
        if (result.resultCode == RESULT_OK && frame != null && thumbnail != null) {
            previewBitmap?.recycle()
            previewBitmap = thumbnail
            imgPreview.setImageBitmap(thumbnail)
            processedFrame = frame
            txtStatus.text = if (isConnected) "Sending event to display..." else "Event ready to send"
            updateUiState()

            // If the user explicitly chose "Send to display" in the calendar UI, do it.
            // (Previously it only prepared the frame and required an extra manual tap.)
            if (isConnected) {
                nowPlayingService?.sendFrame(frame, asBackground = true)
            }
        }
    }

    // ---------- Gallery launcher ----------
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val frame = GalleryActivity.selectedFrame
        val thumbnail = GalleryActivity.selectedThumbnail
        GalleryActivity.selectedFrame = null
        GalleryActivity.selectedThumbnail = null
        if (result.resultCode == RESULT_OK && frame != null && thumbnail != null) {
            previewBitmap?.recycle()
            previewBitmap = thumbnail
            imgPreview.setImageBitmap(thumbnail)
            processedFrame = frame
            txtStatus.text = "Loaded from gallery (${frame.size} bytes)"
            updateUiState()
        }
    }

    // ---------- Take Photo launcher ----------
    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoUri != null) {
            txtStatus.text = "Photo captured. Processing..."
            processImageUri(photoUri!!)
        } else {
            txtStatus.text = "Photo capture cancelled or failed"
        }
    }

    // ---------- Pick image launcher ----------
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            txtStatus.text = "Image selected. Processing..."
            processImageUri(uri)
        } else {
            txtStatus.text = "No image selected"
        }
    }

    // ---------- Permission launchers ----------
    private val requestBluetoothPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            txtStatus.text = "Bluetooth permissions granted"
        } else {
            txtStatus.text = "Bluetooth permissions denied"
        }
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            txtStatus.text = "Location permission granted"
        } else {
            txtStatus.text = "Location permission denied"
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            takePhoto()
        } else {
            txtStatus.text = "Camera permission denied"
        }
    }

    private val requestCalendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            renderNextCalendarEvent()
        } else {
            txtStatus.text = "Calendar permission denied"
        }
    }

    // ---------- life cycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Avoid content under the status bar/notch on edge-to-edge devices.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }

        btnTakePhoto = findViewById(R.id.btnTakePhoto)
        btnUploadPhoto = findViewById(R.id.btnUploadPhoto)
        btnAddText = findViewById(R.id.btnAddText)
        btnCalendar = findViewById(R.id.btnCalendar)
        btnMyEvents = findViewById(R.id.btnMyEvents)
        btnGallery = findViewById(R.id.btnGallery)
        btnNowPlaying = findViewById(R.id.btnNowPlaying)
        btnNowPlayingStyle = findViewById(R.id.btnNowPlayingStyle)
        btnScanConnect = findViewById(R.id.btnScanConnect)
        btnSend = findViewById(R.id.btnSend)
        btnClearDisplay = findViewById(R.id.btnClearDisplay)
        imgPreview = findViewById(R.id.imgPreview)
        txtStatus = findViewById(R.id.txtStatus)

        btnTakePhoto.setOnClickListener { onTakePhotoClicked() }
        btnUploadPhoto.setOnClickListener { onUploadPhotoClicked() }
        btnAddText.setOnClickListener { onAddTextClicked() }
        btnCalendar.setOnClickListener { onCalendarClicked() }
        btnMyEvents.setOnClickListener { onMyEventsClicked() }
        btnGallery.setOnClickListener { onGalleryClicked() }
        btnNowPlaying.setOnClickListener { onNowPlayingClicked() }
        btnNowPlayingStyle.setOnClickListener { onNowPlayingStyleClicked() }
        btnScanConnect.setOnClickListener { onScanConnectClicked() }

        selectedStyle = loadSelectedStyle()
        btnSend.setOnClickListener { onSendClicked() }
        btnClearDisplay.setOnClickListener { onClearDisplayClicked() }

        loadLastDisplayPreview()
        updateUiState()
        ensureRuntimePermissions()
    }

    private fun loadLastDisplayPreview() {
        try {
            val file = lastDisplayPreviewFile()
            val bitmap = if (file.exists()) {
                BitmapFactory.decodeFile(file.path)
            } else {
                val latest = HistoryStore.list(this).maxByOrNull { it.id }
                latest?.let { HistoryStore.loadThumbnail(it) }
            }
            if (bitmap != null) {
                previewBitmap?.recycle()
                previewBitmap = bitmap
                imgPreview.setImageBitmap(bitmap)
            }
        } catch (_: Exception) {
        }
    }

    private fun saveLastDisplayPreview(bitmap: Bitmap) {
        try {
            FileOutputStream(lastDisplayPreviewFile()).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (_: Exception) {
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, NowPlayingService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        serviceBound = true
    }

    override fun onStop() {
        super.onStop()
        nowPlayingService?.setListener(null)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            imageExecutor.shutdown()
        } catch (_: Exception) {
        }
    }

    // ---------- permissions ----------

    private fun ensureRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            val perms = if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            } else {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            }
            val missing = perms.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing) requestBluetoothPermissionsLauncher.launch(perms)
        } else {
            val fine = Manifest.permission.ACCESS_FINE_LOCATION
            if (ContextCompat.checkSelfPermission(this, fine) != PackageManager.PERMISSION_GRANTED) {
                requestLocationPermissionLauncher.launch(fine)
            }
        }
    }

    // ---------- button handlers ----------

    private fun onTakePhotoClicked() {
        resetPreview()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            takePhoto()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun takePhoto() {
        val photosDir = File(cacheDir, "photos").apply { mkdirs() }
        val photoFile = File.createTempFile("epd_", ".jpg", photosDir)
        photoUri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, photoFile)
        takePhotoLauncher.launch(photoUri!!)
    }

    private fun onUploadPhotoClicked() {
        resetPreview()

        pickImageLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    /** Clears the preview before starting a new capture/upload, so the ImageView never
     *  holds a reference to a bitmap we're about to recycle (recycling it first crashes
     *  on the next redraw). */
    private fun resetPreview() {
        processedFrame = null
        imgPreview.setImageDrawable(null)
        previewBitmap?.recycle()
        previewBitmap = null
        updateUiState()
    }

    private fun onAddTextClicked() {
        // Carry the current photo (if any) into the text editor as its background,
        // instead of discarding it, so photo + text can be combined.
        TextAdjustActivity.backgroundBitmap = previewBitmap
        statusBeforeAdjust = txtStatus.text.toString()
        textAdjustLauncher.launch(Intent(this, TextAdjustActivity::class.java))
    }

    private fun onGalleryClicked() {
        galleryLauncher.launch(Intent(this, GalleryActivity::class.java))
    }

    // ---------- My Events (in-app calendar) ----------

    private fun onMyEventsClicked() {
        resetPreview()
        calendarLauncher.launch(Intent(this, CalendarMonthActivity::class.java))
    }

    // ---------- Calendar ----------

    private fun onCalendarClicked() {
        resetPreview()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            renderNextCalendarEvent()
        } else {
            requestCalendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
        }
    }

    /** Renders the soonest calendar event on demand (button tap), same shape as
     *  finishProcessing() for Photo/Text: render once, preview, let the user hit Send. */
    private fun renderNextCalendarEvent() {
        imageExecutor.execute {
            try {
                val event = CalendarSource.nextEvent(contentResolver, CALENDAR_LOOKAHEAD_MILLIS)
                val bitmap = CalendarRenderer.render(event)

                val argb = IntArray(TARGET_WIDTH * TARGET_HEIGHT)
                bitmap.getPixels(argb, 0, TARGET_WIDTH, 0, 0, TARGET_WIDTH, TARGET_HEIGHT)

                val result = ImageProcessor.process(argb, TARGET_WIDTH, TARGET_HEIGHT)
                HistoryStore.save(this, result.frame, bitmap)

                runOnUiThread {
                    previewBitmap?.recycle()
                    previewBitmap = bitmap
                    imgPreview.setImageBitmap(bitmap)
                    processedFrame = result.frame
                    txtStatus.text = if (event != null) "Calendar ready" else "No upcoming events"
                    updateUiState()
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "renderNextCalendarEvent: permission revoked", e)
                runOnUiThread {
                    txtStatus.text = "Calendar permission needed"
                    processedFrame = null
                    updateUiState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "renderNextCalendarEvent failed", e)
                runOnUiThread {
                    txtStatus.text = "Calendar read failed: ${e.message}"
                    processedFrame = null
                    updateUiState()
                }
            }
        }
    }

    // ---------- Now Playing toggle ----------

    private fun onNowPlayingClicked() {
        val svc = nowPlayingService ?: return
        val enabling = !svc.isNowPlayingEnabled
        if (enabling) {
            if (!MediaSessionNowPlaying.isNotificationAccessGranted(this)) {
                txtStatus.text = "Notification access required for Now Playing"
                Toast.makeText(this, "Grant notification access in Settings to enable Now Playing", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    // Settings action not available; user must navigate manually
                }
                return
            }
            // Notification access can be granted but not yet bound (a known platform
            // quirk — the listener service doesn't take effect until rebound or the
            // app restarts). Request a rebind every time Now Playing is enabled so a
            // freshly-granted permission works immediately instead of silently
            // reporting "no active track" until the user restarts the app.
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(this, MediaSessionListenerService::class.java)
                )
            } catch (e: Exception) {
                Log.w(TAG, "requestRebind failed", e)
            }
            // A bound-only service is killed the instant its last client unbinds
            // (e.g. the moment this app is backgrounded), no matter what
            // startForeground() inside it does. It must also be started, so it
            // stays alive independent of whether this Activity is bound to it.
            ContextCompat.startForegroundService(this, Intent(this, NowPlayingService::class.java))
            txtStatus.text = "Now Playing: enabled"
        } else {
            txtStatus.text = "Now Playing: disabled"
        }
        svc.setNowPlayingEnabled(enabling, selectedStyle)
        if (!enabling) {
            stopService(Intent(this, NowPlayingService::class.java))
        }
        updateUiState()
    }

    private fun onNowPlayingStyleClicked() {
        val intent = Intent(this, NowPlayingStyleActivity::class.java)
            .putExtra("current", selectedStyle.name)
        nowPlayingStyleLauncher.launch(intent)
    }

    private fun loadSelectedStyle(): NowPlayingStyle {
        val name = getSharedPreferences("epd_prefs", MODE_PRIVATE).getString("display_style", null)
        return NowPlayingStyle.entries.find { it.name == name } ?: NowPlayingStyle.ART_AND_TEXT
    }

    private fun saveSelectedStyle(style: NowPlayingStyle) {
        getSharedPreferences("epd_prefs", MODE_PRIVATE).edit()
            .putString("display_style", style.name)
            .apply()
    }

    // ---------- NowPlayingService.Listener ----------

    override fun onStatus(message: String) {
        runOnUiThread { txtStatus.text = message }
    }

    override fun onConnectionChanged(connected: Boolean) {
        runOnUiThread {
            isConnected = connected
            updateUiState()
        }
    }

    override fun onNowPlayingFrame(bitmap: Bitmap, frame: ByteArray) {
        runOnUiThread {
            previewBitmap?.recycle()
            previewBitmap = bitmap
            imgPreview.setImageBitmap(bitmap)
            processedFrame = frame
            txtStatus.text = "Now Playing updated"
            updateUiState()
        }
    }

    override fun onTransferComplete() {
        runOnUiThread { txtStatus.text = "Transfer complete" }
    }

    override fun onTransferError(error: String) {
        runOnUiThread { txtStatus.text = "Error: $error" }
    }

    private fun onScanConnectClicked() {
        val svc = nowPlayingService ?: return
        if (isConnected) {
            svc.disconnectBle()
            return
        }
        svc.startScan()
    }

    private fun onSendClicked() {
        val frame = processedFrame
        if (!isConnected) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            return
        }
        if (frame == null) {
            Toast.makeText(this, "No image ready", Toast.LENGTH_SHORT).show()
            return
        }

        // Persist the preview so the next app launch shows what the display likely shows.
        previewBitmap?.let { saveLastDisplayPreview(it) }
        nowPlayingService?.sendFrame(frame, asBackground = true)
    }

    private fun onClearDisplayClicked() {
        if (!isConnected) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
            return
        }

        // Clearing the display means we no longer have a meaningful "last display" preview.
        try {
            lastDisplayPreviewFile().delete()
        } catch (_: Exception) {
        }
        nowPlayingService?.clearDisplay()
    }

    // ---------- image processing ----------

    private fun processImageUri(uri: Uri) {
        // Do all image IO/processing off the main thread.
        imageExecutor.execute {
            try {
                val oriented = decodeDownsampledAndOrientedBitmap(contentResolver, uri)

                // Hand off to the adjust screen for user pinch/pan/rotate/crop into the
                // 250x122 frame, instead of a force-stretch. Must launch from the UI thread.
                runOnUiThread {
                    statusBeforeAdjust = txtStatus.text.toString()
                    PhotoAdjustActivity.sourceBitmap = oriented
                    txtStatus.text = "Adjust photo..."
                    photoAdjustLauncher.launch(Intent(this, PhotoAdjustActivity::class.java))
                }
            } catch (e: Exception) {
                Log.e(TAG, "processImageUri failed", e)
                runOnUiThread {
                    txtStatus.text = "Image processing failed: ${e.message}"
                    processedFrame = null
                    updateUiState()
                }
            }
        }
    }

    /** Continues the pipeline with the user-adjusted 250x122 bitmap from PhotoAdjustActivity. */
    private fun finishProcessing(scaled: Bitmap) {
        imageExecutor.execute {
            try {
                // ImageProcessor is pure Kotlin and expects ARGB IntArray, not Bitmap
                val argb = IntArray(TARGET_WIDTH * TARGET_HEIGHT)
                scaled.getPixels(argb, 0, TARGET_WIDTH, 0, 0, TARGET_WIDTH, TARGET_HEIGHT)

                val result = ImageProcessor.process(argb, TARGET_WIDTH, TARGET_HEIGHT)
                HistoryStore.save(this, result.frame, scaled)

                runOnUiThread {
                    previewBitmap?.recycle()
                    previewBitmap = scaled
                    imgPreview.setImageBitmap(scaled)
                    processedFrame = result.frame
                    txtStatus.text = "Image ready (${result.frame.size} bytes)"
                    updateUiState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "finishProcessing failed", e)
                runOnUiThread {
                    txtStatus.text = "Image processing failed: ${e.message}"
                    processedFrame = null
                    updateUiState()
                }
            }
        }
    }

    // ---------- UI state ----------

    private fun updateUiState() {
        btnSend.isEnabled = isConnected && processedFrame != null
        btnClearDisplay.isEnabled = isConnected
    }
}
