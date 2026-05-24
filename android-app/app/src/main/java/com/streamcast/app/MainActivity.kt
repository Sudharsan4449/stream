package com.streamcast.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.NetworkInterface
import io.ktor.utils.io.jvm.javaio.toByteReadChannel

class MainActivity : ComponentActivity() {

    private var server: NettyApplicationEngine? = null
    private var activeVideoUri: Uri? = null
    private var activeVideoName: String = "None"
    
    // State for UI
    private val serverUrlState = mutableStateOf("Server Stopped")
    private val videoNameState = mutableStateOf("No video selected")
    private val isServerRunning = mutableStateOf(false)

    private val selectVideoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                activeVideoUri = uri
                activeVideoName = getFileName(uri) ?: "video.mp4"
                videoNameState.value = activeVideoName
                
                startServer()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "StreamCast",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        Button(onClick = { selectVideo() }) {
                            Text("Select Movie to Stream")
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        if (isServerRunning.value) {
                            Text("Active Video: ${videoNameState.value}")
                            Spacer(modifier = Modifier.height(16.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("TV Network Stream URL:", style = MaterialTheme.typography.labelLarge)
                                    Text(
                                        text = serverUrlState.value,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Button(onClick = { stopServer() }) {
                                Text("Stop Stream")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun selectVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        selectVideoLauncher.launch(intent)
    }

    private fun startServer() {
        stopServer() // Ensure old server is stopped
        
        server = embeddedServer(Netty, port = 8080) {
            install(PartialContent)
            install(CORS) {
                anyHost()
            }
            routing {
                get("/stream") {
                    val uri = activeVideoUri
                    if (uri != null) {
                        val fileDescriptor = contentResolver.openAssetFileDescriptor(uri, "r")
                        val fileSize = fileDescriptor?.length ?: -1L
                        fileDescriptor?.close()
                        
                        if (fileSize >= 0) {
                            call.respond(UriReadChannelContent(contentResolver, uri, fileSize))
                        } else {
                            call.respondText("Cannot read file size", status = io.ktor.http.HttpStatusCode.InternalServerError)
                        }
                    } else {
                        call.respondText("No video selected", status = io.ktor.http.HttpStatusCode.NotFound)
                    }
                }
            }
        }
        server?.start(wait = false)
        
        val ip = getLocalIpAddress()
        serverUrlState.value = "http://$ip:8080/stream"
        isServerRunning.value = true
    }

    private fun stopServer() {
        server?.stop(1000, 2000)
        server = null
        isServerRunning.value = false
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "127.0.0.1"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }
}

class UriReadChannelContent(
    private val contentResolver: android.content.ContentResolver,
    private val uri: Uri,
    private val fileSize: Long
) : io.ktor.http.content.OutgoingContent.ReadChannelContent() {
    override val contentLength: Long = fileSize
    override val contentType: io.ktor.http.ContentType = io.ktor.http.ContentType.Video.Any

    override fun readFrom(): io.ktor.utils.io.ByteReadChannel {
        val inputStream = contentResolver.openInputStream(uri) ?: throw java.io.IOException("Failed to open input stream")
        return inputStream.toByteReadChannel(context = kotlinx.coroutines.Dispatchers.IO)
    }

    override fun readFrom(range: LongRange): io.ktor.utils.io.ByteReadChannel {
        val inputStream = contentResolver.openInputStream(uri) ?: throw java.io.IOException("Failed to open input stream")
        var toSkip = range.start
        while (toSkip > 0) {
            val skipped = inputStream.skip(toSkip)
            if (skipped <= 0) break
            toSkip -= skipped
        }
        return inputStream.toByteReadChannel(context = kotlinx.coroutines.Dispatchers.IO)
    }
}
