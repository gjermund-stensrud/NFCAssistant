package no.neverhood.nfcassistant

import android.service.notification.NotificationListenerService
import timber.log.Timber

class MediaNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        Timber.d("MediaNotificationListenerService: Connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Timber.d("MediaNotificationListenerService: Disconnected")
    }
}
