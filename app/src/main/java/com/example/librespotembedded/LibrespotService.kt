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
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import coil.ImageLoader
import coil.request.ImageRequest
import mobilebind.Librespot
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Locale

data class TrackMetadata(
    val uri: String,
    val name: String,
    val artistNames: List<String>,
    val albumName: String,
    val albumCoverUrl: String,
    val position: Long,
    val duration: Long,
    val releaseDate: String,
    val trackNumber: Int,
    val discNumber: Int
)

data class SpotifyDevice(
    val id: String,
    val name: String,
    val type: String,
    val isActive: Boolean
)

class LibrespotService : Service() {
    private val tag = "LibrespotService"
    private val channelId = "librespot_channel"
    private val notificationId = 1
    private val credentialsFileName = "creds.json"
    private val prefs by lazy { getSharedPreferences("librespot_prefs", Context.MODE_PRIVATE) }
    private val secureRandom = SecureRandom()

    private var librespot: Librespot? = null
    
    // Public state observable by Compose
    var isStarted by mutableStateOf(false)
    var isPlaying by mutableStateOf(false)
    var metadata by mutableStateOf<TrackMetadata?>(null)
    var positionMs by mutableLongStateOf(0L)
    var isLoggedIn by mutableStateOf(false)
    var isAuthenticating by mutableStateOf(false)
    
    var shuffleEnabled by mutableStateOf(false)
    var repeatMode by mutableIntStateOf(0) // 0: off, 1: track, 2: context
    var availableDevices by mutableStateOf<List<SpotifyDevice>>(emptyList())

