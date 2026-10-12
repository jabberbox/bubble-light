package io.github.jabberbox.bubblelight.ui

/** Cuts a long message down to the part around what was searched for. */
object Snippets {
    /**
     * [text] on one line, trimmed to about [width] characters around the first match of [needle], with an ellipsis
     * where it was cut. A text that already fits, or one without the needle, starts from its beginning.
     */
    fun around(text: String, needle: String, width: Int = 120): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= width) return flat
        val at = flat.indexOf(needle.trim(), ignoreCase = true).takeIf { it >= 0 } ?: 0
        // Keep a little context before the match so it is not the very first thing read.
        var start = (at - LEAD).coerceAtLeast(0).coerceAtMost(flat.length - width)
        var end = (start + width).coerceAtMost(flat.length)
        // Begin and end on whole words: drop a word the cut went through, when a break is close by.
        if (start > 0 && flat[start - 1] != ' ') {
            val space = flat.indexOf(' ', start)
            if (space in start..(start + WORD_SLACK)) start = space + 1
        }
        if (end < flat.length && flat[end] != ' ') {
            val space = flat.lastIndexOf(' ', end)
            if (space >= end - WORD_SLACK && space > start) end = space
        }
        return (if (start > 0) "…" else "") + flat.substring(start, end).trim() + (if (end < flat.length) "…" else "")
    }

    private const val LEAD = 30
    private const val WORD_SLACK = 15
}
