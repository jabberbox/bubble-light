package io.github.jabberbox.bubblelight.ui

import io.github.jabberbox.bubblelight.data.Contacts
import io.github.jabberbox.bubblelight.data.MessageEntity
import io.github.jabberbox.bubblelight.data.MessageState
import io.github.jabberbox.bubblelight.data.Tapback
import io.github.jabberbox.bubblelight.data.Tapbacks
import io.github.jabberbox.bubblelight.media.PhotoRef
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How one message is shown in a thread. */
data class UiMessage(
    val id: String,
    /** The stamp above the message, with the sender's name in front in a group. */
    val time: String,
    val text: String,
    val mine: Boolean,
    /** Shown in the secondary gray: still sending, or not delivered. */
    val dim: Boolean,
    /** Tapping it sends it again. */
    val failed: Boolean,
    val photos: List<PhotoRef> = emptyList(),
    /** Tapbacks on this message as one compact line, null when there are none. */
    val reactions: String? = null,
    /** "Delivered" or "Read", only ever on my newest message. */
    val status: String? = null,
    /** A group system line such as a rename, shown centered and dimmed rather than as a turn. */
    val event: Boolean = false,
)

/** What formatting a thread needs to know beyond the message itself. */
class ThreadContext(
    val isGroup: Boolean,
    val contacts: Contacts,
    val tapbacks: Map<String, List<Tapback>>,
    /** The one message that gets a delivery status line, or null. */
    val statusFor: String?,
)

/** Pure display rules, kept out of composables so they can be tested. */
object MessageFormat {
    /** The line for a group system row, or null for one that is not shown. [actor] is "You" or a first name. */
    fun eventText(m: MessageEntity, actor: String): String? = when {
        m.itemType == 2 ->
            m.groupTitle?.takeIf { it.isNotBlank() }?.let { "$actor named the conversation \"$it\"" } ?: "$actor removed the conversation name"
        m.itemType == 1 && m.groupActionType == 0 -> "$actor added someone to the conversation"
        m.itemType == 1 && m.groupActionType == 1 -> "$actor removed someone from the conversation"
        m.itemType == 3 && m.groupActionType == 0 -> "$actor left the conversation"
        m.itemType == 3 -> "$actor changed the group photo"
        else -> null
    }

    /** "Read" once the recipient has read it, else "Delivered" once it has arrived, else nothing. */
    fun status(m: MessageEntity): String? = when {
        !m.fromMe || m.state != MessageState.OK || m.error != 0 -> null
        m.dateRead > 0 -> "Read"
        m.dateDelivered > 0 -> "Delivered"
        else -> null
    }

    /** Null for a row with nothing to show, such as a sticker or an empty message. */
    fun toUi(m: MessageEntity, ctx: ThreadContext, formatTime: (Long) -> String): UiMessage? {
        val sender = m.sender?.let(ctx.contacts::sender)
        if (m.isGroupEvent) {
            val line = eventText(m, if (m.fromMe) "You" else sender ?: "Someone") ?: return null
            return UiMessage(id = m.guid, time = formatTime(m.dateCreated), text = line, mine = false, dim = true, failed = false, event = true)
        }
        val attachments = m.attachmentList
        val photos = attachments.filter { it.isImage }.map { PhotoRef(it.guid, it.width, it.height) }
        val others = attachments.filter { !it.isImage }.map { "[${it.typeLabel}]" }
        val lines = listOfNotNull(m.text.ifEmpty { null }) + others
        if (lines.isEmpty() && photos.isEmpty()) return null
        val failed = m.state == MessageState.FAILED || m.error != 0
        val body = lines.joinToString("\n")
        val note = when {
            m.state == MessageState.FAILED -> "not sent, tap to retry"
            m.error != 0 -> "not delivered, tap to retry"
            // A photo takes a while to upload and the picture alone gives no sign it is still going.
            m.state == MessageState.SENDING && photos.isNotEmpty() -> "sending..."
            else -> null
        }
        val time = formatTime(m.dateCreated)
        return UiMessage(
            id = m.guid,
            time = if (!m.fromMe && ctx.isGroup && sender != null) "$sender  $time" else time,
            text = listOfNotNull(body.ifEmpty { null }, note?.let { "($it)" }).joinToString(" "),
            mine = m.fromMe,
            dim = m.state != MessageState.OK || m.error != 0,
            failed = failed,
            photos = photos,
            reactions = ctx.tapbacks[m.guid]?.let { Tapbacks.summary(it, ctx.isGroup, ctx.contacts::sender) }?.ifEmpty { null },
            status = if (m.guid == ctx.statusFor) status(m) else null,
        )
    }

    /** Matches Molly Light's stamps, for example "Jul 18 8:22PM". */
    fun formatter(locale: Locale = Locale.getDefault()): (Long) -> String {
        val format = SimpleDateFormat("MMM d h:mma", locale)
        return { ts -> format.format(Date(ts)) }
    }
}
