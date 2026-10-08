package io.github.jabberbox.bubblelight.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapbacksTest {
    private fun tap(guid: String, target: String, word: String, at: Long, mine: Boolean = false, from: String? = "+16081111111") =
        MessageEntity(
            guid = guid, chatGuid = "c", text = "", dateCreated = at, fromMe = mine,
            sender = if (mine) null else from, reaction = word, reactionTarget = target,
        )

    @Test
    fun foldsReactionsOntoTheirTargets() {
        val folded = Tapbacks.fold(
            listOf(tap("r1", "M1", "love", 1), tap("r2", "M1", "laugh", 2, mine = true), tap("r3", "M2", "like", 3)),
        )
        assertEquals(listOf(ReactionType.LOVE, ReactionType.LAUGH), folded.getValue("M1").map { it.type })
        assertEquals(listOf(ReactionType.LIKE), folded.getValue("M2").map { it.type })
    }

    @Test
    fun aRemovalCancelsTheEarlierReactionFromTheSamePerson() {
        val folded = Tapbacks.fold(listOf(tap("r1", "M1", "love", 1), tap("r2", "M1", "-love", 2)))
        assertNull(folded["M1"])
    }

    @Test
    fun aRemovalOnlyCancelsThatPersonsReaction() {
        val folded = Tapbacks.fold(
            listOf(
                tap("r1", "M1", "love", 1, from = "+16081111111"),
                tap("r2", "M1", "love", 2, from = "+16082222222"),
                tap("r3", "M1", "-love", 3, from = "+16081111111"),
            ),
        )
        assertEquals(listOf("+16082222222"), folded.getValue("M1").map { it.sender })
    }

    @Test
    fun aRemovalThenANewReactionLeavesTheNewOne() {
        val folded = Tapbacks.fold(
            listOf(tap("r1", "M1", "love", 1), tap("r2", "M1", "-love", 2), tap("r3", "M1", "love", 3)),
        )
        assertEquals(1, folded.getValue("M1").size)
    }

    @Test
    fun theSameSenderInADifferentNumberFormatCountsAsOnePerson() {
        val folded = Tapbacks.fold(
            listOf(tap("r1", "M1", "like", 1, from = "+16082646591"), tap("r2", "M1", "like", 2, from = "608-264-6591")),
        )
        assertEquals(1, folded.getValue("M1").size)
    }

    @Test
    fun rowsThatAreNotTapbacksAreIgnored() {
        val plain = MessageEntity("p", "c", "hi", 1, false, "+1")
        assertEquals(emptyMap<String, List<Tapback>>(), Tapbacks.fold(listOf(plain)))
    }

    @Test
    fun aOneToOneSummaryIsJustTheWordUntilIReactToo() {
        val theirs = listOf(Tapback(ReactionType.LOVE, false, "+16081111111"))
        assertEquals("Loved", Tapbacks.summary(theirs, isGroup = false) { "Chris" })
        val both = theirs + Tapback(ReactionType.LOVE, true, null)
        assertEquals("Loved by Chris, You", Tapbacks.summary(both, isGroup = false) { "Chris" })
    }

    @Test
    fun aGroupSummaryNamesWhoReactedAndKeepsAFixedOrder() {
        val taps = listOf(
            Tapback(ReactionType.LAUGH, false, "+16082222222"),
            Tapback(ReactionType.LOVE, false, "+16081111111"),
            Tapback(ReactionType.LOVE, true, null),
        )
        val names = mapOf("+16081111111" to "Chris", "+16082222222" to "Matt")
        assertEquals("Loved by Chris, You  Laughed by Matt", Tapbacks.summary(taps, isGroup = true) { names.getValue(it) })
    }

    @Test
    fun normalizeKeepsOnlyTheSixTapbackWords() {
        assertEquals("love", ReactionType.normalize("love"))
        assertEquals("-question", ReactionType.normalize("-question"))
        assertNull(ReactionType.normalize("sticker"))
        assertNull(ReactionType.normalize(""))
        assertNull(ReactionType.normalize(null))
    }
}
