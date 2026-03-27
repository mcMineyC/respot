// MainActivity.kt
package com.example.librespotembedded

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.librespotembedded.ui.theme.LibrespotEmbeddedTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mobilebind.Librespot
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {
    private val TAG = "LibrespotDemo"
    private val credentialsFileName = "creds.json"

    private var librespotService: LibrespotService? by mutableStateOf(null)
    private var isBound by mutableStateOf(false)
    private var librespot by mutableStateOf<Librespot?>(null)
    
    private lateinit var webView: WebView

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LibrespotService.LocalBinder
            librespotService = binder.getService()
            isBound = true
            
            // Now that we're bound, initialize librespot via the service
            setupLibrespot(librespotService!!)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            librespotService = null
        }
    }

    companion object {
        init {
            try {
                Log.i("LibrespotNative", "Support libraries loaded successfully")
                System.loadLibrary("gojni")
                Log.i("LibrespotNative", "gojni loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("LibrespotNative", "Failed to load libraries", e)
            }
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
                    var hasNotificationPermission by remember {
                        mutableStateOf(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                ContextCompat.checkSelfPermission(
                                    this,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                            } else {
                                true
                            }
                        )
                    }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        hasNotificationPermission = isGranted
                    }

                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    var currentTrack by remember { mutableStateOf("—") }
                    var showAuth by remember { mutableStateOf(false) }
                    var isAuthenticating by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    val currentLibrespot = librespot
                    if (currentLibrespot == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .safeDrawingPadding(), 
                            contentAlignment = Alignment.Center
                        ) {
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
                            Box(modifier = Modifier.safeDrawingPadding()) {
                                LibrespotUI(
                                    librespotService = librespotService,
                                    onAuthenticate = {
                                        val authUrl = currentLibrespot.beginInteractiveAuth()
                                        webView.webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                                if (url != null && url.startsWith("http://127.0.0.1")) {
                                                    showAuth = false
                                                    isAuthenticating = true
                                                    scope.launch(Dispatchers.IO) {
                                                        try {
                                                            currentLibrespot.completeInteractiveAuth(url)
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
                                    },
                                    currentTrack = currentTrack
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setupLibrespot(service: LibrespotService) {
        lifecycleScope.launch(Dispatchers.IO) {
            val cfg = JSONObject().apply {
                put("device_id", "a80e66172f552359922a3b472a2a30d8a4ef89ee")
                put("device_name", "Android Compose Demo")
                put("device_type", "smartphone")
                put("audio_backend", "oto")
                put("volume_steps", 16)
                put("initial_volume", 8)
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

            restoreCredentials()?.let { (u, d) ->
                cfg.getJSONObject("credentials")
                    .getJSONObject("spotify_token")
                    .apply {
                        put("username", u)
                        put("data", d)
                    }
            }

            val instance = service.getLibrespotInstance()
            instance.setCredentialCallback(object : mobilebind.CredentialCallback {
                override fun onCredentialsPersist(credentialsJson: String) {
                    val j = JSONObject(credentialsJson)
                    val u = j.optString("username")
                    val d = j.optString("data")
                    val outFile = File(filesDir, "creds.json")
                    outFile.writeText(JSONObject().apply {
                        put("username", u)
                        put("data", d)
                    }.toString())
                }
            })

            // Start librespot if not already started
            instance.startWithConfigJSON(cfg.toString(), null)

            withContext(Dispatchers.Main) {
                librespot = instance
            }
        }
    }

    private fun restoreCredentials(): Pair<String, String>? {
        val file = File(filesDir, credentialsFileName)
        if (!file.exists()) return null
        val txt = file.readText()
        val j = JSONObject(txt)
        val u = j.optString("username")
        val d = j.optString("data")
        return if (u.isNotEmpty() && d.isNotEmpty()) Pair(u, d) else null
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
fun LibrespotUI(
    librespotService: LibrespotService?,
    onAuthenticate: () -> Unit,
    currentTrack: String
) {
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Now Playing: $currentTrack", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch(Dispatchers.IO) { librespotService?.handlePlay() } }) { Text("Play") }
            Button(onClick = { scope.launch(Dispatchers.IO) { librespotService?.handlePause() } }) { Text("Pause") }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch(Dispatchers.IO) { librespotService?.handleNext() } }) { Text("Next") }
            Button(onClick = { scope.launch(Dispatchers.IO) { librespotService?.handlePrevious() } }) { Text("Prev") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onAuthenticate) { Text("Authenticate") }
    }
}
