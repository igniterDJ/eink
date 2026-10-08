package com.keychain.epd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * Owns the single BLE connection and the Now Playing poll loop.
 *
 * This used to live on MainActivity, gated by onResume/onPause — so polling
 * (and the BLE send that depends on it) stopped the instant the user left the
 * app to use YouTube Music, which is the whole point of Now Playing. Moving
 * it into a foreground service keeps it running independent of the Activity.
 * MainActivity binds to this service and routes all BLE actions
 * (scan/connect/send/clear) through it instead of owning a second BleManager,
 * so there's only ever one GATT connection.
 */
class NowPlayingService : Service() {

    companion object {
        private const val TAG = "NowPlayingService"
        private const val CHANNEL_ID = "now_playing_sync"
        private const val NOTIF_ID = 1
        private const val NOW_PLAYING_POLL_INTERVAL_MS = 10_000L
        private const val BG_REVERT_DELAY_MS = 60_000L
        private const val TARGET_WIDTH = 250
        private const val TARGET_HEIGHT = 122
    }

    interface Listener {
        fun onStatus(message: String)
        fun onConnectionChanged(connected: Boolean)
        fun onNowPlayingFrame(bitmap: Bitmap, frame: ByteArray)
        fun onTransferComplete()
        fun onTransferError(error: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): NowPlayingService = this@NowPlayingService
    }

    private val binder = LocalBinder()
    private var listener: Listener? = null

    private lateinit var bleManager: BleManager
    private val handler = Handler(Looper.getMainLooper())
    private val imageExecutor = Executors.newSingleThreadExecutor()

    var isConnected: Boolean = false
        private set

    var isNowPlayingEnabled: Boolean = false
        private set
    private var selectedStyle: NowPlayingStyle = NowPlayingStyle.ART_AND_TEXT
    private var lastSentTitleArtist: String? = null
    private var pendingBgRevertRunnable: Runnable? = null
    // Guards against re-arming the revert after it already fired once for this idle
    // streak — without this, every poll tick after a revert sees no pending runnable
    // and no active track, and schedules another one, causing a full e-ink refresh
    // (visible flicker) roughly every BG_REVERT_DELAY_MS forever while paused.
    private var revertedSinceLastTrack: Boolean = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollNowPlaying()
            handler.postDelayed(this, NOW_PLAYING_POLL_INTERVAL_MS)
        }
    }

    private val statusCallback = object : BleManager.StatusCallback {
        override fun onStatus(message: String) {
            listener?.onStatus(message)
        }

        override fun onGattConnected() {
            isConnected = true
            listener?.onConnectionChanged(true)
        }

        override fun onGattDisconnected() {
            isConnected = false
            listener?.onConnectionChanged(false)
        }

        override fun onTransferComplete() {
            listener?.onTransferComplete()
        }

        override fun onTransferError(error: String) {
            listener?.onTransferError(error)
        }
    }

    override fun onCreate() {
        super.onCreate()
        bleManager = BleManager(this, statusCallback)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopPolling()
        bleManager.disconnect()
        imageExecutor.shutdown()
        super.onDestroy()
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    // ---------- BLE passthrough for manual UI actions ----------

    fun startScan() = bleManager.startScan()
    fun disconnectBle() = bleManager.disconnect()
    fun sendFrame(frame: ByteArray, asBackground: Boolean = false) = bleManager.sendFrame(frame, asBackground)
    fun clearDisplay() = bleManager.clearDisplay()

    // ---------- Now Playing ----------

    fun setNowPlayingEnabled(enabled: Boolean, style: NowPlayingStyle) {
        isNowPlayingEnabled = enabled
        selectedStyle = style
        if (enabled) {
            startForeground(NOTIF_ID, buildNotification())
            stopPolling()
            revertedSinceLastTrack = false
            handler.post(pollRunnable)
        } else {
            stopPolling()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    fun setSelectedStyle(style: NowPlayingStyle) {
        selectedStyle = style
        lastSentTitleArtist = null // force a re-render with the new style on the next poll
    }

    private fun stopPolling() {
        handler.removeCallbacks(pollRunnable)
        pendingBgRevertRunnable?.let { handler.removeCallbacks(it) }
        pendingBgRevertRunnable = null
    }

    private fun pollNowPlaying() {
        if (!isNowPlayingEnabled) return
        if (!isConnected) return
        if (selectedStyle == NowPlayingStyle.NONE) return

        imageExecutor.execute {
            try {
                val np = MediaSessionNowPlaying.current(this@NowPlayingService)
                if (np == null) {
                    handler.post {
                        listener?.onStatus("Now Playing: no active track")
                        if (pendingBgRevertRunnable == null && !revertedSinceLastTrack) {
                            val revert = Runnable {
                                bleManager.redrawBackground()
                                lastSentTitleArtist = null
                                pendingBgRevertRunnable = null
                                revertedSinceLastTrack = true
                            }
                            pendingBgRevertRunnable = revert
                            handler.postDelayed(revert, BG_REVERT_DELAY_MS)
                        }
                    }
                    return@execute
                }

                handler.post {
                    pendingBgRevertRunnable?.let {
                        handler.removeCallbacks(it)
                        pendingBgRevertRunnable = null
                    }
                    revertedSinceLastTrack = false
                }

                val key = "${np.title}—${np.artist}"
                if (key == lastSentTitleArtist) {
                    return@execute
                }

                // TEXT_ONLY never shows art, so skip the art bitmap entirely.
                val artBitmap = if (selectedStyle == NowPlayingStyle.TEXT_ONLY) null else np.art

                val composite = NowPlayingRenderer.render(selectedStyle, artBitmap, np.title, np.artist)

                val argb = IntArray(TARGET_WIDTH * TARGET_HEIGHT)
                composite.getPixels(argb, 0, TARGET_WIDTH, 0, 0, TARGET_WIDTH, TARGET_HEIGHT)
                val result = ImageProcessor.process(argb, TARGET_WIDTH, TARGET_HEIGHT)

                lastSentTitleArtist = key

                handler.post {
                    if (!isConnected) return@post
                    listener?.onNowPlayingFrame(composite, result.frame)
                    bleManager.sendFrame(result.frame)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Now Playing poll failed", e)
            }
        }
    }

    // ---------- notification ----------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Now Playing sync", NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("EPD Keychain")
            .setContentText("Now Playing sync active")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }
}
