package com.keychain.epd

import android.service.notification.NotificationListenerService

/**
 * Minimal NotificationListenerService required so that [MediaSessionManager] will
 * authorize [MediaSessionNowPlaying] against this component.
 *
 * No logic is needed here; the service just needs to exist and be registered in
 * AndroidManifest.xml.
 */
class MediaSessionListenerService : NotificationListenerService() {
    override fun onListenerConnected() {}
    override fun onListenerDisconnected() {}
}