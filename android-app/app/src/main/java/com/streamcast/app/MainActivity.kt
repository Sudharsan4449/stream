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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.HttpURLConnection
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {

    private val PREFS_NAME = "StreamCastPrefs"
    private val KEY_SAVED_URI = "saved_uri"
    private var nsdHelper: NsdHelper? = null

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

            LaunchedEffect(Unit) {
                while (true) {
                    isRunning = StreamingService.isServiceRunning
                    activeUri = StreamingService.activeTreeUri
                    activePort = StreamingService.activePort
                    localIps = getLocalIps()
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

            var lastMainBackPressTime by remember { mutableStateOf(0L) }
            BackHandler {
                if (currentMode == "Client") {
                    if (pathStack.size > 1) {
                        pathStack.removeAt(pathStack.size - 1)
                        return@BackHandler
                    } else if (selectedServerUrl != null) {
                        selectedServerUrl = null
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
                                            val url = URL("https://api.github.com/repos/Sudharsan4449/stream/releases/latest")
                                            val conn = url.openConnection() as HttpURLConnection
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
                            selectedTabIndex = if (currentMode == "Server") 0 else 1,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            containerColor = CardColor,
                            contentColor = Color.White
                        ) {
                            Tab(
                                selected = currentMode == "Server",
                                onClick = { currentMode = "Server" },
                                text = { Text("Server Mode (Phone)") }
                            )
                            Tab(
                                selected = currentMode == "Client",
                                onClick = { currentMode = "Client" },
                                text = { Text("Client Mode (TV)") }
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

                        } else {
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
                                    startDownload(urlStr)
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
        }
    }

    private fun startDownload(url: String) {
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "streamcast_update.apk")
        if (file.exists()) {
            file.delete()
        }

        val request = DownloadManager.Request(Uri.parse(url))
        request.setTitle("StreamCast Update")
        request.setDescription("Downloading new version...")
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "streamcast_update.apk")
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = manager.enqueue(request)
        
        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (downloadId == id) {
                    installApk()
                    context.unregisterReceiver(this)
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
        
        Toast.makeText(this, "Downloading update...", Toast.LENGTH_SHORT).show()
    }

    private fun installApk() {
        try {
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "streamcast_update.apk")
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
