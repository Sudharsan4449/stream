package com.streamcast.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
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
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private val PREFS_NAME = "StreamCastPrefs"
    private val KEY_SAVED_URI = "saved_uri"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            val context = LocalContext.current
            val clipboardManager = LocalClipboardManager.current
            
            // Notification Permission (Android 13+)
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

            // Read service state
            var isRunning by remember { mutableStateOf(StreamingService.isServiceRunning) }
            var activeUri by remember { mutableStateOf(StreamingService.activeTreeUri) }
            var activePort by remember { mutableStateOf(StreamingService.activePort) }
            var localIps by remember { mutableStateOf(getLocalIps()) }
            
            // Poll service state and IP list
            LaunchedEffect(Unit) {
                while (true) {
                    isRunning = StreamingService.isServiceRunning
                    activeUri = StreamingService.activeTreeUri
                    activePort = StreamingService.activePort
                    localIps = getLocalIps()
                    delay(1000)
                }
            }

            // Handle Saved Uri loaded on startup
            var savedUri by remember { mutableStateOf<Uri?>(loadSavedFolderUri()) }

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

            // Index documents to display on the local media explorer list
            var fileList by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
            LaunchedEffect(isRunning, activeUri, savedUri) {
                val targetUri = activeUri ?: savedUri
                if (targetUri != null) {
                    try {
                        val rootDoc = DocumentFile.fromTreeUri(context, targetUri)
                        val files = rootDoc?.listFiles()?.map { 
                            Pair(it.name ?: "Unknown", if (it.isDirectory) 0L else it.length())
                        } ?: emptyList()
                        fileList = files.sortedWith(compareBy({ it.second > 0 }, { it.first }))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    fileList = emptyList()
                }
            }

            // Dark Mode Aesthetic Theme Custom Colors
            val BackgroundColor = Color(0xFF0F172A) // slate-900
            val CardColor = Color(0xFF1E293B) // slate-800
            val AccentColor = Color(0xFF10B981) // emerald-500
            val SecondaryAccent = Color(0xFF0EA5E9) // sky-500
            val ErrorColor = Color(0xFFEF4444) // red-500

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
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Title Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
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

                        // State Monitor Banner
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
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

                        // Server Dashboard and Connectivity Addresses
                        if (isRunning) {
                            val activeIp = localIps.firstOrNull { !it.contains("Hotspot") } ?: "192.168.43.1"
                            val serverUrl = "http://$activeIp:$activePort"
                            
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
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
                                    
                                    // Generate QR Connection Bitmap
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
                                        text = "Add WebDAV server in VLC TV Network Settings:",
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
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
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
                        }

                        // Set / Change Folder
                        if (!isRunning) {
                            Button(
                                onClick = { selectFolderLauncher.launch(null) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SecondaryAccent)
                            ) {
                                Text(
                                    text = if (savedUri != null) "Change Folder Directory" else "Select Movie Folder Tree",
                                    color = Color.White
                                )
                            }
                        }

                        // Local Media Directory Index Browser
                        Text(
                            text = "Local Media Browser Index",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color.Gray,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp, bottom = 8.dp)
                        )
                        
                        if (fileList.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(CardColor, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No files found. Select a valid storage directory tree.",
                                    color = Color.Gray,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(CardColor, RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                items(fileList) { file ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp, horizontal = 12.dp),
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
                                                contentDescription = if (isVideo) "Video" else "Folder",
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
                                        if (file.second > 0L) {
                                            Text(
                                                text = formatSize(file.second),
                                                color = Color.LightGray,
                                                fontSize = 12.sp
                                            )
                                        } else {
                                            Text(
                                                text = "Directory",
                                                color = SecondaryAccent,
                                                fontSize = 12.sp
                                            )
                                        }
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
        // Fallback or explicit additions for hotspot connections
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
}
