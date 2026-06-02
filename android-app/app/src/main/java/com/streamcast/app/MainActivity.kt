package com.streamcast.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL
import java.net.HttpURLConnection
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class MainActivity : ComponentActivity() {

    private val PREFS_NAME = "StreamCastPrefs"
    private val KEY_SAVED_URI = "saved_uri"
    private var nsdHelper: NsdHelper? = null
    private var downloadProgressState by mutableStateOf(-1f)

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Determine if running on Android TV/Smart TV to default UI mode
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
        val isTv = uiModeManager.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val clipboardManager = LocalClipboardManager.current

            // State to toggle between Server Mode (Phone) and Client Mode (TV)
            var currentMode by remember { mutableStateOf(if (isTv) "Client" else "Server") }

            // Notification permission (Android 13+)
            var hasNotificationPermission by remember {
                mutableStateOf(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
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
                if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // Server-side State monitoring
            var isRunning by remember { mutableStateOf(StreamingService.isServiceRunning) }
            var activeUri by remember { mutableStateOf(StreamingService.activeTreeUri) }
            var activePort by remember { mutableStateOf(StreamingService.activePort) }
            var localIps by remember { mutableStateOf(getLocalIps()) }
            var savedUri by remember { mutableStateOf<Uri?>(loadSavedFolderUri()) }
            var isBatteryOptimized by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                while (true) {
                    isRunning = StreamingService.isServiceRunning
                    activeUri = StreamingService.activeTreeUri
                    activePort = StreamingService.activePort
                    localIps = getLocalIps()
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                        isBatteryOptimized = !pm.isIgnoringBatteryOptimizations(context.packageName)
                    }
                    delay(1000)
                }
            }

            val selectFolderLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                uri?.let {
                    try {
                        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        contentResolver.takePersistableUriPermission(it, takeFlags)
                        saveSavedFolderUri(it)
                        savedUri = it
                    } catch (e: Exception) {
                        Toast.makeText(context, "Permission error. Choose another directory.", Toast.LENGTH_LONG).show()
                    }
                }
            }

            // Server file indexing (Phone side preview list)
            var fileList by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
            LaunchedEffect(isRunning, activeUri, savedUri) {
                val targetUri = activeUri ?: savedUri
                if (targetUri != null) {
                    try {
                        val rootDoc = DocumentFile.fromTreeUri(context, targetUri)
                        val files = rootDoc?.listFiles()?.map {
                            Pair(it.name ?: "Unknown", if (it.isDirectory) 0L else it.length())
                        } ?: emptyList()
                        fileList = files.sortedWith(compareBy<Pair<String, Long>> { it.second != 0L }.thenBy { it.first })
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    fileList = emptyList()
                }
            }

            // Client-side Discovery State (TV side)
            val discoveredServers = remember { mutableStateListOf<Pair<String, Int>>() }
            val recentServers = remember { mutableStateListOf<String>() }
            var manualIpText by remember { mutableStateOf("") }

            LaunchedEffect(Unit) {
                recentServers.addAll(getRecentServers(context))
            }
            var selectedServerUrl by remember { mutableStateOf<String?>(null) }
            val pathStack = remember { mutableStateListOf<String>("/") }
            var serverFiles by remember { mutableStateOf<List<WebDavClient.ServerNode>>(emptyList()) }
            var isBrowserLoading by remember { mutableStateOf(false) }

            var showUpdateDialog by remember { mutableStateOf(false) }
            var updateUrl by remember { mutableStateOf<String?>(null) }
            
            // YouTube Browser State
            var youtubeSearchQuery by remember { mutableStateOf("") }
            var youtubeSearchResults by remember { mutableStateOf<List<JSONObject>?>(null) }
            var isSearchingYoutube by remember { mutableStateOf(false) }

            var lastMainBackPressTime by remember { mutableStateOf(0L) }
            BackHandler {
                if (currentMode == "Client") {
                    if (pathStack.size > 1) {
                        pathStack.removeAt(pathStack.size - 1)
                        return@BackHandler
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastMainBackPressTime < 2000) {
                    finish()
                } else {
                    lastMainBackPressTime = now
                    Toast.makeText(context, "Press Back again to exit", Toast.LENGTH_SHORT).show()
                }
            }

            // Handle network scanning discovery lifecycle
            LaunchedEffect(currentMode) {
                if (currentMode == "Client") {
                    discoveredServers.clear()
                    nsdHelper = NsdHelper(context).apply {
                        discoverServices { ip, port ->
                            val entry = Pair(ip, port)
                            if (!discoveredServers.contains(entry)) {
                                discoveredServers.add(entry)
                            }
                        }
                    }
                } else {
                    nsdHelper?.stopDiscovery()
                    nsdHelper = null
                }
            }

            // Fetch WebDAV files on directory change
            LaunchedEffect(selectedServerUrl, pathStack.size) {
                val server = selectedServerUrl
                if (server != null) {
                    isBrowserLoading = true
                    val currentPath = pathStack.lastOrNull() ?: "/"
                    serverFiles = WebDavClient.listFolder(server, currentPath)
                    isBrowserLoading = false
                }
            }

            val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            
            // Server Polling Health Check
            LaunchedEffect(selectedServerUrl) {
                val server = selectedServerUrl
                if (server != null) {
                    var failCount = 0
                    while (isActive) {
                        kotlinx.coroutines.delay(10000L)
                        val isAlive = try {
                            val conn = java.net.URL(server).openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "OPTIONS"
                            conn.connectTimeout = 3000
                            conn.readTimeout = 3000
                            conn.connect()
                            conn.responseCode in 200..499
                        } catch (e: Exception) {
                            false
                        }
                        
                        if (!isAlive) {
                            failCount++
                            if (failCount >= 2) {
                                // Only auto-disconnect if MainActivity is actively visible
                                if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                                    selectedServerUrl = null
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Server disconnected", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        } else {
                            failCount = 0
                        }
                    }
                }
            }

            // Theme Aesthetics Setup
            val BackgroundColor = Color(0xFF0F172A)
            val CardColor = Color(0xFF1E293B)
            val AccentColor = Color(0xFF10B981)
            val SecondaryAccent = Color(0xFF0EA5E9)
            val ErrorColor = Color(0xFFEF4444)

            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = BackgroundColor,
                    surface = CardColor,
                    primary = AccentColor,
                    secondary = SecondaryAccent,
                    error = ErrorColor
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        // Header Row with App Title and Update Button
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("StreamCast", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            
                            var isCheckingUpdate by remember { mutableStateOf(false) }
                            Button(
                                onClick = {
                                    isCheckingUpdate = true
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                                                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                                                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                                                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                                            })
                                            val sslContext = SSLContext.getInstance("SSL")
                                            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

                                            val url = URL("https://api.github.com/repos/Sudharsan4449/stream/releases/latest")
                                            val conn = url.openConnection() as HttpsURLConnection
                                            conn.sslSocketFactory = sslContext.socketFactory
                                            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                                            conn.requestMethod = "GET"
                                            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                                            if (conn.responseCode == 200) {
                                                val response = conn.inputStream.bufferedReader().use { it.readText() }
                                                val json = JSONObject(response)
                                                val tagName = json.optString("tag_name", "")
                                                val apkUrl = json.optJSONArray("assets")?.let { assets ->
                                                    if (assets.length() > 0) {
                                                        assets.getJSONObject(0).optString("browser_download_url")
                                                    } else null
                                                } ?: json.optString("html_url")
                                                
                                                val latestVersion = tagName.removePrefix("v")
                                                val currentVersion = "1.0"
                                                
                                                withContext(Dispatchers.Main) {
                                                    isCheckingUpdate = false
                                                    if (latestVersion.isNotEmpty() && latestVersion != currentVersion) {
                                                        updateUrl = apkUrl
                                                        showUpdateDialog = true
                                                    } else {
                                                        Toast.makeText(context, "App is up to date!", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            } else if (conn.responseCode == 404) {
                                                withContext(Dispatchers.Main) {
                                                    isCheckingUpdate = false
                                                    Toast.makeText(context, "App is up to date! (No releases yet)", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    isCheckingUpdate = false
                                                    Toast.makeText(context, "Failed to check for updates (Code: ${conn.responseCode})", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            withContext(Dispatchers.Main) {
                                                isCheckingUpdate = false
                                                Toast.makeText(context, "Network error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CardColor),
                                modifier = Modifier.focusable()
                            ) {
                                if (isCheckingUpdate) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = "Check for Updates", tint = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Check Update", color = Color.White)
                                }
                            }
                        }

                        // Global Mode Toggle Tabs
                        TabRow(
                            selectedTabIndex = when(currentMode) {
                                "Server" -> 0
                                "Client" -> 1
                                else -> 2
                            },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            containerColor = CardColor,
                            contentColor = Color.White
                        ) {
                            Tab(
                                selected = currentMode == "Server",
                                onClick = { currentMode = "Server" },
                                text = { Text("Server Mode") }
                            )
                            Tab(
                                selected = currentMode == "Client",
                                onClick = { currentMode = "Client" },
                                text = { Text("Client Mode") }
                            )
                            Tab(
                                selected = currentMode == "Files",
                                onClick = { currentMode = "Files" },
                                text = { Text("File Manager") }
                            )
                        }

                        // RENDER SCREEN MODES
                        if (currentMode == "Server") {
                            // --- SERVER INTERFACE (PHONE DASHBOARD) ---
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "StreamCast WebDAV Server",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                IconButton(onClick = { localIps = getLocalIps() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Networks", tint = Color.LightGray)
                                }
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = "Status: ${if (isRunning) "ACTIVE" else "STOPPED"}",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isRunning) AccentColor else ErrorColor
                                        )
                                        Text(
                                            text = "Active Folder: ${getUriDisplayName(savedUri ?: activeUri)}",
                                            fontSize = 13.sp,
                                            color = Color.LightGray,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.width(220.dp)
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            if (isRunning) {
                                                stopStreamingService()
                                            } else {
                                                val uriToStart = savedUri ?: activeUri
                                                if (uriToStart != null) {
                                                    startStreamingService(uriToStart)
                                                } else {
                                                    Toast.makeText(context, "Please select a movie folder first!", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isRunning) ErrorColor else AccentColor
                                        )
                                    ) {
                                        Text(text = if (isRunning) "Stop" else "Start", color = Color.White)
                                    }
                                }
                            }
                            
                            if (isRunning && isBatteryOptimized) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    colors = CardDefaults.cardColors(containerColor = ErrorColor.copy(alpha = 0.2f))
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Info, contentDescription = "Warning", tint = ErrorColor)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Battery Optimization Active", color = ErrorColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            Text("Android may kill the server when screen turns off.", color = Color.White, fontSize = 12.sp)
                                        }
                                        Button(
                                            onClick = {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                    try {
                                                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                            data = Uri.parse("package:${context.packageName}")
                                                        }
                                                        context.startActivity(intent)
                                                    } catch (e: Exception) {
                                                        Toast.makeText(context, "Could not open settings", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = ErrorColor)
                                        ) {
                                            Text("Fix", color = Color.White)
                                        }
                                    }
                                }
                            }

                            if (isRunning) {
                                val activeIp = localIps.firstOrNull { !it.contains("Hotspot") } ?: "192.168.43.1"
                                val serverUrl = "http://$activeIp:$activePort"

                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "TV Network Connection Instructions",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = SecondaryAccent,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))

                                        val qrBitmap = remember(serverUrl) {
                                            QrCodeGenerator.generateQrCode(serverUrl)
                                        }

                                        qrBitmap?.let {
                                            Image(
                                                bitmap = it.asImageBitmap(),
                                                contentDescription = "Connection QR Code",
                                                modifier = Modifier
                                                    .size(160.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color.White)
                                                    .padding(6.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(16.dp))

                                        Text(
                                            text = "Or scan this code inside the TV Client app to connect instantly.",
                                            fontSize = 12.sp,
                                            color = Color.LightGray,
                                            textAlign = TextAlign.Center
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                            text = serverUrl,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            textAlign = TextAlign.Center
                                        )

                                        Spacer(modifier = Modifier.height(12.dp))

                                        Button(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(serverUrl))
                                                Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = SecondaryAccent)
                                        ) {
                                            Text("Copy Server URL", color = Color.White)
                                        }
                                    }
                                }
                            }

                            if (!isRunning) {
                                Button(
                                    onClick = { selectFolderLauncher.launch(null) },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = SecondaryAccent)
                                ) {
                                    Text(
                                        text = if (savedUri != null) "Change Folder Directory" else "Select Movie Folder Tree",
                                        color = Color.White
                                    )
                                }
                            }

                            Text(
                                text = "Local Media Browser Index",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color.Gray,
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)
                            )

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(CardColor, RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                if (fileList.isEmpty()) {
                                    item {
                                        Text(
                                            text = "No files found. Choose a storage directory tree first.",
                                            color = Color.Gray,
                                            modifier = Modifier.padding(16.dp),
                                            fontSize = 13.sp
                                        )
                                    }
                                } else {
                                    items(fileList) { file ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                val isVideo = file.second > 0L
                                                Icon(
                                                    imageVector = if (isVideo) Icons.Default.PlayArrow else Icons.Default.Info,
                                                    contentDescription = "File Type",
                                                    tint = if (isVideo) AccentColor else SecondaryAccent,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = file.first,
                                                    color = Color.White,
                                                    fontSize = 14.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text(
                                                text = if (file.second > 0L) formatSize(file.second) else "Directory",
                                                color = Color.LightGray,
                                                fontSize = 12.sp
                                            )
                                        }
                                        Divider(color = Color(0xFF334155), thickness = 0.5.dp)
                                    }
                                }
                            }

                        } else if (currentMode == "Client") {
                            // --- CLIENT INTERFACE (TV / PLAYER COMPANION MODE) ---
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (selectedServerUrl == null) "Select Media Server" else "Browsing: ${pathStack.lastOrNull() ?: "/"}",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                if (selectedServerUrl != null) {
                                    Row {
                                        if (pathStack.size > 1) {
                                            Button(
                                                onClick = { pathStack.removeAt(pathStack.size - 1) },
                                                colors = ButtonDefaults.buttonColors(containerColor = SecondaryAccent),
                                                modifier = Modifier.padding(end = 8.dp)
                                            ) {
                                                Text("Back Folder", color = Color.White)
                                            }
                                        }
                                        Button(
                                            onClick = {
                                                selectedServerUrl = null
                                                pathStack.clear()
                                                pathStack.add("/")
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = ErrorColor)
                                        ) {
                                            Text("Disconnect", color = Color.White)
                                        }
                                    }
                                }
                            }

                            if (selectedServerUrl == null) {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .background(CardColor, RoundedCornerShape(8.dp))
                                        .padding(16.dp)
                                ) {
                                // 0. YouTube Browser Section
                                    item {
                                        Text(
                                            text = "YouTube Browser",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = Color(0xFFFF0000), // YouTube Red
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = youtubeSearchQuery,
                                                onValueChange = { youtubeSearchQuery = it },
                                                label = { Text("Search YouTube") },
                                                placeholder = { Text("Movie trailers, music, etc...") },
                                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                                singleLine = true
                                            )
                                            Button(
                                                onClick = {
                                                    if (youtubeSearchQuery.isNotBlank()) {
                                                        isSearchingYoutube = true
                                                        scope.launch(Dispatchers.IO) {
                                                            try {
                                                                val encodedQuery = java.net.URLEncoder.encode(youtubeSearchQuery.trim(), "UTF-8")
                                                                // Use an Invidious instance (puffyan) to get search results without API key
                                                                val url = URL("https://vid.puffyan.us/api/v1/search?q=\$encodedQuery")
                                                                val conn = url.openConnection() as HttpURLConnection
                                                                conn.requestMethod = "GET"
                                                                if (conn.responseCode == 200) {
                                                                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                                                                    val jsonArray = JSONArray(response)
                                                                    val results = mutableListOf<JSONObject>()
                                                                    for (i in 0 until jsonArray.length()) {
                                                                        val obj = jsonArray.getJSONObject(i)
                                                                        if (obj.optString("type") == "video") {
                                                                            results.add(obj)
                                                                        }
                                                                    }
                                                                    withContext(Dispatchers.Main) {
                                                                        youtubeSearchResults = results
                                                                        isSearchingYoutube = false
                                                                    }
                                                                } else {
                                                                    withContext(Dispatchers.Main) { isSearchingYoutube = false }
                                                                }
                                                            } catch (e: Exception) {
                                                                e.printStackTrace()
                                                                withContext(Dispatchers.Main) { 
                                                                    isSearchingYoutube = false 
                                                                    Toast.makeText(context, "Search failed: \${e.message}", Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                        }
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000))
                                            ) {
                                                if (isSearchingYoutube) {
                                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                                } else {
                                                    Text("Search", color = Color.White)
                                                }
                                            }
                                        }
                                        
                                        // YouTube Search Results
                                        youtubeSearchResults?.let { results ->
                                            if (results.isEmpty()) {
                                                Text("No results found.", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 16.dp))
                                            } else {
                                                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                                                    results.take(15).forEach { video ->
                                                        Card(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(vertical = 4.dp)
                                                                .clickable {
                                                                    val videoId = video.optString("videoId")
                                                                    val title = video.optString("title")
                                                                    Toast.makeText(context, "Extracting Stream...", Toast.LENGTH_SHORT).show()
                                                                    scope.launch(Dispatchers.IO) {
                                                                        try {
                                                                            val url = URL("https://vid.puffyan.us/api/v1/videos/\$videoId")
                                                                            val conn = url.openConnection() as HttpURLConnection
                                                                            if (conn.responseCode == 200) {
                                                                                val response = conn.inputStream.bufferedReader().use { it.readText() }
                                                                                val json = JSONObject(response)
                                                                                val formatStreams = json.optJSONArray("formatStreams")
                                                                                if (formatStreams != null && formatStreams.length() > 0) {
                                                                                    var streamUrl = formatStreams.getJSONObject(0).optString("url")
                                                                                    // Find best quality
                                                                                    for (i in 0 until formatStreams.length()) {
                                                                                        val format = formatStreams.getJSONObject(i)
                                                                                        if (format.optString("resolution").contains("1080") || format.optString("resolution").contains("720")) {
                                                                                            streamUrl = format.optString("url")
                                                                                            break
                                                                                        }
                                                                                    }
                                                                                    withContext(Dispatchers.Main) {
                                                                                        val intent = Intent(context, PlayerActivity::class.java).apply {
                                                                                            putExtra(PlayerActivity.EXTRA_VIDEO_URL, streamUrl)
                                                                                            putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, title)
                                                                                        }
                                                                                        context.startActivity(intent)
                                                                                    }
                                                                                } else {
                                                                                    withContext(Dispatchers.Main) {
                                                                                        Toast.makeText(context, "No stream found", Toast.LENGTH_SHORT).show()
                                                                                    }
                                                                                }
                                                                            } else {
                                                                                withContext(Dispatchers.Main) { Toast.makeText(context, "Failed to load video", Toast.LENGTH_SHORT).show() }
                                                                            }
                                                                        } catch (e: Exception) {
                                                                            e.printStackTrace()
                                                                            withContext(Dispatchers.Main) { Toast.makeText(context, "Playback error", Toast.LENGTH_SHORT).show() }
                                                                        }
                                                                    }
                                                                },
                                                            colors = CardDefaults.cardColors(containerColor = SecondaryAccent.copy(alpha = 0.5f))
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(32.dp))
                                                                Spacer(modifier = Modifier.width(12.dp))
                                                                Column {
                                                                    Text(text = video.optString("title"), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                                    Text(text = video.optString("author"), color = Color.LightGray, fontSize = 12.sp)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        Divider(color = Color(0xFF334155), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))
                                    }

                                    // 1. Manual Connection Input
                                    item {
                                        Text(
                                            text = "Connect Manually",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = SecondaryAccent,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = manualIpText,
                                                onValueChange = { manualIpText = it },
                                                label = { Text("IP Address or URL") },
                                                placeholder = { Text("192.168.1.5") },
                                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                                singleLine = true
                                            )
                                            Button(
                                                onClick = {
                                                    if (manualIpText.isNotBlank()) {
                                                        var targetUrl = manualIpText.trim()
                                                        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                                                            targetUrl = "http://$targetUrl"
                                                        }
                                                        if (targetUrl.indexOf(':', 7) == -1) {
                                                            targetUrl = "$targetUrl:8080"
                                                        }
                                                        addRecentServer(context, targetUrl)
                                                        if (!recentServers.contains(targetUrl)) {
                                                            recentServers.add(targetUrl)
                                                        }
                                                        selectedServerUrl = targetUrl
                                                    } else {
                                                        Toast.makeText(context, "Enter a valid IP or URL", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = AccentColor)
                                            ) {
                                                Text("Connect", color = Color.White)
                                            }
                                        }
                                        Divider(color = Color(0xFF334155), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))
                                    }

                                    // 2. Discovered Servers Section
                                    item {
                                        Text(
                                            text = "Discovered Servers (Scanning...)",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = AccentColor,
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        )
                                    }

                                    if (discoveredServers.isEmpty()) {
                                        item {
                                            Text(
                                                text = "Searching local network...",
                                                color = Color.Gray,
                                                fontSize = 13.sp,
                                                modifier = Modifier.padding(bottom = 16.dp, start = 8.dp)
                                            )
                                        }
                                    } else {
                                        items(discoveredServers) { server ->
                                            val ip = if (server.first.contains(":")) "[${server.first}]" else server.first
                                            val url = "http://$ip:${server.second}"
                                            var isFocused by remember { mutableStateOf(false) }
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp)
                                                    .onFocusChanged { isFocused = it.isFocused }
                                                    .focusable()
                                                    .clickable {
                                                        addRecentServer(context, url)
                                                        if (!recentServers.contains(url)) {
                                                            recentServers.add(url)
                                                        }
                                                        selectedServerUrl = url
                                                    },
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (isFocused) AccentColor else Color(0xFF1E293B)
                                                )
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(16.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                 ) {
                                                     Column {
                                                         Text(
                                                             text = "StreamCast Phone Server",
                                                             fontWeight = FontWeight.Bold,
                                                             color = if (isFocused) Color.Black else Color.White
                                                         )
                                                         Text(
                                                             text = url,
                                                             color = if (isFocused) Color(0xFF1E293B) else Color.LightGray,
                                                             fontSize = 13.sp
                                                         )
                                                     }
                                                     Icon(
                                                         imageVector = Icons.Default.PlayArrow,
                                                         contentDescription = "Connect",
                                                         tint = if (isFocused) Color.Black else AccentColor
                                                     )
                                                 }
                                             }
                                         }
                                     }

                                     // 3. Recent Servers Section
                                     item {
                                         Divider(color = Color(0xFF334155), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))
                                         Text(
                                             text = "Recent Connections",
                                             fontWeight = FontWeight.Bold,
                                             fontSize = 15.sp,
                                             color = SecondaryAccent,
                                             modifier = Modifier.padding(bottom = 8.dp)
                                         )
                                     }

                                     if (recentServers.isEmpty()) {
                                         item {
                                             Text(
                                                 text = "No recent connections saved.",
                                                 color = Color.Gray,
                                                 fontSize = 13.sp,
                                                 modifier = Modifier.padding(start = 8.dp)
                                             )
                                         }
                                     } else {
                                         items(recentServers) { url ->
                                             var isFocused by remember { mutableStateOf(false) }
                                             Card(
                                                 modifier = Modifier
                                                     .fillMaxWidth()
                                                     .padding(vertical = 4.dp)
                                                     .onFocusChanged { isFocused = it.isFocused }
                                                     .focusable()
                                                     .clickable {
                                                         selectedServerUrl = url
                                                     },
                                                 colors = CardDefaults.cardColors(
                                                     containerColor = if (isFocused) SecondaryAccent else Color(0xFF1E293B)
                                                 )
                                             ) {
                                                 Row(
                                                     modifier = Modifier.padding(16.dp),
                                                     verticalAlignment = Alignment.CenterVertically,
                                                     horizontalArrangement = Arrangement.SpaceBetween
                                                 ) {
                                                     Column(modifier = Modifier.weight(1f)) {
                                                         Text(
                                                             text = "Saved Server Connection",
                                                             fontWeight = FontWeight.Bold,
                                                             color = if (isFocused) Color.Black else Color.White
                                                         )
                                                         Text(
                                                             text = url,
                                                             color = if (isFocused) Color(0xFF1E293B) else Color.LightGray,
                                                             fontSize = 13.sp
                                                         )
                                                     }
                                                     IconButton(
                                                         onClick = {
                                                             removeRecentServer(context, url)
                                                             recentServers.remove(url)
                                                         }
                                                     ) {
                                                         Icon(
                                                             imageVector = Icons.Default.Delete,
                                                             contentDescription = "Delete",
                                                             tint = if (isFocused) Color.Black else ErrorColor
                                                         )
                                                     }
                                                 }
                                             }
                                         }
                                     }
                                 }
                            } else {
                                // 2. WebDAV Files and Directories Browser
                                if (isBrowserLoading) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().weight(1f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(color = SecondaryAccent)
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .background(CardColor, RoundedCornerShape(8.dp))
                                            .padding(8.dp)
                                    ) {
                                        if (serverFiles.isEmpty()) {
                                            item {
                                                Text(
                                                    text = "Folder is empty.",
                                                    color = Color.Gray,
                                                    modifier = Modifier.padding(16.dp)
                                                )
                                            }
                                        } else {
                                            items(serverFiles) { node ->
                                                var isFocused by remember { mutableStateOf(false) }
                                                Card(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 4.dp, horizontal = 8.dp)
                                                        .onFocusChanged { isFocused = it.isFocused }
                                                        .focusable()
                                                        .clickable {
                                                            if (node.isDirectory) {
                                                                pathStack.add(node.path)
                                                            } else {
                                                                // Launch PlayerActivity with subtitles resolution
                                                                val videoUrl = selectedServerUrl!!.trimEnd('/') + node.path
                                                                
                                                                // Automatic sidecar subtitle search
                                                                val videoBaseName = node.name.substringBeforeLast('.')
                                                                val subtitleNode = serverFiles.find { sub ->
                                                                    !sub.isDirectory && 
                                                                    sub.name.substringBeforeLast('.').equals(videoBaseName, ignoreCase = true) &&
                                                                    (sub.name.endsWith(".srt", ignoreCase = true) || sub.name.endsWith(".vtt", ignoreCase = true))
                                                                }
                                                                
                                                                val subtitleUrl = subtitleNode?.let { sub ->
                                                                    selectedServerUrl!!.trimEnd('/') + sub.path
                                                                }

                                                                val playIntent = Intent(context, PlayerActivity::class.java).apply {
                                                                    putExtra(PlayerActivity.EXTRA_VIDEO_URL, videoUrl)
                                                                    putExtra(PlayerActivity.EXTRA_SUBTITLE_URL, subtitleUrl)
                                                                }
                                                                context.startActivity(playIntent)
                                                            }
                                                        },
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = if (isFocused) SecondaryAccent else Color.Transparent
                                                    )
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(16.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.weight(1f),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Icon(
                                                                imageVector = if (node.isDirectory) Icons.Default.Info else Icons.Default.PlayArrow,
                                                                contentDescription = "Item Type",
                                                                tint = if (isFocused) Color.Black else (if (node.isDirectory) SecondaryAccent else AccentColor),
                                                                modifier = Modifier.size(24.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(12.dp))
                                                            Text(
                                                                text = node.name,
                                                                color = if (isFocused) Color.Black else Color.White,
                                                                fontSize = 15.sp,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                        Text(
                                                            text = if (node.isDirectory) "Directory" else formatSize(node.size),
                                                            color = if (isFocused) Color(0xFF1E293B) else Color.LightGray,
                                                            fontSize = 12.sp
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        } else if (currentMode == "Files") {
                            // --- FILE MANAGER INTERFACE ---
                            var downloadedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
                            LaunchedEffect(currentMode) {
                                withContext(Dispatchers.IO) {
                                    val dirs = ContextCompat.getExternalFilesDirs(context, Environment.DIRECTORY_MOVIES)
                                    val allFiles = mutableListOf<File>()
                                    dirs.forEach { dir ->
                                        if (dir != null && dir.exists()) {
                                            allFiles.addAll(dir.listFiles()?.toList() ?: emptyList())
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        downloadedFiles = allFiles.filter { it.isFile && (it.name.endsWith(".mp4") || it.name.endsWith(".mkv") || it.name.endsWith(".avi")) }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Downloaded Videos",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                IconButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val dirs = ContextCompat.getExternalFilesDirs(context, Environment.DIRECTORY_MOVIES)
                                        val allFiles = mutableListOf<File>()
                                        dirs.forEach { dir ->
                                            if (dir != null && dir.exists()) {
                                                allFiles.addAll(dir.listFiles()?.toList() ?: emptyList())
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            downloadedFiles = allFiles.filter { it.isFile && (it.name.endsWith(".mp4") || it.name.endsWith(".mkv") || it.name.endsWith(".avi")) }
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Files", tint = Color.LightGray)
                                }
                            }

                            if (downloadedFiles.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("No downloaded videos found.", color = Color.Gray)
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .background(CardColor, RoundedCornerShape(8.dp))
                                        .padding(8.dp)
                                ) {
                                    itemsIndexed(downloadedFiles) { index, file ->
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp),
                                            colors = CardDefaults.cardColors(containerColor = SecondaryAccent.copy(alpha = 0.5f))
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(text = file.name, color = Color.White, fontWeight = FontWeight.Bold)
                                                    Text(text = formatSize(file.length()), color = Color.LightGray, fontSize = 12.sp)
                                                }
                                                Row {
                                                    Button(
                                                        onClick = {
                                                            val uri = FileProvider.getUriForFile(context, "\${context.packageName}.provider", file)
                                                            val intent = Intent(context, PlayerActivity::class.java).apply {
                                                                putExtra(PlayerActivity.EXTRA_VIDEO_URL, uri.toString())
                                                                putExtra(PlayerActivity.EXTRA_VIDEO_TITLE, file.name)
                                                            }
                                                            context.startActivity(intent)
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                                                        modifier = Modifier.padding(end = 8.dp)
                                                    ) {
                                                        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White)
                                                        Spacer(Modifier.width(4.dp))
                                                        Text("Play", color = Color.White)
                                                    }
                                                    Button(
                                                        onClick = {
                                                            if (file.delete()) {
                                                                Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                                                                downloadedFiles = downloadedFiles.filter { it.absolutePath != file.absolutePath }
                                                            } else {
                                                                Toast.makeText(context, "Failed to delete", Toast.LENGTH_SHORT).show()
                                                            }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = ErrorColor)
                                                    ) {
                                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showUpdateDialog) {
                AlertDialog(
                    onDismissRequest = { showUpdateDialog = false },
                    title = { Text("Update Available") },
                    text = { Text("A new version of StreamCast is available. Would you like to download it now?") },
                    confirmButton = {
                        Button(
                            onClick = {
                                showUpdateDialog = false
                                updateUrl?.let { urlStr ->
                                    startDownload(urlStr, scope)
                                }
                            }
                        ) {
                            Text("Download")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showUpdateDialog = false }) {
                            Text("Later")
                        }
                    }
                )
            }
            
            if (downloadProgressState >= 0f) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text("Downloading Update") },
                    text = {
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Please wait...")
                            Spacer(Modifier.height(16.dp))
                            LinearProgressIndicator(
                                progress = downloadProgressState,
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = AccentColor
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("${(downloadProgressState * 100).toInt()}%", color = Color.Gray)
                        }
                    },
                    confirmButton = {}
                )
            }
        }
    }

    private fun startDownload(url: String, scope: kotlinx.coroutines.CoroutineScope) {
        val file = File(cacheDir, "streamcast_update.apk")
        if (file.exists()) {
            file.delete()
        }

        downloadProgressState = 0f

        scope.launch(Dispatchers.IO) {
            try {
                var currentUrl = url
                var redirectCount = 0
                var connection: HttpURLConnection? = null
                var fileLength = 0
                var input: java.io.InputStream? = null
                
                while (redirectCount < 10) {
                    connection = URL(currentUrl).openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "StreamCast-Updater")
                    connection.instanceFollowRedirects = false
                    connection.connect()

                    val responseCode = connection.responseCode
                    if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                        responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                        responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                        responseCode == 307 || responseCode == 308) {
                        
                        val newUrl = connection.getHeaderField("Location")
                        if (newUrl == null) break
                        currentUrl = newUrl
                        redirectCount++
                        continue
                    }
                    
                    if (responseCode in 200..299) {
                        fileLength = connection.contentLength
                        input = connection.inputStream
                        break
                    } else {
                        throw Exception("Server returned HTTP $responseCode")
                    }
                }
                
                if (input == null) throw Exception("Too many redirects or null stream")
                
                val output = java.io.FileOutputStream(file)
                val data = ByteArray(8192)
                var total: Long = 0
                var count: Int
                
                while (input.read(data).also { count = it } != -1) {
                    total += count.toLong()
                    if (fileLength > 0) {
                        downloadProgressState = (total.toFloat() / fileLength.toFloat()).coerceIn(0f, 1f)
                    }
                    output.write(data, 0, count)
                }

                output.flush()
                output.close()
                input.close()

                withContext(Dispatchers.Main) {
                    downloadProgressState = -1f
                    installApk(file)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    downloadProgressState = -1f
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun installApk(file: File) {
        try {
            if (file.exists()) {
                val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setDataAndType(uri, "application/vnd.android.package-archive")
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                startActivity(intent)
            } else {
                Toast.makeText(this, "Update file not found", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to launch installer", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getUriDisplayName(uri: Uri?): String {
        if (uri == null) return "None selected"
        return uri.path?.substringAfterLast(':') ?: uri.lastPathSegment ?: uri.toString()
    }

    private fun startStreamingService(uri: Uri) {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_TREE_URI, uri.toString())
            putExtra(StreamingService.EXTRA_PORT, 8080)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopStreamingService() {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        startService(intent)
    }

    private fun saveSavedFolderUri(uri: Uri) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SAVED_URI, uri.toString()).apply()
    }

    private fun loadSavedFolderUri(): Uri? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(KEY_SAVED_URI, null)
        return uriString?.let { Uri.parse(it) }
    }

    private fun getLocalIps(): List<String> {
        val ipList = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        val ip = addr.hostAddress ?: continue
                        ipList.add(ip)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (!ipList.contains("192.168.43.1")) {
            ipList.add("192.168.43.1 (Hotspot AP)")
        }
        return ipList.distinct()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun getRecentServers(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet("recent_servers", emptySet()) ?: emptySet()
        return set.toList().sorted()
    }

    private fun addRecentServer(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet("recent_servers", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (set.add(url)) {
            prefs.edit().putStringSet("recent_servers", set).apply()
        }
    }

    private fun removeRecentServer(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet("recent_servers", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (set.remove(url)) {
            prefs.edit().putStringSet("recent_servers", set).apply()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        nsdHelper?.stopDiscovery()
    }
}
