package io.github.jabberbox.bubblelight.ui

import io.github.jabberbox.bubblelight.data.AttachmentInfo
import io.github.jabberbox.bubblelight.data.AttachmentJson
import io.github.jabberbox.bubblelight.data.Contacts
import io.github.jabberbox.bubblelight.data.MessageEntity
import io.github.jabberbox.bubblelight.data.MessageState
import io.github.jabberbox.bubblelight.data.ReactionType
import io.github.jabberbox.bubblelight.data.Tapback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFormatTest {
    private val contacts = Contacts.from(listOf("+16081111111" to "Chris Campise"))
    private val time: (Long) -> String = { "T$it" }

    private fun message(
        guid: String = "M",
        text: String = "hi",
        mine: Boolean = false,
        attachments: List<AttachmentInfo> = emptyList(),
        state: Int = MessageState.OK,
        error: Int = 0,
        delivered: Long = 0,
        read: Long = 0,
        itemType: Int = 0,
        groupAction: Int = 0,
        groupTitle: String? = null,
    ) = MessageEntity(
        guid = guid, chatGuid = "c", text = text, dateCreated = 5, fromMe = mine,
        sender = if (mine) null else "+16081111111", attachments = AttachmentJson.encode(attachments),
        dateDelivered = delivered, dateRead = read, itemType = itemType, groupActionType = groupAction,
        groupTitle = groupTitle, error = error, state = state,
    )

    private fun ctx(group: Boolean = false, statusFor: String? = null, taps: Map<String, List<Tapback>> = emptyMap()) =
        ThreadContext(group, contacts, taps, statusFor)

    @Test
    fun aPlainMessageShowsItsTextAndTime() {
        val ui = MessageFormat.toUi(message(), ctx(), time)!!
        assertEquals("hi", ui.text)
        assertEquals("T5", ui.time)
        assertFalse(ui.mine)
        assertFalse(ui.dim)
    }

    @Test
    fun inAGroupTheFullSenderNameGetsItsOwnLineForOthersOnly() {
        val theirs = MessageFormat.toUi(message(), ctx(group = true), time)!!
        assertEquals("Chris Campise", theirs.sender)
        assertEquals("T5", theirs.time)
        assertNull(MessageFormat.toUi(message(mine = true), ctx(group = true), time)!!.sender)
        assertNull(MessageFormat.toUi(message(), ctx(group = false), time)!!.sender)
    }

    @Test
    fun imagesBecomePhotosAndOtherFilesBecomeLabels() {
        val ui = MessageFormat.toUi(
            message(
                text = "",
                attachments = listOf(
                    AttachmentInfo("A1", "image/jpeg", "a.jpg", 10, 20),
                    AttachmentInfo("A2", "video/quicktime", "b.mov"),
                ),
            ),
            ctx(), time,
        )!!
        assertEquals(listOf("A1"), ui.photos.map { it.guid })
        assertEquals("[Video]", ui.text)
    }

    @Test
    fun aCaptionStaysWithItsPhoto() {
        val ui = MessageFormat.toUi(message(text = "look", attachments = listOf(AttachmentInfo("A1", "image/png"))), ctx(), time)!!
        assertEquals("look", ui.text)
        assertEquals(1, ui.photos.size)
    }

    @Test
    fun aRowWithNothingToShowIsSkipped() {
        assertNull(MessageFormat.toUi(message(text = ""), ctx(), time))
    }

    @Test
    fun aMessageStillSendingIsDim() {
        val ui = MessageFormat.toUi(message(mine = true, state = MessageState.SENDING), ctx(), time)!!
        assertTrue(ui.dim)
        assertFalse(ui.failed)
    }

    @Test
    fun aPhotoStillUploadingSaysSoAndAFailedOneCanBeRetried() {
        val photo = listOf(AttachmentInfo("temp-1-photo", "image/jpeg", "IMG.jpg", 4, 3))
        val sending = MessageFormat.toUi(message(text = "", mine = true, attachments = photo, state = MessageState.SENDING), ctx(), time)!!
        assertEquals("(sending...)", sending.text)
        assertEquals(1, sending.photos.size)
        assertTrue(sending.dim)
        val failed = MessageFormat.toUi(message(text = "", mine = true, attachments = photo, state = MessageState.FAILED), ctx(), time)!!
        assertEquals("FAILED TO SEND", failed.time)
        assertTrue(failed.failed)
        // A plain text message that is still sending needs no note.
        assertEquals("hi", MessageFormat.toUi(message(mine = true, state = MessageState.SENDING), ctx(), time)!!.text)
    }

    @Test
    fun aFailedSendCanBeRetried() {
        val ui = MessageFormat.toUi(message(mine = true, state = MessageState.FAILED), ctx(), time)!!
        assertTrue(ui.failed)
        assertEquals("FAILED TO SEND", ui.time)
        assertEquals("hi", ui.text)
    }

    @Test
    fun aMessageTheServerCouldNotDeliverSaysSo() {
        val ui = MessageFormat.toUi(message(mine = true, error = 22), ctx(), time)!!
        assertTrue(ui.failed)
        assertEquals("NOT DELIVERED", ui.time)
        assertEquals("hi", ui.text)
    }

    @Test
    fun deliveryStatusOnlyOnTheDesignatedMessage() {
        val read = message(guid = "A", mine = true, delivered = 6, read = 7)
        assertEquals("Read", MessageFormat.toUi(read, ctx(statusFor = "A"), time)!!.status)
        assertNull(MessageFormat.toUi(read, ctx(statusFor = "B"), time)!!.status)
        assertEquals("Delivered", MessageFormat.status(message(mine = true, delivered = 6)))
        assertNull(MessageFormat.status(message(mine = true)))
        assertNull(MessageFormat.status(message(mine = false, read = 9)))
    }

    @Test
    fun tapbacksShowAsOneCompactLine() {
        val taps = mapOf("M" to listOf(Tapback(ReactionType.LOVE, false, "+16081111111")))
        assertEquals("Loved", MessageFormat.toUi(message(), ctx(taps = taps), time)!!.reactions)
        assertEquals("Loved by Chris", MessageFormat.toUi(message(), ctx(group = true, taps = taps), time)!!.reactions)
    }

    @Test
    fun groupEventsBecomeCenteredLines() {
        val rename = MessageFormat.toUi(message(text = "", itemType = 2, groupTitle = "Fans"), ctx(group = true), time)!!
        assertTrue(rename.event)
        assertEquals("Chris named the conversation \"Fans\"", rename.text)
        val left = message(text = "", mine = true, itemType = 3, groupAction = 0)
        assertEquals("You left the conversation", MessageFormat.toUi(left, ctx(group = true), time)!!.text)
        assertNull(MessageFormat.toUi(message(text = "", itemType = 99), ctx(group = true), time))
    }

    @Test
    fun stampsMatchTheStockApp() {
        val fmt = MessageFormat.formatter(java.util.Locale.US)
        val ts = java.util.GregorianCalendar(2026, java.util.Calendar.JULY, 18, 20, 22).timeInMillis
        assertEquals("Jul 18 8:22 PM", fmt(ts))
    }
}
