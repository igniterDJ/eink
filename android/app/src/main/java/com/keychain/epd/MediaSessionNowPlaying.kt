package com.keychain.epd

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Generic, app-agnostic Now Playing source that reads the currently-playing
 * media session from any app (YouTube Music, Spotify, Amazon Music, etc.).
 *
 * Requires the user to grant Notification Listener access via
 * [Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS].
 */
object MediaSessionNowPlaying {

    private const val TAG = "MediaSessionNowPlaying"

    data class NowPlayingInfo(
        val title: String,
        val artist: String,
        val art: Bitmap?
    )

    /**
     * Returns [NowPlayingInfo] for the active media session, or null if nothing
     * is playing or notification access is not granted.
     */
    fun current(context: Context): NowPlayingInfo? {
        try {
            val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val compName = ComponentName(context, MediaSessionListenerService::class.java)
            val sessions = mgr.getActiveSessions(compName)

            if (sessions.isNullOrEmpty()) return null

            val playing = sessions.mapNotNull { ctrl ->
                val state = ctrl.playbackState
                if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                    ctrl to (state.lastPositionUpdateTime)
                } else {
                    null
                }
            }

            if (playing.isEmpty()) return null

            // If multiple sessions are playing, pick the most recently updated one.
            val best = playing.maxByOrNull { it.second } ?: return null
            val ctrl = best.first
            val meta = ctrl.metadata ?: return null

            val title = meta.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            val artist = meta.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            // Some apps (podcast/audiobook players especially) only set one of the two
            // fields. Only treat it as "nothing playing" when both are missing — dropping
            // the whole update over a single absent field hid real now-playing state.
            if (title == null && artist.isEmpty()) return null

            // Prefer album art as direct Bitmap; fall back to content URI. Some apps embed
            // very large album art (1000px+); downscale so repeated polls don't decode/hold
            // full-resolution bitmaps for a 250x122 display.
            val art: Bitmap? = try {
                val bitmap = meta.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
                val raw = if (bitmap != null) {
                    bitmap
                } else {
                    val uriStr = meta.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                    if (!uriStr.isNullOrEmpty()) {
                        decodeSampledBitmap(context, android.net.Uri.parse(uriStr), MAX_ART_DIMEN)
                    } else {
                        null
                    }
                }
                raw?.let { downscaleIfLarger(it, MAX_ART_DIMEN) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read album art", e)
                null
            }

            return NowPlayingInfo(title = title ?: "Unknown Title", artist = artist, art = art)
        } catch (e: SecurityException) {
            // Notification listener access not granted
            Log.w(TAG, "Notification access not granted", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "MediaSessionNowPlaying.current failed", e)
            return null
        }
    }

    /**
     * Returns true if the user has granted Notification Listener access to this app.
     */
    fun isNotificationAccessGranted(context: Context): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    private const val MAX_ART_DIMEN = 512

    /** Decodes a content:// album art URI downsampled to roughly [maxDimen], instead of
     *  decoding at full resolution first (some apps embed multi-megapixel album art). */
    private fun decodeSampledBitmap(context: Context, uri: android.net.Uri, maxDimen: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxDimen ||
            bounds.outHeight / (sampleSize * 2) >= maxDimen
        ) {
            sampleSize *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    /** Scales down a bitmap that's still larger than [maxDimen] after sampling (or one
     *  handed to us directly via METADATA_KEY_ALBUM_ART, which isn't sampled). Returns
     *  the original bitmap unchanged if it's already small enough. */
    private fun downscaleIfLarger(bitmap: Bitmap, maxDimen: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDimen && h <= maxDimen) return bitmap
        val scale = maxDimen.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
    }
}