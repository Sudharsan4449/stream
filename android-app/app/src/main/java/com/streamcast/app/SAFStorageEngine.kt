package com.streamcast.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.ConcurrentHashMap

class SAFStorageEngine(
    private val context: Context,
    val rootUri: Uri
) {
    private val rootFolder: DocumentFile by lazy {
        DocumentFile.fromTreeUri(context, rootUri) 
            ?: throw IllegalArgumentException("Invalid Scoped Storage directory URI")
    }

    // In-memory weak cache of WebDAV paths mapped directly to resolved SAF DocumentFiles
    private val pathCache = ConcurrentHashMap<String, DocumentFile>()

    init {
        pathCache["/"] = rootFolder
    }

    /**
     * Resolves a WebDAV absolute path string (e.g. "/Movies/Inception.mp4") to a DocumentFile.
     * Splitting the path into segments and traversing dynamically down the SAF hierarchy.
     */
    fun resolvePath(path: String): DocumentFile? {
        val cleanPath = "/" + path.trim('/').replace(Regex("/{2,}"), "/")
        
        val cached = pathCache[cleanPath]
        if (cached != null) return cached

        if (cleanPath == "/") return rootFolder

        val segments = cleanPath.split("/").filter { it.isNotEmpty() }
        var current: DocumentFile = rootFolder

        var currentPath = ""
        for (segment in segments) {
            currentPath += "/$segment"
            
            // Check cache for intermediate folders
            val cached = pathCache[currentPath]
            if (cached != null) {
                current = cached
                continue
            }

            // Otherwise list files to find matching child segment name
            val child = current.listFiles().find { it.name == segment }
                ?: return null // Path segment not found in hierarchy
            
            current = child
            pathCache[currentPath] = current
        }

        return current
    }

    /**
     * Lists immediate children of a given resolved folder.
     */
    fun listChildren(folder: DocumentFile, parentPath: String): List<WebDavNode> {
        if (!folder.isDirectory) return emptyList()
        val cleanParent = "/" + parentPath.trim('/').replace(Regex("/{2,}"), "/")
        val prefix = if (cleanParent == "/") "" else cleanParent

        return folder.listFiles().map { child ->
            val childName = child.name ?: "Unnamed"
            val childPath = "$prefix/$childName"
            
            // Cache the child to speed up immediate subsequent lookups
            pathCache[childPath] = child
            
            val isDir = child.isDirectory
            WebDavNode(
                name = childName,
                path = childPath,
                isDirectory = isDir,
                size = if (isDir) 0L else child.length(),
                lastModified = child.lastModified(),
                mimeType = child.type ?: (if (isDir) "httpd/unix-directory" else getMimeType(childName))
            )
        }
    }

    /**
     * Helper to detect and assign standard MIME types for video, subtitle, and media files.
     */
    fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            "webm" -> "video/webm"
            "srt" -> "text/plain"
            "vtt" -> "text/vtt"
            "ssa", "ass" -> "text/plain"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }
}

data class WebDavNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String
)
