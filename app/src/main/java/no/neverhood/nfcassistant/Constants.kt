package no.neverhood.nfcassistant

enum class MediaTypes {
    UNKNOWN,
    YOUTUBE,
    YOUTUBE_MUSIC,
    SPOTIFY,
    TIDAL,
    PHONE_NUMBER,
}

val packageNames = mapOf(
    MediaTypes.YOUTUBE to "com.google.android.youtube",
    MediaTypes.YOUTUBE_MUSIC to "com.google.android.apps.youtube.music",
    MediaTypes.SPOTIFY to "com.spotify.music",
    MediaTypes.TIDAL to "com.aspiro.tidal"
)