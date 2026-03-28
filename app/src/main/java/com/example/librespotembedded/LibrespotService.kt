package com.example.librespotembedded

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import coil.ImageLoader
import coil.request.ImageRequest
import mobilebind.Librespot
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

data class TrackMetadata(
    val uri: String,
    val name: String,
    val artist_names: List<String>,
    val album_name: String,
    val album_cover_url: String,
    val position: Long,
    val duration: Long,
    val release_date: String,
    val track_number: Int,
    val disc_number: Int
)

class LibrespotService : Service() {
    private val TAG = "LibrespotService"
    private val CHANNEL_ID = "librespot_channel"
    private val NOTIFICATION_ID = 1
    private val credentialsFileName = "creds.json"
    private val prefs by lazy { getSharedPreferences("librespot_prefs", Context.MODE_PRIVATE) }
    private val secureRandom = SecureRandom()

    private var librespot: Librespot? = null
    
    // Public state observable by Compose
    var isStarted by mutableStateOf(false)
    var is_playing by mutableStateOf(false)
    var metadata by mutableStateOf<TrackMetadata?>(null)
    var positionMs by mutableStateOf(0L)
    var isLoggedIn by mutableStateOf(false)
    var isAuthenticating by mutableStateOf(false)

    // Configurable Settings
    var deviceName by mutableStateOf("")
    var deviceType by mutableStateOf("")
    var initialVolume by mutableStateOf(14)
    var deviceId by mutableStateOf("")

