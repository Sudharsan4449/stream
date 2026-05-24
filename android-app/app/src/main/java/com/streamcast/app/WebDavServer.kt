package com.streamcast.app

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.options
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class WebDavServer(
    private val context: Context,
    private val storageEngine: SAFStorageEngine,
    private val port: Int = 8080
) {
    private var engine: NettyApplicationEngine? = null
    private val PropFindMethod = HttpMethod("PROPFIND")
    private val MultiStatus = HttpStatusCode(207, "Multi-Status")

    fun start() {
        if (engine != null) return

        engine = embeddedServer(Netty, port = port) {
            install(PartialContent)
            install(CORS) {
                anyHost()
                allowHeader("Depth")
                allowHeader("Destination")
                allowHeader("Overwrite")
                allowMethod(HttpMethod("PROPFIND"))
                allowMethod(HttpMethod("OPTIONS"))
            }

            routing {
                // OPTIONS handlers (crucial for WebDAV handshakes)
                options("/") {
                    call.response.header("DAV", "1")
                    call.response.header("Allow", "GET, HEAD, OPTIONS, PROPFIND")
                    call.respond(HttpStatusCode.OK)
                }
                options("{...}") {
                    call.response.header("DAV", "1")
                    call.response.header("Allow", "GET, HEAD, OPTIONS, PROPFIND")
                    call.respond(HttpStatusCode.OK)
                }

                // PROPFIND handlers (VLC folder list queries)
                route("/", PropFindMethod) {
                    handle {
                        handlePropFind(call, "/")
                    }
                }
                route("{...}", PropFindMethod) {
                    handle {
                        val decodedPath = URLDecoder.decode(call.request.path(), "UTF-8")
                        handlePropFind(call, decodedPath)
                    }
                }

                // GET handlers (media streams and subtitles)
                get("/") {
                    handleGet(call, "/")
                }
                get("{...}") {
                    val decodedPath = URLDecoder.decode(call.request.path(), "UTF-8")
                    handleGet(call, decodedPath)
                }
            }
        }
        engine?.start(wait = false)
    }

    fun stop() {
        engine?.stop(1000, 2000)
        engine = null
    }

    private suspend fun handlePropFind(call: ApplicationCall, path: String) {
        val resolvedNode = storageEngine.resolvePath(path)
        if (resolvedNode == null || !resolvedNode.exists()) {
            call.respond(HttpStatusCode.NotFound, "Directory or File not found")
            return
        }

        val depth = call.request.headers["Depth"] ?: "1"
        val nodesToReturn = mutableListOf<WebDavNode>()

        // 1. Always include the requested directory/file node itself
        val isDir = resolvedNode.isDirectory
        val nodeName = resolvedNode.name ?: ""
        val selfNode = WebDavNode(
            name = if (path == "/") "" else nodeName,
            path = "/" + path.trim('/'),
            isDirectory = isDir,
            size = if (isDir) 0L else resolvedNode.length(),
            lastModified = resolvedNode.lastModified(),
            mimeType = resolvedNode.type ?: (if (isDir) "httpd/unix-directory" else storageEngine.getMimeType(nodeName))
        )
        nodesToReturn.add(selfNode)

        // 2. If it is a directory and depth is "1", also include immediate children
        if (isDir && depth == "1") {
            nodesToReturn.addAll(storageEngine.listChildren(resolvedNode, path))
        }

        val xmlResponse = generateMultiStatusXml(nodesToReturn)
        call.respondText(xmlResponse, ContentType.Text.Xml.withCharset(Charsets.UTF_8), MultiStatus)
    }

    private suspend fun handleGet(call: ApplicationCall, path: String) {
        val resolvedNode = storageEngine.resolvePath(path)
        if (resolvedNode == null || !resolvedNode.exists()) {
            call.respond(HttpStatusCode.NotFound, "File not found")
            return
        }

        if (resolvedNode.isDirectory) {
            call.respond(HttpStatusCode.Forbidden, "Direct HTTP GET on folder is forbidden. Use a WebDAV browser.")
            return
        }

        val fileSize = resolvedNode.length()
        val mimeType = resolvedNode.type ?: storageEngine.getMimeType(resolvedNode.name ?: "video.mp4")

        try {
            call.respond(UriReadChannelContent(context.contentResolver, resolvedNode, fileSize, mimeType))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, "Error opening media stream: ${e.message}")
        }
    }

    private fun generateMultiStatusXml(nodes: List<WebDavNode>): String {
        val xml = StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n")
        xml.append("<d:multistatus xmlns:d=\"DAV:\">\n")

        val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }

        for (node in nodes) {
            // Encode path segments properly to prevent XML and URL breakdown in VLC TV
            val cleanHref = node.path.split("/").joinToString("/") { segment ->
                URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
            val escapedHref = if (node.isDirectory && !cleanHref.endsWith("/")) "$cleanHref/" else cleanHref
            val escapedName = escapeXml(node.name)
            val modifiedStr = dateFormat.format(Date(node.lastModified))

            xml.append("  <d:response>\n")
            xml.append("    <d:href>").append(escapedHref).append("</d:href>\n")
            xml.append("    <d:propstat>\n")
            xml.append("      <d:prop>\n")
            
            if (node.isDirectory) {
                xml.append("        <d:resourcetype><d:collection/></d:resourcetype>\n")
                xml.append("        <d:getcontenttype>httpd/unix-directory</d:getcontenttype>\n")
            } else {
                xml.append("        <d:resourcetype/>\n")
                xml.append("        <d:getcontentlength>").append(node.size).append("</d:getcontentlength>\n")
                xml.append("        <d:getcontenttype>").append(escapeXml(node.mimeType)).append("</d:getcontenttype>\n")
            }
            
            xml.append("        <d:displayname>").append(escapedName).append("</d:displayname>\n")
            xml.append("        <d:getlastmodified>").append(modifiedStr).append("</d:getlastmodified>\n")
            xml.append("      </d:prop>\n")
            xml.append("      <d:status>HTTP/1.1 200 OK</d:status>\n")
            xml.append("    </d:propstat>\n")
            xml.append("  </d:response>\n")
        }

        xml.append("</d:multistatus>")
        return xml.toString()
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
