package io.github.jabberbox.bubblelight.data

/** One person's number or email, with the address in the form the server wants for a new chat. */
data class Contact(val name: String, val address: String)

/**
 * Address to name lookup built from the server's address book, which is the Mac's contacts and so the same
 * source iMessage uses. Numbers come in many formats (`+1 (315) 212-2695`, `608-264-6591`, `+16082138014`),
 * so keys are normalized: phone numbers to their last 10 digits, emails to lowercase. That collapses every
 * variant onto one key and matches the handles the message API returns.
 *
 * Based on craigeley/chat (MIT), https://github.com/craigeley/chat.
 */
class Contacts(private val byKey: Map<String, Contact> = emptyMap()) {

    /** Full name for an address, or null if it is not in the address book. */
    fun name(address: String): String? = byKey[key(address)]?.name

    /** Sender label inside a thread; the first name keeps group rows short. */
    fun sender(address: String): String = label(address, firstNameOnly = true)

    /**
     * A conversation's title as iMessage shows it: the group's own name if it has one, the full name for a
     * one-to-one chat, first names for an unnamed group.
     */
    fun title(displayName: String, participants: List<String>): String = when {
        displayName.isNotBlank() -> displayName
        participants.isEmpty() -> "Unknown"
        participants.size == 1 -> label(participants[0], firstNameOnly = false)
        else -> participants.joinToString(", ") { label(it, firstNameOnly = true) }
    }

    fun entries(): List<ContactEntity> = byKey.map { ContactEntity(it.key, it.value.name, it.value.address) }

    /** Contacts whose name, number or email contains [query], best names first, for the new message picker. */
    fun search(query: String, limit: Int = 30): List<Contact> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val digits = q.filter { it.isDigit() }
        return byKey.entries
            .filter { (key, c) -> c.name.lowercase().contains(q) || key.contains(q) || (digits.length >= 3 && key.contains(digits)) }
            .map { it.value }
            .sortedBy { it.name.lowercase() }
            .take(limit)
    }

    private fun label(address: String, firstNameOnly: Boolean): String {
        val name = name(address) ?: return address
        return if (firstNameOnly) name.substringBefore(" ") else name
    }

    companion object {
        /** Normalizes an address to its lookup key. */
        fun key(address: String): String {
            val a = address.trim()
            if (a.contains("@")) return a.lowercase()
            val digits = a.filter { it.isDigit() }
            return if (digits.length >= 10) digits.takeLast(10) else digits
        }

        /**
         * An address in the form iMessage uses as a handle: an email in lowercase, a phone number as `+` and
         * digits. A bare ten-digit number is taken to be American, as is an eleven-digit one starting with 1.
         */
        fun dial(address: String): String {
            val a = address.trim()
            if (a.contains("@")) return a.lowercase()
            val digits = a.filter { it.isDigit() }
            return when {
                a.startsWith("+") -> "+$digits"
                digits.length == 10 -> "+1$digits"
                digits.length == 11 && digits.startsWith("1") -> "+$digits"
                else -> digits
            }
        }

        /** Whether text typed into the recipient field is a number or email that a chat can be started with. */
        fun looksLikeAddress(text: String): Boolean {
            val t = text.trim()
            if (t.contains("@")) return t.substringAfter("@").contains('.') && !t.contains(' ')
            return t.count { it.isDigit() } >= 7 && t.all { it.isDigit() || it in "+-() ." }
        }

        fun fromEntities(entities: List<ContactEntity>): Contacts =
            Contacts(entities.associate { it.key to Contact(it.name, it.address) })

        /** Builds the index from address and name pairs; the first name wins for each key. */
        fun from(pairs: List<Pair<String, String>>): Contacts {
            val map = HashMap<String, Contact>()
            for ((address, name) in pairs) {
                val k = key(address)
                if (k.isNotEmpty() && name.isNotBlank()) map.putIfAbsent(k, Contact(name, dial(address)))
            }
            return Contacts(map)
        }
    }
}
