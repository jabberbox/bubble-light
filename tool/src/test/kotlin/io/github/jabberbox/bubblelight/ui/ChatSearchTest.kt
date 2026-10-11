package io.github.jabberbox.bubblelight.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchTest {
    // A group titled by initials, as the list shows it, which is found by the full names and numbers inside it.
    private val group = ChatRow("g", "PA, JT", unread = false, haystack = "pa, jt pablo alaniz +16081111111 jason turner +16082222222")

    @Test
    fun everyWordMustAppearAnywhereInTheChat() {
        assertTrue(group.matches("pablo"))
        assertTrue(group.matches("turner pablo"))
        assertTrue(group.matches("  JASON  "))
        assertTrue(group.matches("1111"))
        assertFalse(group.matches("pablo smith"))
    }

    @Test
    fun anEmptySearchMatchesNothing() {
        assertFalse(group.matches(""))
        assertFalse(group.matches("   "))
    }
}
