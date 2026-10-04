package com.questsoundboard.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.questsoundboard.AppContainer
import com.questsoundboard.audio.Route
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Tiny zero-dependency HTTP server that exposes the soundboard to anything with
 * a browser on the same Wi-Fi. This is the main way people actually use it:
 * headset on your face, phone in your hand, tap pads without leaving the game.
 *
 * Endpoints
 *   GET  /                      web panel
 *   GET  /api/state             everything the UI needs (sounds, status, playing)
 *   POST /api/play/{id}
 *   POST /api/stop/{id}
 *   POST /api/stopall
 *   POST /api/rescan
 *   POST /api/route?value=MIC
 *   POST /api/volume?master=0.8&monitor=0.5
 *   POST /api/sound/{id}?volume=&loop=&pad=&hotkey=
 *   POST /api/upload?name=x.mp3&category=Memes   (raw body = file bytes)
 *   POST /api/hotkey/capture    arms the next controller press for binding
 *   GET  /api/events            server-sent events stream
 */
class ControlServer(
    private val context: Context,
    private val container: AppContainer,
    private val port: Int = 8099
) {

    companion object {
        private const val TAG = "ControlServer"
        private const val MAX_UPLOAD = 64 * 1024 * 1024
    }

    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    @Volatile private var running = false
    @Volatile private var capturedKey: String? = null

    fun start() {
        if (running) return
        running = true
        val socket = ServerSocket(port).apply { reuseAddress = true }
        serverSocket = socket
        Thread({
            while (running) {
                try {
                    val client = socket.accept()
                    pool.execute { handle(client) }
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "accept failed", e)
                }
            }
        }, "control-server").apply { isDaemon = true }.start()
        Log.i(TAG, "listening on ${panelUrl()}")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        pool.shutdownNow()
    }

    fun reportCapturedKey(key: String) {
        capturedKey = key
    }

    fun panelUrl(): String = "http://${localIp()}:$port"

    fun localIp(): String {
        runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wifi.connectionInfo.ipAddress
            if (ip != 0) {
                return InetAddress.getByAddress(
                    byteArrayOf(
                        (ip and 0xff).toByte(), (ip shr 8 and 0xff).toByte(),
                        (ip shr 16 and 0xff).toByte(), (ip shr 24 and 0xff).toByte()
                    )
                ).hostAddress ?: "127.0.0.1"
            }
        }
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
                ?.hostAddress?.let { return it }
        }
        return "127.0.0.1"
    }

    // ------------------------------------------------------------------ routing

    private fun handle(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 30_000
                val input = sock.getInputStream()
                val out = BufferedOutputStream(sock.getOutputStream())

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val rawPath = parts[1]
                val path = rawPath.substringBefore('?')
                val query = parseQuery(rawPath.substringAfter('?', ""))

                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] =
                            line.substring(idx + 1).trim()
                    }
                }

                if (method == "OPTIONS") {
                    respond(out, 204, "text/plain", ByteArray(0)); return
                }

                when {
                    path == "/api/events" -> serveEvents(out)
                    path.startsWith("/api/") -> {
                        val body = readBody(input, headers)
                        val json = route(method, path, query, body)
                        respond(out, 200, "application/json", json.toString().toByteArray())
                    }
                    path.startsWith("/sound/") -> serveSoundFile(out, path.removePrefix("/sound/"))
                    else -> serveStatic(out, path)
                }
            } catch (e: Exception) {
                Log.w(TAG, "request failed", e)
            }
        }
    }

    private fun route(
        method: String,
        path: String,
        q: Map<String, String>,
        body: ByteArray
    ): JSONObject {
        val engine = container.engine
        val library = container.library
        val settings = container.settings

        return when {
            path == "/api/state" -> stateJson()

            path.startsWith("/api/play/") -> {
                val id = path.removePrefix("/api/play/")
                val sound = library.byId(id)
                    ?: return error("unknown sound $id")
                val voice = engine.play(sound)
                ok(JSONObject().put("voiceId", voice).put("decoded", voice >= 0))
            }

            path.startsWith("/api/stop/") -> {
                engine.stopSound(path.removePrefix("/api/stop/")); ok()
            }

            path == "/api/stopall" -> { engine.stopAll(); ok() }

            path == "/api/rescan" -> {
                val sounds = runBlocking { library.scan() }
                ok(JSONObject().put("count", sounds.size))
            }

            path == "/api/route" -> {
                val value = q["value"] ?: return error("missing value")
                val newRoute = runCatching { Route.valueOf(value) }.getOrNull()
                    ?: return error("bad route $value")
                engine.setRoute(newRoute)
                settings.route = engine.route.name
                ok(JSONObject().put("route", engine.route.name))
            }

            path == "/api/volume" -> {
                q["master"]?.toFloatOrNull()?.let {
                    engine.masterVolume = it.coerceIn(0f, 2f); settings.masterVolume = engine.masterVolume
                }
                q["monitor"]?.toFloatOrNull()?.let {
                    engine.monitorVolume = it.coerceIn(0f, 2f); settings.monitorVolume = engine.monitorVolume
                }
                ok()
            }

            path.startsWith("/api/sound/") -> {
                val id = path.removePrefix("/api/sound/")
                if (method == "DELETE") {
                    val deleted = runBlocking { library.delete(id) }
                    return ok(JSONObject().put("deleted", deleted))
                }
                runBlocking {
                    library.update(id) { s ->
                        q["volume"]?.toFloatOrNull()?.let { s.volume = it.coerceIn(0f, 2f) }
                        q["loop"]?.let { s.loop = it.toBoolean() }
                        q["pad"]?.toIntOrNull()?.let { s.pad = it }
                        if (q.containsKey("hotkey")) {
                            s.hotkey = q["hotkey"]?.takeIf { it.isNotBlank() && it != "none" }
                        }
                    }
                }
                ok()
            }

            path == "/api/upload" -> {
                val name = q["name"] ?: return error("missing name")
                if (body.size > MAX_UPLOAD) return error("file too large")
                val sound = runBlocking {
                    library.import(name, q["category"] ?: "General", body)
                } ?: return error("unsupported or unreadable file")
                ok(JSONObject().put("id", sound.id).put("name", sound.name))
            }

            path == "/api/hotkey/capture" -> {
                val monitor = com.questsoundboard.service.SoundboardService.hotkeys
                    ?: return error("hotkeys need root")
                capturedKey = null
                monitor.captureMode = true
                ok()
            }

            path == "/api/hotkey/captured" ->
                ok(JSONObject().put("key", capturedKey ?: JSONObject.NULL))

            path == "/api/root/refresh" -> {
                val status = runBlocking { container.rootManager.refresh() }
                ok(rootJson(status))
            }

            else -> error("unknown endpoint $path")
        }
    }

    private fun stateJson(): JSONObject {
        val engine = container.engine
        val library = container.library

        val sounds = JSONArray()
        for (s in library.sounds.value) sounds.put(s.toJson())

        val playing = JSONArray()
        for (p in engine.nowPlaying.value) {
            playing.put(
                JSONObject()
                    .put("voiceId", p.voiceId).put("soundId", p.soundId)
                    .put("name", p.name).put("positionMs", p.positionMs)
                    .put("durationMs", p.durationMs).put("loop", p.loop)
            )
        }

        val categories = JSONArray()
        for (c in library.categories.value) categories.put(c)

        return JSONObject()
            .put("ok", true)
            .put("sounds", sounds)
            .put("categories", categories)
            .put("playing", playing)
            .put("route", engine.route.name)
            .put("masterVolume", engine.masterVolume.toDouble())
            .put("monitorVolume", engine.monitorVolume.toDouble())
            .put("peak", engine.peakLevel.value.toDouble())
            .put("engineRunning", engine.isRunning)
            .put("libraryError", library.lastScanError ?: JSONObject.NULL)
            .put("root", rootJson(container.rootManager.status.value))
            .put("micInjector", JSONObject()
                .put("state", container.micInjector.state.name)
                .put("error", container.micInjector.lastError ?: JSONObject.NULL))
    }

    private fun rootJson(s: com.questsoundboard.root.RootManager.Status) = JSONObject()
        .put("tier", s.tier.name)
        .put("rooted", s.rooted)
        .put("canInjectMic", s.canInjectMic)
        .put("provider", s.rootProvider)
        .put("magisk", s.magiskVersion ?: JSONObject.NULL)
        .put("moduleInstalled", s.moduleInstalled)
        .put("hiddenApiUnlocked", s.hiddenApiUnlocked)
        .put("selinuxEnforcing", s.selinuxEnforcing)
        .put("device", s.device)
        .put("android", s.androidRelease)

    private fun ok(extra: JSONObject? = null): JSONObject {
        val obj = JSONObject().put("ok", true)
        extra?.keys()?.forEach { obj.put(it, extra.get(it)) }
        return obj
    }

    private fun error(message: String) = JSONObject().put("ok", false).put("error", message)

    // ------------------------------------------------------------------ static

    private fun serveStatic(out: BufferedOutputStream, path: String) {
        val asset = when (path) {
            "/", "" -> "web/index.html"
            else -> "web" + path
        }
        try {
            val bytes = context.assets.open(asset).use { it.readBytes() }
            respond(out, 200, mimeFor(asset), bytes)
        } catch (_: Exception) {
            respond(out, 404, "text/plain", "not found".toByteArray())
        }
    }

    /** Lets the web panel preview a clip in the browser. */
    private fun serveSoundFile(out: BufferedOutputStream, id: String) {
        val sound = container.library.byId(URLDecoder.decode(id, "UTF-8"))
        if (sound == null || !sound.file.exists()) {
            respond(out, 404, "text/plain", "not found".toByteArray()); return
        }
        respond(out, 200, mimeFor(sound.path), sound.file.readBytes())
    }

    private fun serveEvents(out: BufferedOutputStream) {
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                "Cache-Control: no-cache\r\nConnection: keep-alive\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n").toByteArray()
        )
        out.flush()
        while (running) {
            val payload = stateJson().toString()
            out.write("data: $payload\n\n".toByteArray())
            out.flush()
            Thread.sleep(400)
        }
    }

    private fun mimeFor(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "m4a", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }

    private fun respond(out: BufferedOutputStream, code: Int, mime: String, body: ByteArray) {
        val status = when (code) {
            200 -> "200 OK"; 204 -> "204 No Content"; 404 -> "404 Not Found"
            else -> "$code Error"
        }
        out.write(
            ("HTTP/1.1 $status\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: *\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }

    // ------------------------------------------------------------------ parsing

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
        }
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val length = headers["content-length"]?.toIntOrNull() ?: return ByteArray(0)
        if (length <= 0 || length > MAX_UPLOAD) return ByteArray(0)
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n <= 0) break
            read += n
        }
        return if (read == length) body else body.copyOf(read)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null
            else URLDecoder.decode(pair.substring(0, i), "UTF-8") to
                URLDecoder.decode(pair.substring(i + 1), "UTF-8")
        }.toMap()
    }
}
