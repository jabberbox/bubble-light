package io.github.jabberbox.bubblelight.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One chat room as BlueBubbles reports it. A group that iMessage forked into several rooms, or a contact
 * reachable over both iMessage and SMS, has one row per room; [Conversations.collapse] merges them for display.
 */
@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val guid: String,
    /** The group's own name, empty for an unnamed group or a one-to-one chat. */
    val displayName: String,
    /** Handle addresses (phone numbers and emails), sorted and joined by [ADDRESS_SEPARATOR]. */
    val participants: String,
    val isGroup: Boolean,
    /** Newest message of any kind, including tapbacks and group events, which is what orders the list. */
    val lastDate: Long = 0,
    /** Newest message that is not a tapback. Decides which sibling room of a forked chat is the live one. */
    val lastRealDate: Long = 0,
    /** Date of the newest incoming message nobody has read yet, 0 when there is none. */
    val unreadTs: Long = 0,
    /** Newest message time already seen on this device, so the dot clears even when no receipt is sent. */
    val localReadTs: Long = 0,
    /** The server has no messages older than the oldest one stored. */
    val historyExhausted: Boolean = false,
) {
    val participantList: List<String> get() = if (participants.isEmpty()) emptyList() else participants.split(ADDRESS_SEPARATOR)

    val unread: Boolean get() = unreadTs > localReadTs

    companion object {
        const val ADDRESS_SEPARATOR = "\n"

        fun joinAddresses(addresses: List<String>): String = addresses.distinct().sorted().joinToString(ADDRESS_SEPARATOR)
    }
}

object MessageState {
    const val OK = 0
    const val SENDING = 2
    const val FAILED = 3
}

@Serializable
data class AttachmentInfo(
    val guid: String,
    val mimeType: String? = null,
    val name: String? = null,
    val width: Int = 0,
    val height: Int = 0,
) {
    /**
     * A picture. A message just sent over AppleScript can come back before the Mac has worked out the type, so
     * with no type the file name decides.
     */
    val isImage: Boolean
        get() = mimeType?.startsWith("image/") == true || (mimeType == null && name?.substringAfterLast('.', "")?.lowercase() in IMAGE_EXTENSIONS)

    /** A short human type for something that is not shown inline, such as "Video". */
    val typeLabel: String
        get() = when {
            isImage -> "Photo"
            mimeType == null -> "File"
            mimeType.startsWith("video/") -> "Video"
            mimeType.startsWith("audio/") -> "Audio"
            mimeType.contains("vcard") -> "Contact"
            mimeType == "application/pdf" -> "PDF"
            mimeType.startsWith("image/") -> "Photo"
            else -> "File"
        }
}

/** A message, tapback or group event in a chat. Rows with a non-null [reaction] are tapbacks, folded onto their target. */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["chatGuid", "dateCreated"]), Index("reactionTarget"), Index("tempGuid")],
)
data class MessageEntity(
    @PrimaryKey val guid: String,
    val chatGuid: String,
    /** Body text with the attachment placeholder character removed; empty for an attachment-only message. */
    val text: String,
    val dateCreated: Long,
    val fromMe: Boolean,
    /** Sender's handle address, null for my own messages. */
    val sender: String?,
    /** JSON list of [AttachmentInfo], null when there are none. */
    val attachments: String? = null,
    val dateDelivered: Long = 0,
    val dateRead: Long = 0,
    /** Non-zero for group system rows: a rename, someone joining or leaving, a new group photo. */
    val itemType: Int = 0,
    val groupTitle: String? = null,
    val groupActionType: Int = 0,
    /** Non-zero when a message I sent failed to deliver. */
    val error: Int = 0,
    /** Set only on tapbacks: the server's word for it, such as `love` or `-love` for a removal. */
    val reaction: String? = null,
    /** Set only on tapbacks: the guid of the message it targets, without iMessage's part prefix. */
    val reactionTarget: String? = null,
    /** The client id a message was sent with, echoed back by the server so the local copy can be replaced. */
    val tempGuid: String? = null,
    /** [MessageState]: only [MessageState.SENDING] and [MessageState.FAILED] are ever set locally. */
    val state: Int = MessageState.OK,
) {
    val attachmentList: List<AttachmentInfo>
        get() = attachments?.let { runCatching { AttachmentJson.decode(it) }.getOrNull() } ?: emptyList()

    /** A rename, member change or similar row rather than actual speech. */
    val isGroupEvent: Boolean get() = itemType != 0

    val isReaction: Boolean get() = reaction != null
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "heic", "heif", "webp")

object AttachmentJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(list: List<AttachmentInfo>): String? = if (list.isEmpty()) null else json.encodeToString(list)

    fun decode(text: String): List<AttachmentInfo> = json.decodeFromString(text)
}

/** Address book entry, keyed the way [Contacts.key] normalizes an address. */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val key: String,
    val name: String,
    val address: String,
)
