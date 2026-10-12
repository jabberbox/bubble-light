package io.github.jabberbox.bubblelight.ui

import io.github.jabberbox.bubblelight.data.ChatRepository.SyncReport

/** The line shown in the list's top band after a tap on it asks for a sync. */
object SyncNote {
    const val WORKING = "Syncing..."

    fun of(report: SyncReport): String = when {
        report.busy -> "Already syncing"
        !report.ok -> "Could not reach the server"
        report.fresh == 1 -> "1 new message"
        report.fresh > 1 -> "${report.fresh} new messages"
        else -> "Up to date"
    }
}
