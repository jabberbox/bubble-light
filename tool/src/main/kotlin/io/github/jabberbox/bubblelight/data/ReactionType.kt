package io.github.jabberbox.bubblelight.data

/**
 * The six iMessage tapbacks. The server reports them as a word (`love`, or `-love` when one is taken back)
 * in `associatedMessageType`, and takes the same words to send one.
 */
enum class ReactionType(val word: String, val label: String) {
    LOVE("love", "Loved"),
    LIKE("like", "Liked"),
    DISLIKE("dislike", "Disliked"),
    LAUGH("laugh", "Laughed"),
    EMPHASIZE("emphasize", "Emphasized"),
    QUESTION("question", "Questioned");

    companion object {
        fun fromWord(word: String): ReactionType? = entries.firstOrNull { it.word == word }

        /**
         * The server's word if it names a tapback, kept with its leading `-` when it is a removal, else null.
         * Stickers and other associated messages are not tapbacks and stay ordinary rows.
         */
        fun normalize(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return raw.takeIf { fromWord(it.removePrefix("-")) != null }
        }
    }
}

/** A tapback folded onto the message it targets. [sender] is the reactor's address, null when it is mine. */
data class Tapback(val type: ReactionType, val fromMe: Boolean, val sender: String?)

object Tapbacks {
    /**
     * Folds tapback rows onto their targets, keyed by target guid. Rows are applied oldest first so a
     * removal cancels the matching earlier reaction, and each person has at most one of each type.
     */
    fun fold(rows: List<MessageEntity>): Map<String, List<Tapback>> {
        val state = LinkedHashMap<String, LinkedHashMap<Triple<Boolean, String?, ReactionType>, Tapback>>()
        for (row in rows.sortedBy { it.dateCreated }) {
            val raw = row.reaction ?: continue
            val target = row.reactionTarget ?: continue
            val type = ReactionType.fromWord(raw.removePrefix("-")) ?: continue
            val who = Triple(row.fromMe, if (row.fromMe) null else row.sender?.let(Contacts::key), type)
            val forTarget = state.getOrPut(target) { LinkedHashMap() }
            if (raw.startsWith("-")) forTarget.remove(who) else forTarget[who] = Tapback(type, row.fromMe, row.sender)
        }
        return state.mapValues { it.value.values.toList() }.filterValues { it.isNotEmpty() }
    }

    /**
     * A compact caption such as "Loved" in a one-to-one chat, or "Loved by Chris, You" in a group. Types are
     * listed in a fixed order so the line does not reshuffle as reactions arrive.
     */
    fun summary(taps: List<Tapback>, isGroup: Boolean, nameOf: (String) -> String): String =
        ReactionType.entries.mapNotNull { type ->
            val of = taps.filter { it.type == type }
            if (of.isEmpty()) return@mapNotNull null
            // In a one-to-one chat a bare "Loved" is the other person's, so name the reactors once I am one.
            if (!isGroup && of.none { it.fromMe }) return@mapNotNull type.label
            val names = of.map { if (it.fromMe) "You" else it.sender?.let(nameOf) ?: "Someone" }
            "${type.label} by ${names.joinToString(", ")}"
        }.joinToString("  ")
}
