package no.neverhood.nfcassistant

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import timber.log.Timber
import java.time.Instant

class MediaNotificationListenerService : NotificationListenerService() {
    lateinit var sessionManager: MediaSessionManager

    companion object {
        private var instance: MediaNotificationListenerService? = null
        private val activeControllersMap = mutableMapOf<String, MediaController>()
        private var isPlaying = false
        private var playingMediaId = ""
        private var playingMediaTitle = ""
        private var lastTitleChange : Instant = Instant.MIN

        fun getActiveControllers(): Map<String, MediaController> = activeControllersMap
        fun getIsPlaying(): Boolean = isPlaying
        fun getPlayingMediaId(): String = playingMediaId
        fun getPlayingMediaTitle(): String = playingMediaTitle
        fun getLastTitleChange(): Instant = lastTitleChange
        
        fun isRunning(): Boolean = instance != null
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateLocalControllers(controllers)
    }

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadata?.let { handleMediaMetadata(it) }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            state?.let { handleMediaPlaybackState(it) }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Timber.d("MediaNotificationListenerService: Connected")

        sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager.addOnActiveSessionsChangedListener(
            sessionsChangedListener,
            ComponentName(this, MediaNotificationListenerService::class.java)
        )
        
        // Initial sync
        updateLocalControllers(sessionManager.getActiveSessions(ComponentName(this, MediaNotificationListenerService::class.java)))
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Timber.d("MediaNotificationListenerService: Disconnected")
        
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        activeControllersMap.clear()
    }

    private fun updateLocalControllers(controllers: List<MediaController>?) {
        val newControllers = controllers ?: emptyList()
        val supportedPackages = packageNames.values

        // Remove controllers that are no longer active
        val iterator = activeControllersMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (newControllers.none { it.sessionToken == entry.value.sessionToken }) {
                entry.value.unregisterCallback(mediaControllerCallback)
                iterator.remove()
                Timber.d("Controller removed for: ${entry.key}")
            }
        }

        // Add or update controllers
        newControllers.forEach { controller ->
            if (supportedPackages.contains(controller.packageName)) {
                val existing = activeControllersMap[controller.packageName]
                if (existing?.sessionToken != controller.sessionToken) {
                    existing?.unregisterCallback(mediaControllerCallback)
                    controller.registerCallback(mediaControllerCallback)
                    activeControllersMap[controller.packageName] = controller
                    Timber.d("Controller bound for: ${controller.packageName}")

                    controller.metadata?.let { handleMediaMetadata(it) }
                    controller.playbackState?.let { handleMediaPlaybackState(it) }
                }
            }
        }
    }

    private fun handleMediaMetadata(metadata: MediaMetadata) {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)

        Timber.d("Media Metadata: title=$title, artist=$artist, mediaId=$mediaId")

        if (title != null && title != "") {
            if (title != playingMediaTitle) {
                lastTitleChange = Instant.now()
                Timber.d("New song detected: $title")
            }
            playingMediaTitle = title
        }

        if (mediaId != null) {
            if (mediaId.startsWith("spotify:track:")) {
                playingMediaId = mediaId.substringAfter("spotify:track:")
                Timber.d("Extracted Spotify ID: $playingMediaId")
            } else if (mediaId.length == 11) { // Common length for YT video IDs
                playingMediaId = mediaId
            }
        }
    }

    private fun handleMediaPlaybackState(state: PlaybackState) {
        isPlaying = state.state == PlaybackState.STATE_PLAYING
        Timber.d("Media Playback State: playing=$isPlaying")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        if (::sessionManager.isInitialized) {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
        activeControllersMap.values.forEach { it.unregisterCallback(mediaControllerCallback) }
        activeControllersMap.clear()
    }
}