    // Configurable Settings
    var deviceName by mutableStateOf("")
    var deviceType by mutableStateOf("")
    var initialVolume by mutableIntStateOf(14)
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
        startForeground(notificationId, createNotification())
        
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
            isStarted = false
            try {
                librespot?.stop()
            } catch (e: Exception) {
                Log.e(tag, "Error stopping Librespot", e)
            }
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
                if (isPlaying) {
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
                override fun onSeekTo(pos: Long) { handleSeek(pos) }
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
        Log.i(tag, "Starting Librespot with config: ${cfg.toString(2)}")

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
                Log.d(tag, "Credentials persisted")
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
                    Log.d(tag, "Librespot Event: $event, Data: $data")
                    // Use Main dispatcher to update Compose state for safety and consistency
                    serviceScope.launch(Dispatchers.Main) {
                        when (event) {
                            "playing" -> {
                                isPlaying = true
                                updatePlaybackState(PlaybackState.STATE_PLAYING)
                            }
                            "paused" -> {
                                isPlaying = false
                                parsePosition(data)
                                updatePlaybackState(PlaybackState.STATE_PAUSED)
                            }
                            "seek" -> {
                                parsePosition(data)
                                updatePlaybackState(if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED)
                            }
                            "shuffle" -> {
                                shuffleEnabled = data.toBoolean()
                            }
                            "repeat" -> {
                                repeatMode = when(data) {
                                    "track" -> 1
                                    "context" -> 2
                                    else -> 0
                                }
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
                                        artistNames = artists,
                                        albumName = json.optString("album_name"),
                                        albumCoverUrl = json.optString("album_cover_url"),
                                        position = json.optLong("position"),
                                        duration = json.optLong("duration"),
                                        releaseDate = json.optString("release_date"),
                                        trackNumber = json.optInt("track_number"),
                                        discNumber = json.optInt("disc_number")
                                    )
                                    metadata = meta
                                    positionMs = meta.position
                                    updateMediaMetadata(meta)
                                    isLoggedIn = true
                                    isAuthenticating = false
                                    
                                    // Update shuffle/repeat from metadata if available
                                    shuffleEnabled = json.optBoolean("shuffle", shuffleEnabled)
                                    val repeatStr = json.optString("repeat", "")
                                    if (repeatStr.isNotEmpty()) {
                                        repeatMode = when(repeatStr) {
                                            "track" -> 1
                                            "context" -> 2
                                            else -> 0
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(tag, "Failed to parse metadata", e)
                                }
                            }
                        }
                    }
                }
                override fun onLog(level: String, log: String) {
                    Log.d(tag, "Librespot Log [$level]: $log")
                }
            })
            isStarted = true
            serviceScope.launch {
                delay(1000)
                syncState()
            }
            Log.d(tag, "Librespot started successfully")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start Librespot", e)
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
            val bitmap = loadBitmap(meta.albumCoverUrl)
            val builder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, meta.name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, meta.artistNames.joinToString(", "))
                .putString(MediaMetadata.METADATA_KEY_ALBUM, meta.albumName)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, meta.duration)
            
            if (bitmap != null) {
                builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
            }
            
            val mediaMetadata = builder.build()
            
            withContext(Dispatchers.Main) {
                mediaSession?.setMetadata(mediaMetadata)
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(notificationId, createNotification())
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
                    .allowHardware(false) 
                    .build()
                val result = loader.execute(request)
                (result.drawable as? BitmapDrawable)?.bitmap
            } catch (e: Exception) {
                Log.e(tag, "Failed to load bitmap", e)
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
                Log.e(tag, "Error in play()", e)
            }
        }
    }

    fun handlePause() {
        if (!isStarted) return
        serviceScope.launch {
            try {
                librespot?.pause()
            } catch (e: Exception) {
                Log.e(tag, "Error in pause()", e)
            }
        }
    }

    fun handleNext() {
        if (!isStarted) return
        serviceScope.launch {
            try {
                librespot?.next()
            } catch (e: Exception) {
                Log.e(tag, "Error in next()", e)
            }
        }
    }

    fun handlePrevious() {
        if (!isStarted) return
        serviceScope.launch {
            try {
                librespot?.previous()
            } catch (e: Exception) {
                Log.e(tag, "Error in previous()", e)
            }
        }
    }

    fun handleSeek(pos: Long) {
        if (!isStarted) return
        serviceScope.launch {
            try {
                librespot?.seek(pos)
                positionMs = pos
            } catch (e: Exception) {
                Log.e(tag, "Error in seek()", e)
            }
        }
    }

    fun handleShuffle(enabled: Boolean) {
        if (!isStarted) return
        serviceScope.launch {
            try {
                librespot?.setShuffle(enabled)
                shuffleEnabled = enabled
            } catch (e: Exception) {
                Log.e(tag, "Error in setShuffle()", e)
            }
        }
    }

    fun handleRepeat(mode: Int) {
        if (!isStarted) return
        val modeStr = when(mode) {
            1 -> "track"
            2 -> "context"
            else -> "off"
        }
        serviceScope.launch {
            try {
                librespot?.setRepeat(modeStr)
                repeatMode = mode
            } catch (e: Exception) {
                Log.e(tag, "Error in setRepeat()", e)
            }
        }
    }

    fun refreshDevices() {
        if (!isStarted) return
        serviceScope.launch {
            try {
                val jsonStr = librespot?.listDevicesJSON() ?: "[]"
                val arr = JSONArray(jsonStr)
                val list = mutableListOf<SpotifyDevice>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(SpotifyDevice(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        type = obj.getString("type"),
                        isActive = obj.getBoolean("is_active")
                    ))
                }
                withContext(Dispatchers.Main) {
                    availableDevices = list
                }
            } catch (e: Exception) {
                Log.e(tag, "Error in listDevicesJSON()", e)
            }
        }
    }

    fun transferPlayback(deviceId: String) {
        if (!isStarted) return
        serviceScope.launch {
            try {
                librespot?.transferPlayback(deviceId, true)
                // Sync state immediately after transfer to get shuffle/repeat settings
                delay(1500)
                syncState()
                refreshDevices()
            } catch (e: Exception) {
                Log.e(tag, "Error in transferPlayback()", e)
            }
        }
    }

    fun syncState() {
        return
        if (!isStarted) return
        serviceScope.launch {
            try {
                // Discover the correct method names from the AAR using reflection or as provided by user
                val shuffle = librespot?.javaClass?.getMethod("getShuffle")?.invoke(librespot) as? Boolean ?: false
                val repeat = librespot?.javaClass?.getMethod("getRepeat")?.invoke(librespot) as? String ?: "off"
                
                withContext(Dispatchers.Main) {
                    shuffleEnabled = shuffle
                    repeatMode = when(repeat) {
                        "track" -> 1
                        "context" -> 2
                        else -> 0
                    }
                    updatePlaybackState(if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED)
                }
            } catch (e: Exception) {
                // Fallback to dumpStateJSON if specific getters fail
                try {
                    val stateJson = librespot?.dumpStateJSON() ?: return@launch
                    val json = JSONObject(stateJson)
                    withContext(Dispatchers.Main) {
                        isPlaying = json.optBoolean("playing", isPlaying)
                        shuffleEnabled = json.optBoolean("shuffle", shuffleEnabled)
                        val repeatStr = json.optString("repeat", "")
                        if (repeatStr.isNotEmpty()) {
                            repeatMode = when(repeatStr) {
                                "track" -> 1
                                "context" -> 2
                                else -> 0
                            }
                        }
                        positionMs = json.optLong("position", positionMs)
                        updatePlaybackState(if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED)
                    }
                } catch (e2: Exception) {
                    Log.e(tag, "Error syncing state with fallback", e2)
                }
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
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SEEK_TO
            )
            .setState(state, positionMs, 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
        
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, createNotification())
    }

    private fun createNotificationChannel() {
        val name = "Librespot Playback"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(channelId, name, importance)
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

        val playPauseAction = NotificationCompat.Action(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "Pause" else "Play",
            PendingIntent.getService(this, if (isPlaying) 1 else 2, 
                Intent(this, LibrespotService::class.java).apply { action = if (isPlaying) "ACTION_PAUSE" else "ACTION_PLAY" }, 
                PendingIntent.FLAG_IMMUTABLE)
        )

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val mediaStyle = MediaNotificationCompat.MediaStyle()
            .setMediaSession(android.support.v4.media.session.MediaSessionCompat.Token.fromToken(mediaSession?.sessionToken))
            .setShowActionsInCompactView(0, 1, 2)

        return NotificationCompat.Builder(this, channelId)
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
