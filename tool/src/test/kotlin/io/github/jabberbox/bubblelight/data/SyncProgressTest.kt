package io.github.jabberbox.bubblelight.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProgressTest {
    private val epsilon = 0.0001f

    @Test
    fun eachStepStartsWhereThePreviousOneEnded() {
        assertEquals(0f, SyncProgress.at(SyncProgress.RECENT_MESSAGES, 0f).fraction, epsilon)
        assertEquals(0.25f, SyncProgress.at(SyncProgress.RECENT_MESSAGES, 1f).fraction, epsilon)
        assertEquals(0.25f, SyncProgress.at(SyncProgress.CHATS, 0f).fraction, epsilon)
        assertEquals(0.90f, SyncProgress.at(SyncProgress.CHATS, 1f).fraction, epsilon)
        assertEquals(0.90f, SyncProgress.at(SyncProgress.CONTACTS, 0f).fraction, epsilon)
        assertEquals(1f, SyncProgress.at(SyncProgress.CONTACTS, 1f).fraction, epsilon)
    }

    @Test
    fun halfwayThroughTheChatsIsAboutFiftyEightPercent() {
        val progress = SyncProgress.at(SyncProgress.CHATS, 0.5f)
        assertEquals(0.575f, progress.fraction, epsilon)
        assertEquals(57, progress.percent)
    }

    @Test
    fun progressWithinAStepIsClamped() {
        assertEquals(0.25f, SyncProgress.at(SyncProgress.CHATS, -3f).fraction, epsilon)
        assertEquals(0.90f, SyncProgress.at(SyncProgress.CHATS, 7f).fraction, epsilon)
    }

    @Test
    fun theBarNeverGoesBackwardsAsOneStepFills() {
        val values = (0..20).map { SyncProgress.at(SyncProgress.CHATS, it / 20f).fraction }
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun theLabelNamesTheStep() {
        assertEquals("Recent messages", SyncProgress.at(SyncProgress.RECENT_MESSAGES, 0.1f).label)
        assertEquals("Chats", SyncProgress.at(SyncProgress.CHATS, 0.1f).label)
        assertEquals("Contacts", SyncProgress.at(SyncProgress.CONTACTS, 0.1f).label)
    }
}
