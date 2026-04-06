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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
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
                        } else if (service.isAuthenticating) {
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
                                    Spacer(modifier = Modifier.height(24.dp))
                                    TextButton(onClick = { service.isAuthenticating = false }) {
                                        Text("Cancel")
                                    }
                                }
                            }
                        } else {
                            LibrespotPlayerUI(
                                service = service,
                                onAuthenticate = {
                                    val authUrl = service.beginInteractiveAuth()
                                    setupWebViewClient(service) { showAuth = false }
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

    private fun setupWebViewClient(service: LibrespotService, onRedirect: () -> Unit) {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && url.startsWith("http://127.0.0.1")) {
                    Log.d(TAG, "Auth redirect detected: $url")
                    onRedirect()
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            service.completeInteractiveAuth(url)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error completing auth", e)
                            withContext(Dispatchers.Main) {
                                service.isAuthenticating = false
                            }
                        }
                    }
                    return true
                }
                return false
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
    val isPlaying = service.isPlaying
    val position = service.positionMs
    val duration = metadata?.duration ?: 1L
    val progress = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    var showSettings by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }

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
        // Album Art
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
                if (metadata?.albumCoverUrl?.isNotEmpty() == true) {
                    AsyncImage(
                        model = metadata.albumCoverUrl,
                        contentDescription = "Album Art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
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
                
                if (!isPlaying && metadata != null) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)),
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

        Spacer(modifier = Modifier.height(40.dp))

        // Track Info
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(
                text = metadata?.name ?: "No Track Playing",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = metadata?.artistNames?.joinToString(", ") ?: "Connect to start",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Wiggly Progress with Seek interaction
        WigglyProgressBar(
            progress = progress, 
            isPlaying = isPlaying, 
            modifier = Modifier.fillMaxWidth().height(48.dp),
            onSeek = { newProgress ->
                if (duration > 0) {
                    service.handleSeek((newProgress * duration).toLong())
                }
            }
        )

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = formatTime(position), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = formatTime(duration), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Main Controls
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceAround) {
            // Shuffle
            IconButton(onClick = { service.handleShuffle(!service.shuffleEnabled) }) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (service.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = { service.handlePrevious() }, modifier = Modifier.size(56.dp)) {
                Icon(imageVector = Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
            }

            Surface(
                onClick = { if (isPlaying) service.handlePause() else service.handlePlay() },
                modifier = Modifier.size(88.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            IconButton(onClick = { service.handleNext() }, modifier = Modifier.size(56.dp)) {
                Icon(imageVector = Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
            }

            // Repeat
            IconButton(onClick = { service.handleRepeat((service.repeatMode + 1) % 3) }) {
                Icon(
                    imageVector = when(service.repeatMode) {
                        1 -> Icons.Rounded.RepeatOne
                        else -> Icons.Rounded.Repeat
                    },
                    contentDescription = "Repeat",
                    tint = if (service.repeatMode > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Bottom Actions
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalIconButton(
                onClick = { 
                    service.refreshDevices()
                    showDevices = true 
                },
                modifier = Modifier.size(56.dp),
                shape = CircleShape
            ) {
                Icon(imageVector = Icons.Rounded.Devices, contentDescription = "Devices")
            }

            Spacer(modifier = Modifier.width(16.dp))

            if (!service.isLoggedIn) {
                Button(
                    onClick = onAuthenticate,
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = CircleShape
                ) {
                    Text("Login to Spotify")
                }
                Spacer(modifier = Modifier.width(16.dp))
            }
            
            FilledTonalIconButton(
                onClick = { showSettings = true },
                modifier = Modifier.size(56.dp),
                shape = CircleShape
            ) {
                Icon(imageVector = Icons.Rounded.Settings, contentDescription = "Settings")
            }
        }
    }

    if (showSettings) {
        SettingsDialog(service = service, onDismiss = { showSettings = false }, onReLogin = onAuthenticate)
    }

    if (showDevices) {
        DevicesDialog(service = service, onDismiss = { showDevices = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesDialog(
    service: LibrespotService,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 48.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Devices", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = { service.refreshDevices() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            if (service.availableDevices.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No other devices found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    service.availableDevices.forEach { device ->
                        Surface(
                            onClick = { 
                                service.transferPlayback(device.id)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = if (device.isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when(device.type.lowercase()) {
                                        "smartphone" -> Icons.Rounded.Smartphone
                                        "computer" -> Icons.Rounded.Computer
                                        "tablet" -> Icons.Rounded.Tablet
                                        "speaker" -> Icons.Rounded.Speaker
                                        else -> Icons.Rounded.Devices
                                    },
                                    contentDescription = null,
                                    tint = if (device.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(device.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                    Text(if (device.isActive) "Active" else "Spotify Connect", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    service: LibrespotService,
    onDismiss: () -> Unit,
    onReLogin: () -> Unit
) {
    var name by remember { mutableStateOf(service.deviceName) }
    var type by remember { mutableStateOf(service.deviceType) }
    var vol by remember { mutableIntStateOf(service.initialVolume) }
    val creds = service.restoreCredentials()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Device Name") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )

            Column {
                Text("Device Type", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("smartphone", "computer", "tablet", "speaker").forEach {
                        FilterChip(
                            selected = type == it,
                            onClick = { type = it },
                            label = { Text(it.replaceFirstChar { it.uppercase() }) },
                            shape = CircleShape
                        )
                    }
                }
            }

            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Initial Volume", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(vol.toString(), style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = vol.toFloat(), 
                    onValueChange = { vol = it.toInt() }, 
                    valueRange = 0f..16f, 
                    steps = 15
                )
            }

            Column {
                Text("Device ID", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(service.deviceId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { service.regenerateDeviceId() }, shape = CircleShape) {
                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Regenerate ID")
                }
            }

            creds?.let { (u, _) ->
                Column(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(16.dp)
                ) {
                    Text("Logged in as", style = MaterialTheme.typography.labelSmall)
                    Text(u, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { 
                            service.logout()
                            onDismiss()
                            onReLogin()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                        shape = CircleShape
                    ) {
                        Text("Logout & Re-login")
                    }
                }
            }

            Button(
                onClick = {
                    service.saveSettings(name, type, vol)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = CircleShape
            ) {
                Text("Save & Restart")
            }
        }
    }
}

@Composable
fun WigglyProgressBar(
    progress: Float,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onSeek: (Float) -> Unit = {}
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

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                val newProgress = (offset.x / size.width).coerceIn(0f, 1f)
                onSeek(newProgress)
            }
        }
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2
        val strokeWidth = 8.dp.toPx()
        
        drawLine(color = trackColor, start = Offset(0f, centerY), end = Offset(width, centerY), strokeWidth = strokeWidth, cap = StrokeCap.Round)

        val path = Path()
        val segments = 100
        val progressWidth = width * progress
        
        path.moveTo(0f, centerY)
        for (i in 0..segments) {
            val x = (i.toFloat() / segments) * progressWidth
            val wiggle = if (x > 0) sin(x * 0.05f + phase) * amplitude else 0f
            path.lineTo(x, centerY + wiggle)
        }

        drawPath(path = path, color = color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
        drawCircle(color = color, radius = 10.dp.toPx(), center = Offset(progressWidth, centerY + sin(progressWidth * 0.05f + phase) * amplitude))
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
