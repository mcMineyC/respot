package com.example.librespotembedded

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import mobilebind.Librespot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LibrespotService : Service() {
    private val TAG = "LibrespotService"
    private val CHANNEL_ID = "librespot_channel"
    private val NOTIFICATION_ID = 1

    private var librespot: Librespot? = null
    private var mediaSession: MediaSession? = null
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    inner class LocalBinder : Binder() {
        fun getService(): LibrespotService = this@LibrespotService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "LibrespotService").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    handlePlay()
                }

                override fun onPause() {
                    handlePause()
                }

                override fun onSkipToNext() {
                    handleNext()
                }

                override fun onSkipToPrevious() {
                    handlePrevious()
                }

                override fun onStop() {
                    handlePause()
                    updatePlaybackState(PlaybackState.STATE_STOPPED)
                }
            })

            // STATIC METADATA - Placeholder
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Spotify Track")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Librespot Artist")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Librespot Album")
                .build()
            setMetadata(metadata)
            
            isActive = true
        }
        updatePlaybackState(PlaybackState.STATE_PAUSED)
    }

    fun handlePlay() {
        serviceScope.launch { librespot?.play() }
        updatePlaybackState(PlaybackState.STATE_PLAYING)
    }

    fun handlePause() {
        serviceScope.launch { librespot?.pause() }
        updatePlaybackState(PlaybackState.STATE_PAUSED)
    }

    fun handleNext() {
        serviceScope.launch { librespot?.next() }
    }

    fun handlePrevious() {
        serviceScope.launch { librespot?.previous() }
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
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
        
        // Update notification when playback state changes
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_PLAY" -> handlePlay()
            "ACTION_PAUSE" -> handlePause()
            "ACTION_NEXT" -> handleNext()
            "ACTION_PREVIOUS" -> handlePrevious()
        }
        return START_STICKY
    }

    fun getLibrespotInstance(): Librespot {
        if (librespot == null) {
            librespot = Librespot()
        }
        return librespot!!
    }

    private fun createNotificationChannel() {
        val name = "Librespot Playback"
        val descriptionText = "Controls and status for Librespot"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val controller = mediaSession?.controller
        val mediaMetadata = controller?.metadata
        val title = mediaMetadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Librespot"
        val artist = mediaMetadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Connected"

        val playPauseAction = if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) {
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
        super.onDestroy()
    }
}
