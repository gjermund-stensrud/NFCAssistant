package no.neverhood.nfcassistant

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.app.ActivityOptions
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import no.neverhood.nfcassistant.databinding.ActivityMainBinding
import timber.log.Timber
import androidx.core.net.toUri


class MainActivity : AppCompatActivity() {
    private var currentMediaId = ""
    private var currentMediaType: Enum<MediaTypes> = MediaTypes.UNKNOWN
    private var currentMediaIsPlaying = false
    private var youTubeVariant: MediaTypes = MediaTypes.YOUTUBE
    private var mediaIdToWrite: String? = null
    private var mediaTypeToWrite: Enum<MediaTypes>? = null
    private var writeDialog: AlertDialog? = null

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var binding: ActivityMainBinding

    private var pn532Manager: Pn532Manager? = null

    private lateinit var mediaSessionManager: MediaSessionManager
    private val activeControllers = mutableMapOf<String, MediaController>()

    private val packageNames = mapOf(
        MediaTypes.YOUTUBE to "com.google.android.youtube",
        MediaTypes.YOUTUBE_MUSIC to "com.google.android.apps.youtube.music",
        MediaTypes.SPOTIFY to "com.spotify.music",
        MediaTypes.TIDAL to "com.aspiro.tidal"
    )

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadata?.let { handleMediaMetadata(it) }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            state?.let { handleMediaPlaybackState(it) }
        }
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveControllers(controllers)
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                        setStatus("Skru på Blåtann for å koble til ekstern NFC leser")
                    }
                    BluetoothAdapter.STATE_ON -> {
                        pn532Manager?.initBluetooth()
                    }
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Snackbar.make(binding.root, "Bluetooth permissions are required", Snackbar.LENGTH_INDEFINITE)
                .setAction("Retry") { checkPermissionsAndInit() }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Init Timber
        Timber.plant(Timber.DebugTree())   // This forwards logs to android.util.Log → Logcat

        // Init NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Check permissions and then init Bluetooth
        pn532Manager = Pn532Manager(this)
        checkPermissionsAndInit()

        // Bluetooth state receiver
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, filter)
        checkBluetoothState()

        // Init MediaSessionManager
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        checkNotificationPermission()
        if (isNotificationServiceEnabled()) {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                ComponentName(this, MediaNotificationListenerService::class.java)
            )
            // Initial check for active sessions
            updateActiveControllers(mediaSessionManager.getActiveSessions(ComponentName(this, MediaNotificationListenerService::class.java)))
        }

        // Set YouTube variant listener
        binding.radioPlayType.setOnCheckedChangeListener { _, checkedId ->
            youTubeVariant = when (checkedId) {
                R.id.radio_youtube_music -> MediaTypes.YOUTUBE_MUSIC
                else -> MediaTypes.YOUTUBE
            }
            Timber.d("YouTube variant changed to: $youTubeVariant")
        }

        // Process the intent that started the activity
        handleIntent(intent)
    }

    fun setStatus(text: String) {
        runOnUiThread {
            binding.textStatus.text = text
        }
    }

    private fun checkBluetoothState() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            setStatus("Skru på Blåtann for å koble til ekstern NFC leser")
        } else {
            pn532Manager?.initBluetooth()
        }
    }

    private fun showWriteDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_write_nfc, null)
        writeDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Skriv til NFC Tag")
            .setView(dialogView)
            .setNegativeButton("Avbryt") { _, _ ->
                mediaIdToWrite = null
            }
            .setOnDismissListener {
                writeDialog = null
            }
            .setCancelable(false)
            .show()
    }

    private fun updateWriteDialogMessage(message: String) {
        writeDialog?.findViewById<android.widget.TextView>(R.id.text_dialog_message)?.text = message
    }

    // Bluetooth functions
    fun checkPermissionsAndInit() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (!missingPermissions.isEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    // MediaSessionManager functions
    private fun checkNotificationPermission() {
        if (!isNotificationServiceEnabled()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Gi tilgang til varsler")
                .setMessage("Appen trenger tilgang til varsler for å kunne se hva som spiller av media på telefonen.")
                .setPositiveButton("Innstillinger") { _, _ ->
                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                }
                .setNegativeButton("Avbryt", null)
                .show()
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Vis over andre apper")
                .setMessage("Appen trenger tillatelse til å 'Vise over andre apper' for å kunne starte avspilling når den ligger i bakgrunnen.")
                .setPositiveButton("Innstillinger") { _, _ ->
                    val intent = Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton("Avbryt", null)
                .show()
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = this.packageName
        val flat = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!flat.isNullOrEmpty()) {
            val names = flat.split(":")
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == pkgName) {
                    return true
                }
            }
        }
        return false
    }

    private fun updateActiveControllers(controllers: List<MediaController>?) {
        val newControllers = controllers ?: emptyList()
        val supportedPackages = packageNames.values

        // Remove controllers that are no longer active
        val iterator = activeControllers.entries.iterator()
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
                val existing = activeControllers[controller.packageName]
                if (existing?.sessionToken != controller.sessionToken) {
                    existing?.unregisterCallback(mediaControllerCallback)
                    controller.registerCallback(mediaControllerCallback)
                    activeControllers[controller.packageName] = controller
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

        if (mediaId != null) {
            if (mediaId.startsWith("spotify:track:")) {
                currentMediaId = mediaId.substringAfter("spotify:track:")
                Timber.d("Extracted Spotify ID: $currentMediaId")
            } else if (mediaId.length == 11) { // Common length for YT video IDs
                currentMediaId = mediaId
            }
        }
    }

    private fun handleMediaPlaybackState(state: PlaybackState) {
        currentMediaIsPlaying = state.state == PlaybackState.STATE_PLAYING
        Timber.d("Media Playback State: playing=$currentMediaIsPlaying")
    }

    // Parser functions
    private enum class MediaTypes {
        UNKNOWN,
        YOUTUBE,
        YOUTUBE_MUSIC,
        SPOTIFY,
        TIDAL,
        PHONE_NUMBER,
    }

    private fun extractYoutubeId(url: String): String? {
        val patterns = listOf(
            "v=([^&]+)",
            "youtu.be/([^?]+)",
            "embed/([^?]+)",
            "shorts/([^?]+)"
        )
        for (pattern in patterns) {
            val regex = Regex(pattern)
            val match = regex.find(url)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private fun extractSpotifyId(url: String): String? {
        val regex = Regex("https://open.spotify.com/track/([a-zA-Z0-9]+)")
        val match = regex.find(url)
        if (match != null) return match.groupValues[1]
        return null
    }

    // YouTube functions
    fun extractAndPlayMedia(data: android.net.Uri) {
        // TODO: Move write operations here and support writing to external device?
        val youTubeId = data.getQueryParameter("yt") ?: data.toString().substringAfter("yt=", "")
        if (youTubeId.isNotBlank()) {
            playMedia(youTubeId, youTubeVariant)
        }
        val spotifyId = data.getQueryParameter("sp") ?: data.toString().substringAfter("sp=", "")
        if (spotifyId.isNotBlank()) {
            playMedia(spotifyId, MediaTypes.SPOTIFY)
        }
    }

    private fun playMedia(mediaId: String, mediaType: Enum<MediaTypes>) {
        if (mediaId == currentMediaId && mediaType == currentMediaType && currentMediaIsPlaying) {
            Timber.d("Already playing $mediaId on $mediaType")
            return
        }
        Timber.d("Playing media: $mediaId")
        currentMediaId = mediaId
        currentMediaType = mediaType

        var uri: Uri? = null
        when (mediaType) {
            MediaTypes.YOUTUBE -> {
                uri = "https://www.youtube.com/watch?v=$mediaId".toUri()
            }
            MediaTypes.YOUTUBE_MUSIC -> {
                uri = "https://music.youtube.com/watch?v=$mediaId".toUri()
            }
            MediaTypes.SPOTIFY -> {
                uri = "spotify:track:$mediaId".toUri()
            }
            MediaTypes.TIDAL -> {
                uri = "tidal://track/$mediaId".toUri()
            }
            MediaTypes.PHONE_NUMBER -> {
                // Not a media type
            }
        }

        if (uri != null) {
            // Try to use MediaController if the app has an active session
            val packageName = packageNames[mediaType]
            val controller = activeControllers[packageName]

            // YouTube works well with MediaController, but Spotify often triggers BAL blocks.
            // For Spotify, we use a PendingIntent with an explicit BAL bypass.
            if (controller != null && mediaType != MediaTypes.SPOTIFY) {
                Timber.d("Media controller found for $packageName, sending play command")
                controller.transportControls.playFromUri(uri, null)
                return
            }

            Timber.d("Launching $packageName via Intent with BAL bypass")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                val options = ActivityOptions.makeBasic()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    @Suppress("DEPRECATION")
                    options.pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
                
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                pendingIntent.send(this, 0, null, null, null, null, options.toBundle())
            } catch (e: Exception) {
                Timber.e(e, "Failed to launch activity via PendingIntent bypass")
                startActivity(intent)
            }
        }
    }

    // NFC functions
    override fun onResume() {
        super.onResume()
        enableNfcForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bluetoothStateReceiver)
        if (::mediaSessionManager.isInitialized) {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
        activeControllers.values.forEach { it.unregisterCallback(mediaControllerCallback) }
        activeControllers.clear()
    }

    private fun enableNfcForegroundDispatch() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        } else {
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        }
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Timber.d("onNewIntent: ${intent.action}")
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        // Handle Shared Text (YouTube link)
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                val videoId = extractYoutubeId(sharedText)
                if (videoId != null) {
                    mediaIdToWrite = videoId
                    mediaTypeToWrite = MediaTypes.YOUTUBE
                    showWriteDialog()
                }
                val spotifyId = extractSpotifyId(sharedText)
                if (spotifyId != null) {
                    mediaIdToWrite = spotifyId
                    mediaTypeToWrite = MediaTypes.SPOTIFY
                    showWriteDialog()
                }
            }
            return
        }

        when (intent.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_TAG_DISCOVERED -> {
                val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
                if (mediaIdToWrite != null && tag != null) {
                    writeToTag(tag, mediaIdToWrite!!, mediaTypeToWrite!!)
                    return
                }

                // 1. Try to get NDEF data from URI (standard for our nfcmp:// scheme)
                val data = intent.data
                if (data != null && data.scheme == "nfcmp") {
                    extractAndPlayMedia(data)
                    return
                } else if(data != null && data.scheme == "nfca") {
                    extractAndPlayMedia(data)
                    return
                } else {
                    Timber.d("Incorrect NDEF data found in intent")
                    // It might be a generic tag or one we're supposed to read via NDEF messages
                }

                // 2. Try to get NDEF data from messages in the intent
                val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
                if (rawMsgs != null) {
                    for (rawMsg in rawMsgs) {
                        val msg = rawMsg as NdefMessage
                        for (record in msg.records) {
                            val uri = record.toUri()
                            if (uri != null && uri.scheme == "nfcmp") {
                                extractAndPlayMedia(uri)
                                return
                            }
                        }
                    }
                }
            }
        }
    }

    private fun writeToTag(tag: Tag, mediaId: String, mediaType: Enum<MediaTypes>) {
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            val error = "NFC tag støtter ikke NDEF"
            if (writeDialog != null) {
                updateWriteDialogMessage("$error. Prøv en annen tag.")
            } else {
                Snackbar.make(binding.root, error, Snackbar.LENGTH_SHORT).show()
            }
            return
        }

        var typeIndicator = ""
        when (mediaType) {
            MediaTypes.YOUTUBE, MediaTypes.YOUTUBE_MUSIC -> {
                typeIndicator = "yt"
            }
            MediaTypes.SPOTIFY -> {
                typeIndicator = "sp"
            }
            MediaTypes.TIDAL -> {
                typeIndicator = "td"
            }
            MediaTypes.PHONE_NUMBER -> {
                typeIndicator = "ph"
            }
        }

        val uri = "nfca://e?$typeIndicator=$mediaId"
        val record = NdefRecord.createUri(uri)
        val message = NdefMessage(arrayOf(record))

        try {
            ndef.connect()
            if (!ndef.isWritable) {
                val error = "NFC tag er ikke skrivbar"
                if (writeDialog != null) {
                    updateWriteDialogMessage("$error. Prøv en annen tag.")
                } else {
                    Snackbar.make(binding.root, error, Snackbar.LENGTH_SHORT).show()
                }
                return
            }
            if (ndef.maxSize < message.toByteArray().size) {
                val error = "NFC tag har for lite plass"
                if (writeDialog != null) {
                    updateWriteDialogMessage("$error. Prøv en annen tag.")
                } else {
                    Snackbar.make(binding.root, error, Snackbar.LENGTH_SHORT).show()
                }
                return
            }
            ndef.writeNdefMessage(message)
            mediaIdToWrite = null
            writeDialog?.dismiss()
            Snackbar.make(binding.root, "Tag date skrevet!", Snackbar.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Timber.e(e, "Error writing to tag")
            val error = "Feil ved skriving til tag"
            if (writeDialog != null) {
                updateWriteDialogMessage("$error. Prøv igjen.")
            } else {
                Snackbar.make(binding.root, error, Snackbar.LENGTH_SHORT).show()
            }
        } finally {
            try {
                ndef.close()
            } catch (e: Exception) {
                Timber.e(e, "Error closing ndef")
            }
        }
    }
}
