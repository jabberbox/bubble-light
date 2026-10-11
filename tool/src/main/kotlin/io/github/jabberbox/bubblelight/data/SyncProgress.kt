package io.github.jabberbox.bubblelight.data

/**
 * How far a full download has got, for the progress bar. The download has three steps of unequal weight, so a step's
 * own progress is scaled into one overall fraction between 0 and 1.
 */
data class SyncProgress(val label: String, val fraction: Float) {
    val percent: Int get() = (fraction * 100).toInt().coerceIn(0, 100)

    companion object {
        const val RECENT_MESSAGES = 0
        const val CHATS = 1
        const val CONTACTS = 2

        /** The chats are by far the biggest download, so they carry most of the bar. */
        private val weights = floatArrayOf(0.25f, 0.65f, 0.10f)
        private val labels = arrayOf("Recent messages", "Chats", "Contacts")

        /** Progress [within] a step (0 to 1, clamped), placed on the bar. */
        fun at(step: Int, within: Float): SyncProgress {
            val before = weights.take(step).sum()
            return SyncProgress(labels[step], (before + weights[step] * within.coerceIn(0f, 1f)).coerceIn(0f, 1f))
        }
    }
}
