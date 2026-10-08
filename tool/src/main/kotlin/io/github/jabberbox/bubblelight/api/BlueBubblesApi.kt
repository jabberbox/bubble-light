package io.github.jabberbox.bubblelight.api

import io.github.jabberbox.bubblelight.data.MessageEntity
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal BlueBubbles server client over `HttpURLConnection`. The server password goes in the `password`
 * query parameter of every call, which is how the server authenticates. The live event feed is separate
 * (see [LiveSocket]).
 *
 * Based on craigeley/chat (MIT), https://github.com/craigeley/chat.
 */
class BlueBubblesApi(baseUrl: String, private val password: String) {
    private val baseUrl = baseUrl.trimEnd('/')

    /**
     * Checks the URL and password, and reports whether the server's Private API is live (`private_api`
     * and `helper_connected`). Throws [ApiException] for a rejected password and [IOException] when the
     * server cannot be reached.
     */
    fun serverInfo(): ServerInfo {
        val text = requestChecked("GET", "/api/v1/server/info", null, what = "server info")
        val data = dataObject(text)
        return ServerInfo(
            privateApiReady = data?.optBoolean("private_api", false) == true && data.optBoolean("helper_connected", false),
            serverVersion = data?.optString("server_version")?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * `POST /message/query`: the newest messages across every chat, newest first, each with its chat
     * embedded. This drives the conversation list instead of `chat/query`, whose ordering is a cache that
     * can leave a chat that just became active off the first page. With [after] set only messages newer
     * than that epoch-millis date transfer.
     */
    fun messages(limit: Int, offset: Int = 0, after: Long? = null): List<ParsedMessage> {
        val body = JSONObject()
            .put("limit", limit)
            .put("offset", offset)
            .put("with", JSONArray().put("chats").put("chats.participants").put("handle").put("attachment"))
            .put("sort", "DESC")
        if (after != null) body.put("after", after)
        return BlueBubblesJson.parseMessages(requestChecked("POST", "/api/v1/message/query", body, what = "messages"))
    }

    /** `GET /chat/:guid/message`: one chat's messages older than [before], newest first. */
    fun chatMessages(chatGuid: String, limit: Int, before: Long? = null): List<MessageEntity> {
        val query = buildString {
            append("with=handle,attachment&sort=DESC&limit=").append(limit)
            if (before != null) append("&before=").append(before)
        }
        val text = requestChecked("GET", "/api/v1/chat/${enc(chatGuid)}/message", null, what = "chat messages", extraQuery = query)
        return BlueBubblesJson.parseMessages(text, chatGuid).map { it.message }
    }

    /**
     * `POST /message/text`. [tempGuid] comes back on the socket echo so the local copy can be replaced.
     * [method] is `private-api` when the server has it, else `apple-script`. Returns the created message
     * when the server sends it back.
     */
    fun sendText(chatGuid: String, text: String, tempGuid: String, method: String): MessageEntity? {
        val body = JSONObject()
            .put("chatGuid", chatGuid)
            .put("tempGuid", tempGuid)
            .put("message", text)
            .put("method", method)
        val response = requestChecked("POST", "/api/v1/message/text", body, what = "send")
        val data = dataObject(response) ?: return null
        return BlueBubblesJson.parseMessage(data, chatGuid)?.message
    }

    /** `POST /chat/:guid/read`, marking the chat read and sending a read receipt. Needs the Private API. */
    fun markRead(chatGuid: String) {
        requestChecked("POST", "/api/v1/chat/${enc(chatGuid)}/read", null, what = "mark read")
    }

    /**
     * `POST /chat/new`, which creates a chat by sending its first message. A single address goes over
     * AppleScript, several make a group, which needs the Private API. Returns the new chat's guid.
     */
    fun newChat(addresses: List<String>, text: String): String {
        val body = JSONObject()
            .put("addresses", JSONArray().apply { addresses.forEach { put(it) } })
            .put("message", text)
            .put("service", "iMessage")
            .put("method", if (addresses.size > 1) "private-api" else "apple-script")
        val response = requestChecked("POST", "/api/v1/chat/new", body, what = "new chat")
        return dataObject(response)?.optString("guid")?.takeIf { it.isNotBlank() } ?: throw IOException("new chat: no guid returned")
    }

    /** `GET /contact`: the Mac's address book. */
    fun contacts(): List<AddressName> =
        BlueBubblesJson.parseContacts(requestChecked("GET", "/api/v1/contact", null, what = "contacts"))

    /**
     * `GET /attachment/:guid/download`, streamed into [dest] as the server has it. The endpoint can resize with
     * `width` and `height`, but it stretches to exactly those numbers when both are given, converts the result
     * to PNG, and drops the EXIF rotation, so photos come out squashed, sideways or both. The original is
     * downloaded instead and shrunk on the phone, which knows how to do that properly.
     */
    fun downloadAttachment(guid: String, dest: File) {
        val url = buildString {
            append(baseUrl).append("/api/v1/attachment/").append(enc(guid)).append("/download")
            append("?password=").append(enc(password))
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000 // an original can be several megabytes on a slow link
        }
        val code = conn.responseCode
        if (code !in 200..299) throw ApiException(code, "attachment download failed ($code)")
        conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun requestChecked(method: String, path: String, body: JSONObject?, what: String, extraQuery: String? = null): String {
        val (code, text) = request(method, path, body, extraQuery)
        if (code !in 200..299) throw ApiException(code, "$what failed ($code)")
        return text
    }

    private fun dataObject(response: String): JSONObject? = runCatching { JSONObject(response).optJSONObject("data") }.getOrNull()

    private fun request(method: String, path: String, body: JSONObject?, extraQuery: String? = null): Pair<Int, String> {
        val url = buildString {
            append(baseUrl).append(path)
            append("?password=").append(enc(password))
            if (extraQuery != null) append("&").append(extraQuery)
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        // No disconnect(): closing the stream after a full read returns the socket to the keep-alive pool,
        // while disconnect() evicts it and every call over the tunnel would pay a fresh TLS handshake.
        if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
    }
}
