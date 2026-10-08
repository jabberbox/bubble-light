package io.github.jabberbox.bubblelight.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationsTest {
    private fun chat(
        guid: String,
        people: List<String>,
        name: String = "",
        group: Boolean = false,
        last: Long = 100,
        real: Long = last,
        unreadTs: Long = 0,
        read: Long = 0,
    ) = ChatEntity(guid, name, ChatEntity.joinAddresses(people), group, last, real, unreadTs, read)

    @Test
    fun ordersByNewestActivityAndSkipsChatsWithNoMessages() {
        val list = Conversations.collapse(
            listOf(
                chat("a", listOf("+16081111111"), last = 10),
                chat("b", listOf("+16082222222"), last = 30),
                chat("c", listOf("+16083333333"), last = 0),
            ),
        )
        assertEquals(listOf("b", "a"), list.map { it.guid })
    }

    @Test
    fun aForkedGroupIsOneConversationSendingToTheRoomWithTheNewestRealMessage() {
        val people = listOf("+16081111111", "+16082222222")
        val list = Conversations.collapse(
            listOf(
                // The old room got the newest tapback, but the new room got the newest real message.
                chat("old", people, "Fans", group = true, last = 500, real = 100),
                chat("new", people, "Fans", group = true, last = 300, real = 300),
            ),
        )
        val c = list.single()
        assertEquals("new", c.guid)
        assertEquals(listOf("new", "old"), c.guids)
        assertEquals(500L, c.lastDate)
    }

    @Test
    fun groupsWithTheSameMembersButDifferentNamesStayApart() {
        val people = listOf("+16081111111", "+16082222222")
        val list = Conversations.collapse(
            listOf(chat("a", people, "Fans", group = true), chat("b", people, "Work", group = true)),
        )
        assertEquals(2, list.size)
    }

    @Test
    fun aPersonOnIMessageAndSmsIsOneConversation() {
        val list = Conversations.collapse(
            listOf(
                chat("iMessage;-;+16082646591", listOf("+16082646591"), last = 50),
                chat("SMS;-;6082646591", listOf("608-264-6591"), last = 90),
            ),
        )
        val c = list.single()
        assertEquals("SMS;-;6082646591", c.guid)
        assertEquals(2, c.guids.size)
    }

    @Test
    fun differentPeopleAreNeverMerged() {
        val list = Conversations.collapse(
            listOf(chat("a", listOf("+16081111111")), chat("b", listOf("+16082222222"))),
        )
        assertEquals(2, list.size)
    }

    @Test
    fun aChatWithNoKnownMembersKeepsItsOwnRow() {
        val list = Conversations.collapse(listOf(chat("a", emptyList()), chat("b", emptyList())))
        assertEquals(2, list.size)
    }

    @Test
    fun aDotShowsWhileAMessageIsNewerThanWhatWasReadHere() {
        assertTrue(chat("a", listOf("+16081111111"), unreadTs = 50, read = 10).unread)
        assertFalse(chat("a", listOf("+16081111111"), unreadTs = 50, read = 50).unread)
        assertFalse(chat("a", listOf("+16081111111"), unreadTs = 0, read = 0).unread)
    }

    @Test
    fun aConversationIsUnreadIfAnyOfItsRoomsIs() {
        val people = listOf("+16081111111", "+16082222222")
        val list = Conversations.collapse(
            listOf(
                chat("old", people, "Fans", group = true, unreadTs = 40, read = 0),
                chat("new", people, "Fans", group = true),
            ),
        )
        assertTrue(list.single().unread)
    }
}
