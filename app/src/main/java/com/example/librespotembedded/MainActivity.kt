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
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.librespotembedded.ui.theme.LibrespotEmbeddedTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sin

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
    val position = service.positionMs
    val duration = metadata?.duration ?: 1L
    val progress = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    // Expressive scale animation for the album art
    val albumArtScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.92f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "albumArtScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Expressive Album Art - connected to playback toggle
        Card(
            onClick = { if (isPlaying) service.handlePause() else service.handlePlay() },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .scale(albumArtScale),
            shape = RoundedCornerShape(48.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isPlaying) 12.dp else 2.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (metadata?.album_cover_url?.isNotEmpty() == true) {
                    AsyncImage(
                        model = metadata.album_cover_url,
                        contentDescription = "Album Art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(120.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                        )
                    }
                }
                
                // Show play icon overlay when paused to signify clickability
                if (!isPlaying && metadata != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier.size(80.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                            tonalElevation = 8.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Play",
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Track Info
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = metadata?.name ?: "No Track Playing",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = metadata?.artist_names?.joinToString(", ") ?: "Connect to start",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Wiggly Progress Bar
        WigglyProgressBar(
            progress = progress,
            isPlaying = isPlaying,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(position),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatTime(duration),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        // Expressive Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            IconButton(
                onClick = { service.handlePrevious() },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = "Previous",
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Surface(
                onClick = { if (isPlaying) service.handlePause() else service.handlePlay() },
                modifier = Modifier.size(96.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            IconButton(
                onClick = { service.handleNext() },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Next",
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        if (!service.isLoggedIn) {
            Button(
                onClick = onAuthenticate,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Login to Spotify", style = MaterialTheme.typography.titleMedium)
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun WigglyProgressBar(
    progress: Float,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wiggle")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val amplitude by animateFloatAsState(
        targetValue = if (isPlaying) 6f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "amplitude"
    )

    val color = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerY = height / 2
        val strokeWidth = 8.dp.toPx()
        
        // Draw Track
        drawLine(
            color = trackColor,
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // Draw Wiggly Progress
        val path = Path()
        val segments = 100
        val progressWidth = width * progress
        
        path.moveTo(0f, centerY)
        for (i in 0..segments) {
            val x = (i.toFloat() / segments) * progressWidth
            // Sine wave wiggle
            val wiggle = if (x > 0) sin(x * 0.05f + phase) * amplitude else 0f
            path.lineTo(x, centerY + wiggle)
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        
        // Thumb
        drawCircle(
            color = color,
            radius = 10.dp.toPx(),
            center = Offset(progressWidth, centerY + sin(progressWidth * 0.05f + phase) * amplitude)
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