    private var mediaSession: MediaSession? = null
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var progressJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): LibrespotService = this@LibrespotService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        loadSettings()
        createNotificationChannel()
        setupMediaSession()
        startForeground(NOTIFICATION_ID, createNotification())
        
        isLoggedIn = restoreCredentials() != null

        serviceScope.launch {
            setupLibrespot()
        }
        
        startProgressUpdateLoop()
    }

    private fun generateRandomDeviceId(): String {
        val bytes = ByteArray(20)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    private fun loadSettings() {
        deviceName = prefs.getString("device_name", "Librespot Embedded") ?: "Librespot Embedded"
        deviceType = prefs.getString("device_type", "smartphone") ?: "smartphone"
        initialVolume = prefs.getInt("initial_volume", 14)
        
        val storedId = prefs.getString("device_id", null)
        if (storedId == null) {
            val newId = generateRandomDeviceId()
            prefs.edit { putString("device_id", newId) }
            deviceId = newId
        } else {
            deviceId = storedId
        }
    }

    fun saveSettings(name: String, type: String, volume: Int) {
        prefs.edit {
            putString("device_name", name)
            putString("device_type", type)
            putInt("initial_volume", volume)
        }
        deviceName = name
        deviceType = type
        initialVolume = volume
        restart()
    }

    fun regenerateDeviceId() {
        val newId = generateRandomDeviceId()
        prefs.edit { putString("device_id", newId) }
        deviceId = newId
        restart()
    }

    fun logout() {
        val file = File(filesDir, credentialsFileName)
        if (file.exists()) file.delete()
        isLoggedIn = false
        restart()
    }

    fun restart() {
        serviceScope.launch {
            // We create a new instance to apply new config. 
            // The daemon inside might need cleanup if mobilebind supported it.
            isStarted = false
            librespot = Librespot() 
            setupLibrespot()
        }
    }

    private fun startProgressUpdateLoop() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            var lastUpdate = System.currentTimeMillis()
            while (isActive) {
                val now = System.currentTimeMillis()
                val delta = now - lastUpdate
                if (is_playing) {
                    positionMs += delta
                }
                lastUpdate = now
                delay(50) 
            }
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "LibrespotService").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { handlePlay() }
                override fun onPause() { handlePause() }
                override fun onSkipToNext() { handleNext() }
                override fun onSkipToPrevious() { handlePrevious() }
                override fun onStop() {
                    handlePause()
                    updatePlaybackState(PlaybackState.STATE_STOPPED)
                }
            })
            isActive = true
        }
        updatePlaybackState(PlaybackState.STATE_PAUSED)
    }

    private suspend fun setupLibrespot() {
        val instance = getLibrespotInstance()
        
        val cfg = JSONObject().apply {
            put("device_id", deviceId)
            put("device_name", deviceName)
            put("device_type", deviceType)
            put("audio_backend", "oto")
            put("volume_steps", 16)
            put("initial_volume", initialVolume)
            put("flac_enabled", false)
            put("credentials", JSONObject().apply {
                put("type", "interactive")
                put("spotify_token", JSONObject().apply {
                    put("username", "")
                    put("data", "")
                })
            })
            put("server", JSONObject().apply { put("enabled", false) })
            put("zeroconf_enabled", false)
        }
        Log.i(TAG, "Starting Librespot with config: ${cfg.toString(2)}")

        restoreCredentials()?.let { (u, d) ->
            cfg.getJSONObject("credentials")
                .getJSONObject("spotify_token")
                .apply {
                    put("username", u)
                    put("data", d)
                }
        }

        instance.setCredentialCallback(object : mobilebind.CredentialCallback {
            override fun onCredentialsPersist(credentialsJson: String) {
                Log.d(TAG, "Credentials persisted")
                val j = JSONObject(credentialsJson)
                val u = j.optString("username")
                val d = j.optString("data")
                val outFile = File(filesDir, credentialsFileName)
                outFile.writeText(JSONObject().apply {
                    put("username", u)
                    put("data", d)
                }.toString())
                serviceScope.launch(Dispatchers.Main) {
                    isLoggedIn = true
                    isAuthenticating = false
                }
            }
        })

        try {
            instance.startWithConfigJSON(cfg.toString(), object : mobilebind.EventCallback {
                override fun onEvent(event: String, data: String) {
                    Log.d(TAG, "Librespot Event: $event, Data: $data")
                    // Use Main dispatcher to update Compose state for safety and consistency
                    serviceScope.launch(Dispatchers.Main) {
                        when (event) {
                            "playing" -> {
                                is_playing = true
                                updatePlaybackState(PlaybackState.STATE_PLAYING)
                            }
                            "paused" -> {
                                is_playing = false
                                parsePosition(data)
                                updatePlaybackState(PlaybackState.STATE_PAUSED)
                            }
                            "seek" -> {
                                parsePosition(data)
                                updatePlaybackState(if (is_playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED)
                            }
                            "metadata" -> {
                                try {
                                    val json = JSONObject(data)
                                    val artists = mutableListOf<String>()
                                    val artistsArray = json.optJSONArray("artist_names")
                                    if (artistsArray != null) {
                                        for (i in 0 until artistsArray.length()) {
                                            artists.add(artistsArray.getString(i))
                                        }
                                    }
                                    val meta = TrackMetadata(
                                        uri = json.optString("uri"),
                                        name = json.optString("name"),
                                        artist_names = artists,
                                        album_name = json.optString("album_name"),
                                        album_cover_url = json.optString("album_cover_url"),
                                        position = json.optLong("position"),
                                        duration = json.optLong("duration"),
                                        release_date = json.optString("release_date"),
                                        track_number = json.optInt("track_number"),
                                        disc_number = json.optInt("disc_number")
                                    )
                                    metadata = meta
                                    positionMs = meta.position
                                    updateMediaMetadata(meta)
                                    isLoggedIn = true
                                    isAuthenticating = false
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to parse metadata", e)
                                }
                            }
                        }
                    }
                }
                override fun onLog(level: String, log: String) {
                    Log.d(TAG, "Librespot Log [$level]: $log")
                }
            })
            isStarted = true
            Log.d(TAG, "Librespot started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Librespot", e)
        }
    }

    private fun parsePosition(data: String) {
        if (data.isEmpty()) return
        try {
            val json = JSONObject(data)
            positionMs = json.optLong("position", positionMs)
        } catch (e: Exception) {
            positionMs = data.toLongOrNull() ?: positionMs
        }
    }

    private fun updateMediaMetadata(meta: TrackMetadata) {
        serviceScope.launch {
            val bitmap = loadBitmap(meta.album_cover_url)
            val builder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, meta.name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, meta.artist_names.joinToString(", "))
                .putString(MediaMetadata.METADATA_KEY_ALBUM, meta.album_name)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, meta.duration)
            
            if (bitmap != null) {
                builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                builder.putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
            }
            
            val mediaMetadata = builder.build()
            
            withContext(Dispatchers.Main) {
                mediaSession?.setMetadata(mediaMetadata)
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, createNotification())
            }
        }
    }

    private suspend fun loadBitmap(url: String): Bitmap? {
        if (url.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            try {
                val loader = ImageLoader(this@LibrespotService)
                val request = ImageRequest.Builder(this@LibrespotService)
                    .data(url)
                    .allowHardware(false) // Required for getting bitmap to use in notification
                    .build()
                val result = loader.execute(request)
                (result.drawable as? BitmapDrawable)?.bitmap
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load bitmap", e)
                null
            }
        }
    }

    fun restoreCredentials(): Pair<String, String>? {
        val file = File(filesDir, credentialsFileName)
        if (!file.exists()) return null
        val txt = file.readText()
        val j = JSONObject(txt)
        val u = j.optString("username")
        val d = j.optString("data")
        return if (u.isNotEmpty() && d.isNotEmpty()) Pair(u, d) else null
    }

    fun beginInteractiveAuth(): String {
        isAuthenticating = true
        return librespot?.beginInteractiveAuth() ?: ""
    }

    fun completeInteractiveAuth(url: String) {
        librespot?.completeInteractiveAuth(url)
    }


    fun handlePlay() {
        if (!isStarted) return
        serviceScope.launch {
            try {
                librespot?.play()
            } catch (e: Exception) {
                Log.e(TAG, "Error in play()", e)
            }
        }
    }

    fun handlePause() {
        if (!isStarted) return
        serviceScope.launch {
            try {
                librespot?.pause()
            } catch (e: Exception) {
                Log.e(TAG, "Error in pause()", e)
            }
        }
    }

    fun handleNext() {
        if (!isStarted) return
        serviceScope.launch {
            try {
                librespot?.next()
            } catch (e: Exception) {
                Log.e(TAG, "Error in next()", e)
            }
        }
    }

    fun handlePrevious() {
        if (!isStarted) return
        serviceScope.launch {
            try {
                librespot?.previous()
            } catch (e: Exception) {
                Log.e(TAG, "Error in previous()", e)
            }
        }
    }

    private fun updatePlaybackState(state: Int) {
        val playbackState = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_STOP
            )
            .setState(state, positionMs, 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
        
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun createNotificationChannel() {
        val name = "Librespot Playback"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance)
        val notificationManager: NotificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val controller = mediaSession?.controller
        val mediaMetadata = controller?.metadata
        val title = mediaMetadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Librespot"
        val artist = mediaMetadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Connected"
        val artwork = mediaMetadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)

        val playPauseAction = if (is_playing) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Pause",
                PendingIntent.getService(this, 1, Intent(this, LibrespotService::class.java).apply { action = "ACTION_PAUSE" }, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Play",
                PendingIntent.getService(this, 2, Intent(this, LibrespotService::class.java).apply { action = "ACTION_PLAY" }, PendingIntent.FLAG_IMMUTABLE)
            )
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(android.support.v4.media.session.MediaSessionCompat.Token.fromToken(mediaSession?.sessionToken))
            .setShowActionsInCompactView(0, 1, 2)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setLargeIcon(artwork)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(mediaStyle)
            .addAction(android.R.drawable.ic_media_previous, "Previous", 
                PendingIntent.getService(this, 3, Intent(this, LibrespotService::class.java).apply { action = "ACTION_PREVIOUS" }, PendingIntent.FLAG_IMMUTABLE))
            .addAction(playPauseAction)
            .addAction(android.R.drawable.ic_media_next, "Next", 
                PendingIntent.getService(this, 4, Intent(this, LibrespotService::class.java).apply { action = "ACTION_NEXT" }, PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    override fun onDestroy() {
        mediaSession?.release()
        progressJob?.cancel()
        super.onDestroy()
    }

    fun getLibrespotInstance(): Librespot {
        if (librespot == null) {
            librespot = Librespot()
        }
        return librespot!!
    }
}
