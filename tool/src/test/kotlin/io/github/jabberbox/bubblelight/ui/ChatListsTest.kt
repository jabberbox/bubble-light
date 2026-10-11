package io.github.jabberbox.bubblelight.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListsTest {
    private fun row(id: String, known: Boolean = true, pinned: Boolean = false, unread: Boolean = false) =
        ChatRow(id = id, name = id, unread = unread, known = known, pinned = pinned)

    @Test
    fun pinnedChatsComeFirstThenPeopleYouKnow() {
        val all = listOf(row("a"), row("b", pinned = true), row("c"), row("d", pinned = true))
        assertEquals(listOf("b", "d", "a", "c"), ChatLists.main(all).map { it.id })
    }

    @Test
    fun strangersAreBehindOneRowAtTheEnd() {
        val all = listOf(row("a"), row("x", known = false), row("b"), row("y", known = false))
        val main = ChatLists.main(all)
        assertEquals(listOf("a", "b", ChatLists.UNKNOWN_ID), main.map { it.id })
        assertTrue(main.last().muted)
        assertEquals(listOf("x", "y"), ChatLists.unknown(all).map { it.id })
    }

    @Test
    fun theRowShowsAnUnreadDotWhenAnyStrangerIsUnread() {
        val quiet = ChatLists.main(listOf(row("x", known = false), row("a")))
        assertFalse(quiet.last().unread)
        val loud = ChatLists.main(listOf(row("x", known = false, unread = true), row("a")))
        assertTrue(loud.last().unread)
    }

    @Test
    fun noStrangersMeansNoRow() {
        assertEquals(listOf("a", "b"), ChatLists.main(listOf(row("a"), row("b"))).map { it.id })
    }

    @Test
    fun aPinnedStrangerStaysOnTheMainList() {
        val all = listOf(row("x", known = false, pinned = true), row("a"))
        assertEquals(listOf("x", "a"), ChatLists.main(all).map { it.id })
        assertTrue(ChatLists.unknown(all).isEmpty())
    }
}
