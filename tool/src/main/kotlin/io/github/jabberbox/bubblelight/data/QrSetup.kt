package io.github.jabberbox.bubblelight.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** The address and password needed to connect to a server. */
data class ServerSetup(val url: String, val password: String)

/**
 * Reads the setup QR code a BlueBubbles server shows. The code holds a JSON array: the password first, then the
 * server address, then Firebase settings for the official apps, which this tool has no use for and ignores.
 */
object QrSetup {
    /** The setup the code describes, or null when [text] is not a BlueBubbles setup code. Nothing is logged. */
    fun parse(text: String): ServerSetup? {
        val array = runCatching { Json.parseToJsonElement(text.trim()) }.getOrNull() as? JsonArray ?: return null
        if (array.size < 2) return null
        val password = (array[0] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return null
        val address = (array[1] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val url = Store.normalizeUrl(address) ?: return null
        return ServerSetup(url, password)
    }
}
