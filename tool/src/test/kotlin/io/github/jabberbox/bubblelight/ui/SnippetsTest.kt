package io.github.jabberbox.bubblelight.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetsTest {
    @Test
    fun aShortTextIsKeptWhole() {
        assertEquals("see you at noon", Snippets.around("see you at noon", "noon"))
    }

    @Test
    fun newlinesAndRunsOfSpacesBecomeOneSpace() {
        assertEquals("one two three", Snippets.around("one\n\ntwo   three", "two"))
    }

    @Test
    fun aLongTextIsCutAroundTheMatchWithEllipses() {
        val text = "a".repeat(200) + " the address is 12 Oak Street " + "b".repeat(200)
        val snippet = Snippets.around(text, "oak street", width = 80)
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("Oak Street", ignoreCase = true))
        assertTrue(snippet.length <= 82)
    }

    @Test
    fun aMatchNearTheStartHasNoLeadingEllipsis() {
        val snippet = Snippets.around("hello there " + "x".repeat(300), "hello", width = 60)
        assertFalse(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun aMatchAtTheEndHasNoTrailingEllipsis() {
        val snippet = Snippets.around("x".repeat(300) + " find me", "find me", width = 60)
        assertTrue(snippet.startsWith("…"))
        assertFalse(snippet.endsWith("…"))
        assertTrue(snippet.endsWith("find me"))
    }

    @Test
    fun theCutNeverSplitsAWord() {
        val text = "alpha bravo charlie delta echo ".repeat(20) + "needle " + "foxtrot golf hotel india juliet ".repeat(20)
        val words = setOf("alpha", "bravo", "charlie", "delta", "echo", "needle", "foxtrot", "golf", "hotel", "india", "juliet")
        for (width in listOf(50, 61, 73, 90)) {
            val snippet = Snippets.around(text, "needle", width = width).trim('…')
            for (word in snippet.split(' ').filter { it.isNotEmpty() }) {
                assertTrue("\"$word\" is not a whole word at width $width", word in words)
            }
        }
    }

    @Test
    fun noMatchStartsFromTheBeginning() {
        val snippet = Snippets.around("start " + "y".repeat(300), "zzz", width = 40)
        assertTrue(snippet.startsWith("start"))
    }
}
