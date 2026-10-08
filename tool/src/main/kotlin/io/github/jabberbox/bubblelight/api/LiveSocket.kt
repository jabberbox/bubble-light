package io.github.jabberbox.bubblelight.api

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * The live event feed from a BlueBubbles server. The server speaks Socket.IO v4, and the Socket.IO client
 * library is not on the SDK's dependency allowlist, so this talks the wire protocol directly over a plain
 * WebSocket. Only what the feed needs is implemented: the Engine.IO handshake and heartbeat, the default
 * namespace connect, and text events carrying one JSON object.
 *
 * Frames are `<engine type><payload>`: `0{...}` open, `2` ping (answered with `3`), `4` carries a Socket.IO
 * packet. Socket.IO packets are `<type><payload>`: `0` connected, `4` connect error, `2["event",{...}]` event.
 *
 * It reconnects by itself with a backoff until [stop] is called. Nothing from a payload is ever logged.
 */
class LiveSocket(
    baseUrl: String,
    private val password: String,
    private val scope: CoroutineScope,
    /** True once the server accepted the connection, false when it dropped, null when stopped on purpose. */
    private val onState: (Boolean?) -> Unit,
    private val onEvent: (name: String, data: JSONObject) -> Unit,
    private val log: (String) -> Unit,
) {
    private val wsBase = baseUrl.trimEnd('/').let {
        when {
            it.startsWith("https://") -> "wss://" + it.removePrefix("https://")
            it.startsWith("http://") -> "ws://" + it.removePrefix("http://")
            else -> it
        }
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var job: Job? = null

    @Volatile
    private var current: WebSocket? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        current?.cancel()
        current = null
        onState(null)
    }

    private suspend fun loop() {
        var backoff = FIRST_BACKOFF_MS
        val request = Request.Builder()
            .url("$wsBase/socket.io/?EIO=4&transport=websocket&password=" + URLEncoder.encode(password, "UTF-8"))
            .build()
        while (currentCoroutineContext().isActive) {
            val listener = Listener()
            val socket = client.newWebSocket(request, listener)
            current = socket
            val reached = try {
                coroutineScope {
                    val watchdog = launch { watch(listener, socket) }
                    val result = listener.done.await()
                    watchdog.cancel()
                    result
                }
            } finally {
                socket.cancel()
                current = null
                // A deliberate stop reports null itself, so only a drop reports false.
                if (currentCoroutineContext().isActive) onState(false)
            }
            backoff = if (reached) FIRST_BACKOFF_MS else minOf(backoff * 2, MAX_BACKOFF_MS)
            delay(backoff)
        }
    }

    /** Drops a connection that has gone quiet for longer than the server's heartbeat allows. */
    private suspend fun watch(listener: Listener, socket: WebSocket) {
        while (true) {
            delay(WATCH_STEP_MS)
            if (System.currentTimeMillis() - listener.lastRx > listener.silenceLimitMs) {
                log("live: no heartbeat, reconnecting")
                socket.cancel()
                return
            }
        }
    }

    private inner class Listener : WebSocketListener() {
        val done = CompletableDeferred<Boolean>()

        @Volatile
        var lastRx = System.currentTimeMillis()

        @Volatile
        var silenceLimitMs = HANDSHAKE_LIMIT_MS

        @Volatile
        private var connected = false

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastRx = System.currentTimeMillis()
            when (text.firstOrNull()) {
                '0' -> {
                    val open = runCatching { JSONObject(text.substring(1)) }.getOrNull()
                    val interval = open?.optLong("pingInterval", 25_000L) ?: 25_000L
                    val timeout = open?.optLong("pingTimeout", 20_000L) ?: 20_000L
                    silenceLimitMs = interval + timeout + 5_000L
                    webSocket.send("40")
                }
                '2' -> webSocket.send("3")
                '1' -> webSocket.close(1000, null)
                '4' -> socketIo(webSocket, text.substring(1))
            }
        }

        private fun socketIo(webSocket: WebSocket, packet: String) {
            when (packet.firstOrNull()) {
                '0' -> {
                    connected = true
                    onState(true)
                }
                '1' -> webSocket.close(1000, null)
                '4' -> {
                    log("live: server refused the connection")
                    webSocket.close(1000, null)
                }
                '2' -> event(packet.substring(1))
            }
        }

        /** An event is `[ack id]["name", {payload}]`, optionally preceded by a namespace, which this feed does not use. */
        private fun event(body: String) {
            val json = body.dropWhile { it.isDigit() }
            val array = runCatching { JSONArray(json) }.getOrNull() ?: return
            val name = array.optString(0, "").takeIf { it.isNotBlank() } ?: return
            val data = array.optJSONObject(1) ?: return
            runCatching { onEvent(name, data) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            done.complete(connected)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log("live: ${t::class.simpleName}")
            done.complete(connected)
        }
    }

    private companion object {
        const val FIRST_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val WATCH_STEP_MS = 5_000L
        const val HANDSHAKE_LIMIT_MS = 30_000L
    }
}
