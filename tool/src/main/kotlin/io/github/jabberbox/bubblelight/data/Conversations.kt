package io.github.jabberbox.bubblelight.data

/**
 * What the list shows: one or more chat rooms presented as a single conversation. [guid] is the room to send
 * into, and [guids] is every room whose messages belong in the thread.
 */
data class Conversation(
    val guid: String,
    val guids: List<String>,
    val displayName: String,
    val participants: List<String>,
    val isGroup: Boolean,
    val lastDate: Long,
    val unread: Boolean,
)

object Conversations {
    /**
     * Merges rooms that are really one conversation, newest first:
     *  - a group that iMessage forked into several rooms (same name and same members, different guid);
     *  - one person reached over both iMessage and SMS, which are separate rooms on the server but a single
     *    thread on the phone.
     *
     * The send target is the room that most recently received a real message. The newest message of any kind
     * is not safe for that: a tapback can land in a dead old room, and sending there fails.
     */
    fun collapse(chats: List<ChatEntity>): List<Conversation> {
        val groups = LinkedHashMap<String, MutableList<ChatEntity>>()
        for (chat in chats) {
            if (chat.lastDate <= 0L) continue
            groups.getOrPut(identity(chat)) { mutableListOf() }.add(chat)
        }
        return groups.values
            .map { rooms ->
                val live = rooms.sortedWith(compareByDescending<ChatEntity> { it.lastRealDate }.thenByDescending { it.lastDate })
                val first = live.first()
                Conversation(
                    guid = first.guid,
                    guids = live.map { it.guid },
                    displayName = first.displayName,
                    participants = first.participantList,
                    isGroup = first.isGroup,
                    lastDate = rooms.maxOf { it.lastDate },
                    unread = rooms.any { it.unread },
                )
            }
            .sortedByDescending { it.lastDate }
    }

    /** Rooms with the same identity are shown as one conversation. */
    private fun identity(chat: ChatEntity): String {
        val people = chat.participantList
        return when {
            chat.isGroup && people.isNotEmpty() -> "g|${chat.displayName}|${people.map(Contacts::key).sorted().joinToString(",")}"
            !chat.isGroup && people.size == 1 -> "d|${Contacts.key(people[0])}"
            else -> "r|${chat.guid}"
        }
    }
}
