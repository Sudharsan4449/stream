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
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private val PREFS_NAME = "StreamCastPrefs"
    private val KEY_SAVED_URI = "saved_uri"
    private var nsdHelper: NsdHelper? = null

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
            var selectedServerUrl by remember { mutableStateOf<String?>(null) }
            val pathStack = remember { mutableStateListOf<String>("/") }
            var serverFiles by remember { mutableStateOf<List<WebDavClient.ServerNode>>(emptyList()) }
            var isBrowserLoading by remember { mutableStateOf(false) }

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
                        // Global Mode Toggle Tabs (Hidden on TVs to enforce standard client screen layout)
                        if (!isTv) {
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
                                // 1. Scan & Discover Servers View
                                Text(
                                    text = "Scanning network for phone servers...",
                                    fontSize = 13.sp,
                                    color = Color.LightGray,
                                    modifier = Modifier.padding(bottom = 16.dp)
                                )

                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .background(CardColor, RoundedCornerShape(8.dp))
                                        .padding(8.dp)
                                ) {
                                    if (discoveredServers.isEmpty()) {
                                        item {
                                            Box(
                                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    CircularProgressIndicator(color = AccentColor)
                                                    Spacer(modifier = Modifier.height(16.dp))
                                                    Text(
                                                        text = "No server found yet.\nMake sure the phone server is running and on the same Wi-Fi / Hotspot.",
                                                        color = Color.Gray,
                                                        textAlign = TextAlign.Center,
                                                        fontSize = 14.sp
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        items(discoveredServers) { server ->
                                            val url = "http://${server.first}:${server.second}"
                                            Card(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(8.dp)
                                                    .clickable { selectedServerUrl = url },
                                                colors = CardDefaults.cardColors(containerColor = Color(0xFF334155))
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
                                                            color = Color.White
                                                        )
                                                        Text(
                                                            text = url,
                                                            color = Color.LightGray,
                                                            fontSize = 13.sp
                                                        )
                                                    }
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = "Connect",
                                                        tint = AccentColor
                                                    )
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
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
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
                                                        }
                                                        .padding(16.dp),
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
                                                            tint = if (node.isDirectory) SecondaryAccent else AccentColor,
                                                            modifier = Modifier.size(24.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(12.dp))
                                                        Text(
                                                            text = node.name,
                                                            color = Color.White,
                                                            fontSize = 15.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Text(
                                                        text = if (node.isDirectory) "Directory" else formatSize(node.size),
                                                        color = Color.LightGray,
                                                        fontSize = 12.sp
                                                    )
                                                }
                                                Divider(color = Color(0xFF334155), thickness = 0.5.dp)
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

    override fun onDestroy() {
        super.onDestroy()
        nsdHelper?.stopDiscovery()
    }
}
