package com.streamcast.app

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

object WebDavClient {

    data class ServerNode(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val contentType: String
    )

    suspend fun listFolder(serverUrl: String, path: String): List<ServerNode> = withContext(Dispatchers.IO) {
        val resultList = mutableListOf<ServerNode>()
        var connection: HttpURLConnection? = null
        try {
            val cleanPath = "/" + path.trim('/')
            val fullUrl = serverUrl.trimEnd('/') + cleanPath
            val url = URL(fullUrl)
            
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PROPFIND"
                setRequestProperty("Depth", "1")
                connectTimeout = 5000
                readTimeout = 5000
                doInput = true
            }

            val responseCode = connection.responseCode
            if (responseCode == 207 || responseCode == 200) {
                val inputStream = connection.inputStream
                resultList.addAll(parseWebDavXml(inputStream))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            connection?.disconnect()
        }
        
        // Filter out the requested directory itself (which WebDAV always includes as the first item)
        val cleanParentPath = "/" + path.trim('/')
        resultList.filter { 
            val nodePath = "/" + it.path.trim('/')
            nodePath != cleanParentPath && nodePath.isNotEmpty()
        }
    }

    private fun parseWebDavXml(inputStream: InputStream): List<ServerNode> {
        val list = mutableListOf<ServerNode>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(inputStream, "UTF-8")
            
            var eventType = parser.eventType
            var currentHref = ""
            var currentName = ""
            var isDir = false
            var currentSize = 0L
            var currentMime = ""

            var insideResponse = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName.equals("response", ignoreCase = true) || tagName.endsWith(":response")) {
                            insideResponse = true
                            currentHref = ""
                            currentName = ""
                            isDir = false
                            currentSize = 0L
                            currentMime = ""
                        } else if (tagName.equals("href", ignoreCase = true) || tagName.endsWith(":href")) {
                            currentHref = parser.nextText().trim()
                        } else if (tagName.equals("displayname", ignoreCase = true) || tagName.endsWith(":displayname")) {
                            currentName = parser.nextText().trim()
                        } else if (tagName.equals("collection", ignoreCase = true) || tagName.endsWith(":collection")) {
                            isDir = true
                        } else if (tagName.equals("getcontentlength", ignoreCase = true) || tagName.endsWith(":getcontentlength")) {
                            currentSize = parser.nextText().trim().toLongOrNull() ?: 0L
                        } else if (tagName.equals("getcontenttype", ignoreCase = true) || tagName.endsWith(":getcontenttype")) {
                            currentMime = parser.nextText().trim()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName.equals("response", ignoreCase = true) || tagName.endsWith(":response")) {
                            if (insideResponse && currentHref.isNotEmpty()) {
                                val decodedPath = try {
                                    URLDecoder.decode(currentHref, "UTF-8")
                                } catch (e: Exception) {
                                    currentHref
                                }
                                val finalName = currentName.ifEmpty { 
                                    decodedPath.substringBeforeLast('/').substringAfterLast('/')
                                }
                                list.add(
                                    ServerNode(
                                        name = finalName,
                                        path = decodedPath,
                                        isDirectory = isDir,
                                        size = currentSize,
                                        contentType = currentMime.ifEmpty { 
                                            if (isDir) "httpd/unix-directory" else "application/octet-stream"
                                        }
                                    )
                                )
                            }
                            insideResponse = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}
