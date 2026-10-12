package io.github.jabberbox.bubblelight.ui

import io.github.jabberbox.bubblelight.data.ChatRepository.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncNoteTest {
    @Test
    fun aFinishedSyncWithNothingNewIsUpToDate() {
        assertEquals("Up to date", SyncNote.of(SyncReport(ok = true)))
    }

    @Test
    fun newMessagesAreCounted() {
        assertEquals("1 new message", SyncNote.of(SyncReport(ok = true, fresh = 1)))
        assertEquals("4 new messages", SyncNote.of(SyncReport(ok = true, fresh = 4)))
    }

    @Test
    fun aFailedSyncSaysTheServerWasNotReached() {
        assertEquals("Could not reach the server", SyncNote.of(SyncReport(ok = false)))
    }

    @Test
    fun aSyncAlreadyRunningSaysSo() {
        assertEquals("Already syncing", SyncNote.of(SyncReport(busy = true)))
    }
}
