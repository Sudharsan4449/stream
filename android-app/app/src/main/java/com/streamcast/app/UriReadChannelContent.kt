package com.streamcast.app

import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import java.io.FileInputStream
import java.io.IOException

class UriReadChannelContent(
    private val contentResolver: ContentResolver,
    private val file: DocumentFile,
    private val fileSize: Long,
    private val mime: String
) : OutgoingContent.ReadChannelContent() {
    override val contentLength: Long = fileSize
    override val contentType: ContentType = ContentType.parse(mime)

    override fun readFrom(): ByteReadChannel {
        val pfd = contentResolver.openFileDescriptor(file.uri, "r")
            ?: throw IOException("Failed to open file descriptor for: ${file.name}")
        val inputStream = ParcelFileInputStream(pfd)
        return inputStream.toByteReadChannel(context = Dispatchers.IO)
    }

    override fun readFrom(range: LongRange): ByteReadChannel {
        val pfd = contentResolver.openFileDescriptor(file.uri, "r")
            ?: throw IOException("Failed to open file descriptor for seeking: ${file.name}")
        
        val fis = FileInputStream(pfd.fileDescriptor)
        val fileChannel = fis.channel
        
        // Instant hardware-level seek directly to the requested byte position
        fileChannel.position(range.start)
        
        val length = range.endInclusive - range.start + 1
        
        // Wrap the seeked stream to restrict reading to range boundaries and release resources
        val inputStream = LimitedParcelFileInputStream(pfd, fis, length)
        return inputStream.toByteReadChannel(context = Dispatchers.IO)
    }
}

/**
 * An InputStream wrapper that automatically releases the Android ParcelFileDescriptor on close,
 * preventing file descriptor exhaustion/leaks in the OS.
 */
class ParcelFileInputStream(
    private val pfd: ParcelFileDescriptor
) : java.io.InputStream() {
    private val fis = FileInputStream(pfd.fileDescriptor)

    override fun read(): Int = fis.read()
    
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        return fis.read(b, off, len)
    }
    
    override fun close() {
        super.close()
        fis.close()
        pfd.close() // Release Android system file descriptor
    }
}

/**
 * A seek-limited InputStream wrapper that restricts reading to exactly 'limit' bytes,
 * preventing Ktor or media players from reading beyond the requested range boundaries.
 */
class LimitedParcelFileInputStream(
    private val pfd: ParcelFileDescriptor,
    private val fis: FileInputStream,
    private val limit: Long
) : java.io.InputStream() {
    private var bytesRead = 0L

    override fun read(): Int {
        if (bytesRead >= limit) return -1
        val data = fis.read()
        if (data != -1) bytesRead++
        return data
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (bytesRead >= limit) return -1
        val remaining = limit - bytesRead
        val toRead = Math.min(len.toLong(), remaining).toInt()
        val read = fis.read(b, off, toRead)
        if (read != -1) bytesRead += read
        return read
    }

    override fun close() {
        super.close()
        fis.close()
        pfd.close() // Release Android system file descriptor
    }
}
