package io.github.jabberbox.bubblelight.api

import io.github.jabberbox.bubblelight.data.AttachmentInfo
import io.github.jabberbox.bubblelight.data.AttachmentJson
import io.github.jabberbox.bubblelight.data.MessageEntity
import io.github.jabberbox.bubblelight.data.ReactionType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Decodes BlueBubbles payloads. The REST responses and the socket events share one message shape, so both
 * go through here. Pure Kotlin over `org.json`, so it is tested on the JVM against fixtures.
 *
 * Based on craigeley/chat (MIT), https://github.com/craigeley/chat.
 */
object BlueBubblesJson {
    /** iMessage stands a placeholder character in for each attachment inside the text. */
    private const val OBJECT_REPLACEMENT = "￼"

    /** `optString` returns the literal "null" for an explicit JSON null, so read strings through this. */
    private fun JSONObject.str(name: String): String? =
        if (isNull(name)) null else optString(name, "").takeIf { it.isNotBlank() }

    fun parseMessage(o: JSONObject, chatGuid: String? = null): ParsedMessage? {
        val chat = o.optJSONArray("chats")?.optJSONObject(0)?.let(::parseChat)
        val room = chatGuid ?: chat?.guid ?: return null
        val guid = o.str("guid") ?: return null
        val attachments = parseAttachments(o)
        val text = (o.str("text") ?: "").replace(OBJECT_REPLACEMENT, "").trim()
        val reaction = ReactionType.normalize(o.str("associatedMessageType"))
        val message = MessageEntity(
            guid = guid,
            chatGuid = room,
            text = text,
            dateCreated = o.optLong("dateCreated", 0L),
            fromMe = o.optBoolean("isFromMe", false),
            sender = o.optJSONObject("handle")?.str("address"),
            attachments = AttachmentJson.encode(attachments),
            dateDelivered = o.optLong("dateDelivered", 0L),
            dateRead = o.optLong("dateRead", 0L),
            itemType = o.optInt("itemType", 0),
            groupTitle = o.str("groupTitle"),
            groupActionType = o.optInt("groupActionType", 0),
            error = o.optInt("error", 0),
            reaction = reaction,
            reactionTarget = if (reaction != null) reactionTarget(o.str("associatedMessageGuid")) else null,
            tempGuid = o.str("tempGuid"),
        )
        return ParsedMessage(message, chat)
    }

    /** iMessage prefixes the target of a tapback with `p:<part>/` or `bp:`. */
    fun reactionTarget(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val slash = raw.indexOf('/')
        if (slash >= 0) return raw.substring(slash + 1)
        if (raw.startsWith("bp:")) return raw.substring(3)
        return raw
    }

    fun parseChat(chat: JSONObject): ChatInfo? {
        val guid = chat.str("guid") ?: return null
        val participants = chat.optJSONArray("participants")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.str("address") }
        } ?: emptyList()
        // One-to-one chats can come back with no participants, but then the chat identifier is the other
        // person's address. Group identifiers start with "chat" and are not addresses.
        val resolved = participants.ifEmpty {
            chat.str("chatIdentifier")?.takeIf { !it.startsWith("chat") }?.let { listOf(it) } ?: emptyList()
        }
        return ChatInfo(
            guid = guid,
            displayName = chat.str("displayName").orEmpty(),
            participants = resolved,
            isGroup = chat.optInt("style") == 43, // 43 is a group, 45 a one-on-one chat
        )
    }

    private fun parseAttachments(o: JSONObject): List<AttachmentInfo> {
        val arr = o.optJSONArray("attachments") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            val guid = a.str("guid") ?: return@mapNotNull null
            val name = a.str("transferName")
            // Link previews ride along as a metadata blob, not a file. The link is already in the text.
            if (name?.endsWith(".pluginPayloadAttachment", ignoreCase = true) == true) return@mapNotNull null
            AttachmentInfo(
                guid = guid,
                mimeType = a.str("mimeType"),
                name = name,
                width = a.optInt("width", 0),
                height = a.optInt("height", 0),
            )
        }
    }

    fun parseMessages(response: String, chatGuid: String? = null): List<ParsedMessage> {
        val data = JSONObject(response).optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { parseMessage(data.getJSONObject(it), chatGuid) }
    }

    /** The address book flattened to address and name pairs, one per phone number or email. */
    fun parseContacts(response: String): List<AddressName> {
        val data = JSONObject(response).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<AddressName>()
        for (i in 0 until data.length()) {
            val c = data.getJSONObject(i)
            val name = c.str("displayName") ?: listOfNotNull(c.str("firstName"), c.str("lastName")).joinToString(" ")
            if (name.isBlank()) continue
            for (field in listOf("phoneNumbers", "emails")) {
                val arr = c.optJSONArray(field) ?: continue
                for (j in 0 until arr.length()) {
                    arr.optJSONObject(j)?.str("address")?.let { out.add(AddressName(it, name)) }
                }
            }
        }
        return out
    }
}
