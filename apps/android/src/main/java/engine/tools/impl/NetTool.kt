package engine.tools.impl

import android.util.Base64
import engine.crypto.currentTimeMillis
import engine.tools.ToolArgs
import engine.tools.ToolContext
import engine.tools.ToolHandler
import engine.tools.ToolResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import io.ktor.server.cio.CIO as ServerCIO

/**
 * Universal Network Transfer Capability ("network.transfer@1" / "tools://net").
 *
 * Unopinionated networking primitive built on Ktor, covering the two halves of any
 * peer-to-peer / hub / file-sharing app (e.g. Xender-style transfers):
 *
 *  1. Outbound client operations — `request`/`get`/`post`/`download`/`upload` — issued
 *     through a shared Ktor [HttpClient] (CIO engine).
 *  2. An embedded LAN transfer hub — `serve`/`stop`/`status`/`share`/`list`/`unshare` —
 *     a Ktor CIO HTTP server that lets nearby devices discover and pull shared items,
 *     with an auto-generated discovery page served at `/`.
 *
 * Payloads are passed as base64 (or UTF-8 text) so the tool never assumes a content shape.
 */
class NetTool(
    private val clientFactory: () -> HttpClient = ::defaultClient
) : ToolHandler {
    override val capability: String = "network.transfer@1"
    override val requiredPermissions: Set<String> = setOf("android.permission.INTERNET")

    private val client: HttpClient by lazy(clientFactory)

    private var server: EmbeddedServer<*, *>? = null
    private var serverPort: Int = 0
    private var hubId: String? = null
    private val shared: ConcurrentHashMap<String, SharedItem> = ConcurrentHashMap()

    private data class SharedItem(
        val id: String,
        val name: String,
        val mime: String,
        val bytes: ByteArray
    )

    override suspend fun invoke(args: ToolArgs, ctx: ToolContext): ToolResult {
        val op = (args["op"] ?: args["action"] ?: args["operation"] ?: "request").lowercase()
        return try {
            when (op) {
                "request", "http", "get", "post", "put", "patch", "delete", "head", "fetch" ->
                    executeRequest(args, op)
                "download" -> executeDownload(args)
                "upload", "send" -> executeUpload(args, op)
                "serve", "hub", "host", "start_server" -> executeServe(args)
                "stop", "stop_server", "shutdown" -> executeStop()
                "status", "capabilities" -> executeStatus()
                "share", "put_share", "add" -> executeShare(args)
                "list", "list_share", "ls" -> executeList()
                "unshare", "remove" -> executeUnshare(args)
                else -> ToolResult.Failed(capability, "Unsupported net operation '$op'")
            }
        } catch (e: Exception) {
            ToolResult.Failed(capability, e.message ?: "Network operation failed")
        }
    }

    // ------------------------------------------------------------------
    // Outbound client operations
    // ------------------------------------------------------------------

    private suspend fun executeRequest(args: ToolArgs, op: String): ToolResult {
        val url = args["url"] ?: args["endpoint"] ?: args["target"]
            ?: return ToolResult.Failed(capability, "missing url argument")

        val method = resolveMethod(args, op)
        val bodyBytes = decodePayload(args)
        val timeoutMs = args.getInt("timeout_ms", 30000).toLong()

        val response = client.request(url) {
            this.method = method
            args.toMap().forEach { (k, v) ->
                if (k.startsWith("header_")) headers.append(k.removePrefix("header_"), v)
            }
            args["content_type"]?.let { headers.append("Content-Type", it) }
            if (bodyBytes != null && method != HttpMethod.Get && method != HttpMethod.Head) {
                setBody(bodyBytes)
            }
            timeout {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
            }
        }

        val contentType = response.headers["Content-Type"] ?: "application/octet-stream"
        val raw = response.bodyAsBytes()

        val data = buildMap<String, Any> {
            put("status", "SUCCESS")
            put("http_status", response.status.value)
            put("method", method.value)
            put("url", url)
            put("content_type", contentType)
            put("bytes", raw.size)
            put(
                "headers",
                response.headers.entries().associate { it.key to it.value.joinToString(", ") }
            )
            put("timestamp", currentTimeMillis())
            if (isTextLike(contentType)) {
                put("body", String(raw, Charsets.UTF_8))
            } else {
                put("body_b64", Base64.encodeToString(raw, Base64.NO_WRAP))
            }
        }
        return ToolResult.Success(data)
    }

    private suspend fun executeDownload(args: ToolArgs): ToolResult {
        val url = args["url"] ?: args["endpoint"] ?: args["target"]
            ?: return ToolResult.Failed(capability, "missing url argument")
        val timeoutMs = args.getInt("timeout_ms", 60000).toLong()

        val response = client.request(url) {
            method = HttpMethod.Get
            args.toMap().forEach { (k, v) ->
                if (k.startsWith("header_")) headers.append(k.removePrefix("header_"), v)
            }
            timeout { requestTimeoutMillis = timeoutMs }
        }
        val raw = response.bodyAsBytes()
        val contentType = response.headers["Content-Type"] ?: "application/octet-stream"

        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "http_status" to response.status.value,
                "url" to url,
                "file_name" to (args["file_name"] ?: deriveFileName(url)),
                "content_type" to contentType,
                "bytes" to raw.size,
                "content_b64" to Base64.encodeToString(raw, Base64.NO_WRAP),
                "timestamp" to currentTimeMillis()
            )
        )
    }

    private suspend fun executeUpload(args: ToolArgs, op: String): ToolResult {
        val url = args["url"] ?: args["endpoint"] ?: args["target"]
            ?: return ToolResult.Failed(capability, "missing url argument")
        val bytes = decodePayload(args)
            ?: return ToolResult.Failed(capability, "missing content_base64/payload/file_path argument")
        val method = resolveMethod(args, if (op == "send") "post" else op)
        val timeoutMs = args.getInt("timeout_ms", 60000).toLong()

        val response = client.request(url) {
            this.method = method
            args.toMap().forEach { (k, v) ->
                if (k.startsWith("header_")) headers.append(k.removePrefix("header_"), v)
            }
            headers.append("Content-Type", args["content_type"] ?: "application/octet-stream")
            setBody(bytes)
            timeout { requestTimeoutMillis = timeoutMs }
        }
        val raw = response.bodyAsBytes()

        return ToolResult.Success(
            mapOf(
                "status" to "SUCCESS",
                "http_status" to response.status.value,
                "method" to method.value,
                "url" to url,
                "uploaded_bytes" to bytes.size,
                "response_body" to String(raw, Charsets.UTF_8),
                "timestamp" to currentTimeMillis()
            )
        )
    }

    // ------------------------------------------------------------------
    // Embedded LAN transfer hub
    // ------------------------------------------------------------------

    private suspend fun executeServe(args: ToolArgs): ToolResult {
        if (server != null) {
            val status = executeStatus()
            return if (status is ToolResult.Success) {
                ToolResult.Success(status.data + ("note" to "hub already running"))
            } else status
        }

        val port = args.getInt("port", 8089).coerceIn(0, 65535)
        val host = args["host"] ?: "0.0.0.0"
        val label = args["label"] ?: args["name"] ?: "ApexClient Transfer Hub"

        val engine = embeddedServer(ServerCIO, port = port, host = host) {
            routing {
                get("/") {
                    call.respondText(hubIndexHtml(label), ContentType.Text.Html.withCharset(Charsets.UTF_8))
                }
                get("/health") {
                    call.respondText("""{"status":"ok","hub":"${escape(label)}"}""", ContentType.Application.Json)
                }
                get("/list") {
                    call.respondText(listJson(), ContentType.Application.Json)
                }
                get("/file/{id}") {
                    val item = call.parameters["id"]?.let { shared[it] }
                    if (item == null) {
                        call.respondText("""{"error":"not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                    } else {
                        call.respondBytes(item.bytes, ContentType.parse(item.mime))
                    }
                }
                post("/upload") {
                    val raw = call.receiveText()
                    val name = call.request.queryParameters["name"] ?: "upload_${shared.size + 1}"
                    val bytes = Base64.decode(raw, Base64.DEFAULT)
                    val id = newId()
                    shared[id] = SharedItem(id, name, "application/octet-stream", bytes)
                    call.respondText("""{"status":"stored","id":"$id","bytes":${bytes.size}}""", ContentType.Application.Json)
                }
            }
        }
        engine.start(wait = false)
        server = engine
        serverPort = engine.engine.resolvedConnectors().firstOrNull()?.port ?: port
        val hub = hubId ?: newId().also { hubId = it }

        return ToolResult.Success(
            mapOf(
                "status" to "SERVING",
                "hub_id" to hub,
                "label" to label,
                "host" to host,
                "port" to serverPort,
                "share_count" to shared.size,
                "urls" to listOf(
                    "http://<device-ip>:$serverPort/",
                    "http://<device-ip>:$serverPort/health",
                    "http://<device-ip>:$serverPort/list"
                ),
                "timestamp" to currentTimeMillis()
            )
        )
    }

    private fun executeStop(): ToolResult {
        val running = server
        val stoppedPort = serverPort
        server = null
        serverPort = 0
        try {
            running?.stop(500, 1500)
        } catch (_: Exception) {}
        return ToolResult.Success(
            mapOf(
                "status" to if (running != null) "STOPPED" else "NOT_RUNNING",
                "port" to stoppedPort,
                "timestamp" to currentTimeMillis()
            )
        )
    }

    private suspend fun executeStatus(): ToolResult {
        val running = server != null
        return ToolResult.Success(
            mapOf(
                "status" to if (running) "SERVING" else "IDLE",
                "running" to running,
                "hub_id" to (hubId ?: ""),
                "port" to serverPort,
                "share_count" to shared.size,
                "supported_operations" to listOf(
                    "request", "get", "post", "download", "upload",
                    "serve", "stop", "status", "share", "list", "unshare"
                ),
                "timestamp" to currentTimeMillis()
            )
        )
    }

    private fun executeShare(args: ToolArgs): ToolResult {
        val bytes = decodePayload(args)
            ?: return ToolResult.Failed(capability, "missing content_base64/payload/file_path argument")
        val id = newId()
        val name = args["name"] ?: args["file_name"] ?: "item_$id"
        val mime = args["content_type"] ?: "application/octet-stream"
        shared[id] = SharedItem(id, name, mime, bytes)

        return ToolResult.Success(
            mapOf(
                "status" to "SHARED",
                "id" to id,
                "name" to name,
                "mime" to mime,
                "bytes" to bytes.size,
                "path" to "/file/$id",
                "timestamp" to currentTimeMillis()
            )
        )
    }

    private fun executeList(): ToolResult {
        val items = shared.values.map {
            mapOf("id" to it.id, "name" to it.name, "mime" to it.mime, "bytes" to it.bytes.size)
        }
        return ToolResult.Success(
            mapOf(
                "status" to "OK",
                "count" to items.size,
                "items" to items,
                "timestamp" to currentTimeMillis()
            )
        )
    }

    private fun executeUnshare(args: ToolArgs): ToolResult {
        val id = args["id"] ?: args["share_id"]
            ?: return ToolResult.Failed(capability, "missing id argument")
        val removed = shared.remove(id) != null
        return ToolResult.Success(
            mapOf(
                "status" to if (removed) "REMOVED" else "NOT_FOUND",
                "id" to id,
                "remaining" to shared.size,
                "timestamp" to currentTimeMillis()
            )
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun listJson(): String {
        val entries = shared.values.joinToString(",") {
            """{"id":"${it.id}","name":"${escape(it.name)}","mime":"${escape(it.mime)}","bytes":${it.bytes.size}}"""
        }
        return """{"items":[$entries]}"""
    }

    private fun hubIndexHtml(label: String): String {
        val rows = shared.values.joinToString("") {
            "<li><a href=\"/file/${it.id}\" download=\"${escape(it.name)}\">${escape(it.name)}</a> " +
                "<small>(${it.bytes.size} bytes)</small></li>"
        }
        val body = rows.ifBlank { "<li><em>No shared items yet.</em></li>" }
        return """
            <!doctype html>
            <html><head><meta charset="utf-8"/>
            <meta name="viewport" content="width=device-width, initial-scale=1"/>
            <title>${escape(label)}</title>
            <style>body{font-family:system-ui,sans-serif;margin:2rem;max-width:40rem}
            h1{font-size:1.25rem}li{margin:.35rem 0}</style></head>
            <body><h1>${escape(label)}</h1>
            <p>Nearby devices on the same network can download shared items below.</p>
            <ul>$body</ul>
            </body></html>
        """.trimIndent()
    }

    private fun resolveMethod(args: ToolArgs, op: String): HttpMethod {
        val raw = args["method"]
            ?: if (op in setOf("get", "post", "put", "patch", "delete", "head")) op else "GET"
        return try {
            HttpMethod.parse(raw.uppercase())
        } catch (_: Exception) {
            HttpMethod.Get
        }
    }

    private fun decodePayload(args: ToolArgs): ByteArray? {
        args["content_base64"]?.let { return Base64.decode(it, Base64.DEFAULT) }
        args["payload_b64"]?.let { return Base64.decode(it, Base64.DEFAULT) }
        args["body"]?.let { return it.toByteArray(Charsets.UTF_8) }
        args["payload"]?.let { return it.toByteArray(Charsets.UTF_8) }
        args["content"]?.let { return it.toByteArray(Charsets.UTF_8) }
        args["file_path"]?.let { return runCatching { java.io.File(it).readBytes() }.getOrNull() }
        return null
    }

    private fun deriveFileName(url: String): String {
        val path = url.substringBefore('?').substringAfterLast('/')
        return path.ifBlank { "download_${currentTimeMillis()}" }
    }

    private fun isTextLike(contentType: String): Boolean {
        val ct = contentType.lowercase()
        return ct.startsWith("text/") || ct.contains("json") || ct.contains("xml") ||
            ct.contains("javascript") || ct.contains("x-www-form-urlencoded")
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    companion object {
        private fun defaultClient(): HttpClient = HttpClient(CIO) {
            engine { requestTimeout = 30_000 }
            install(HttpTimeout)
            expectSuccess = false
        }
    }
}
