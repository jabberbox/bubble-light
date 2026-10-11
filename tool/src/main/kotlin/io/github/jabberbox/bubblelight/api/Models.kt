package io.github.jabberbox.bubblelight.api

import io.github.jabberbox.bubblelight.data.MessageEntity

/** The chat object embedded in a message, which is all a sweep of messages knows about each chat. */
data class ChatInfo(
    val guid: String,
    val displayName: String,
    val participants: List<String>,
    val isGroup: Boolean,
)

/** A message as the server sent it, with the chat it belongs to when the payload carried one. */
data class ParsedMessage(val message: MessageEntity, val chat: ChatInfo?)

/**
 * What `GET /server/info` says. [privateApiReady] means tapbacks, read receipts and typing are available.
 * [account] is the Apple account the Mac is signed in to, which tells two addresses of one server apart from two servers.
 */
data class ServerInfo(val privateApiReady: Boolean, val serverVersion: String?, val account: String? = null)

/** The HTTP status is kept so callers can tell a bad password from an unreachable server. */
class ApiException(val code: Int, message: String) : java.io.IOException(message) {
    val isAuthError: Boolean get() = code == 401 || code == 403
}

/** One entry of the Mac's address book: an address and the name it belongs to. */
data class AddressName(val address: String, val name: String)
