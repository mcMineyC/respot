package com.example.librespotembedded

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.SkipNext
import androidx.compose.material.icons.automirrored.filled.SkipPrevious
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.librespotembedded.ui.theme.LibrespotEmbeddedTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val TAG = "LibrespotDemo"

    private var librespotService: LibrespotService? by mutableStateOf(null)
    private var isBound by mutableStateOf(false)
    
    private lateinit var webView: WebView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LibrespotService.LocalBinder
            val s = binder.getService()
            librespotService = s
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            librespotService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Start and bind to the foreground service
        val intent = Intent(this, LibrespotService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
        }

        setContent {
            LibrespotEmbeddedTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val service = librespotService
                    var showAuth by remember { mutableStateOf(false) }
                    var isAuthenticating by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    if (service == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        if (showAuth) {
                            AndroidView(
                                factory = { webView },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (isAuthenticating) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .safeDrawingPadding(), 
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Finishing authentication...", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        } else {
                            LibrespotPlayerUI(
                                service = service,
                                onAuthenticate = {
                                    val authUrl = service.beginInteractiveAuth()
                                    webView.webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                            if (url != null && url.startsWith("http://127.0.0.1")) {
                                                showAuth = false
                                                isAuthenticating = true
                                                scope.launch(Dispatchers.IO) {
                                                    try {
                                                        service.completeInteractiveAuth(url)
                                                    } catch (e: Exception) {
                                                        Log.e(TAG, "Error completing auth", e)
                                                    } finally {
                                                        withContext(Dispatchers.Main) {
                                                            isAuthenticating = false
                                                        }
                                                    }
                                                }
                                                return true
                                            }
                                            return false
                                        }
                                    }
                                    webView.loadUrl(authUrl)
                                    showAuth = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}

@Composable
fun LibrespotPlayerUI(
    service: LibrespotService,
    onAuthenticate: () -> Unit
) {
    val metadata = service.metadata
    val isPlaying = service.is_playing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        // Album Art
        Card(
            modifier = Modifier
                .size(320.dp)
                .aspectRatio(1f),
            shape = MaterialTheme.shapes.large,
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            if (metadata?.album_cover_url?.isNotEmpty() == true) {
                AsyncImage(
                    model = metadata.album_cover_url,
                    contentDescription = "Album Art",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(100.dp),
                        tint = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Track Info
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = metadata?.name ?: "No Track Playing",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = metadata?.artist_names?.joinToString(", ") ?: "Connect to start",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (metadata?.album_name?.isNotEmpty() == true) {
                Text(
                    text = metadata.album_name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Progress Slider (Placeholder interaction for now)
        val position = metadata?.position?.toFloat() ?: 0f
        val duration = metadata?.duration?.toFloat() ?: 1f
        val progress = if (duration > 0) position / duration else 0f
        
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Slider(
                value = progress,
                onValueChange = { /* Handle seek? */ },
                enabled = false, // Service doesn't support seek yet in this UI
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatTime(metadata?.position ?: 0), style = MaterialTheme.typography.labelMedium)
                Text(formatTime(metadata?.duration ?: 0), style = MaterialTheme.typography.labelMedium)
            }
        }

        // Controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            IconButton(
                onClick = { service.handlePrevious() },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.SkipPrevious,
                    contentDescription = "Previous",
                    modifier = Modifier.size(40.dp)
                )
            }

            FilledIconButton(
                onClick = { if (isPlaying) service.handlePause() else service.handlePlay() },
                modifier = Modifier.size(80.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(48.dp)
                )
            }

            IconButton(
                onClick = { service.handleNext() },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(
            onClick = onAuthenticate,
            modifier = Modifier.fillMaxWidth(0.7f)
        ) {
            Text("Switch Account")
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
