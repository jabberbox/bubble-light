package io.github.jabberbox.bubblelight.ui

/**
 * Finds phone numbers written in a message, so each can be dialed with a tap. It is deliberately careful: a number
 * must stand alone (not sit inside a longer run of digits or letters), and short codes, years, prices and tracking
 * numbers do not look enough like a phone number to match.
 */
object PhoneLinks {
    /** A North American number, with or without a country code, brackets, spaces, dots or dashes. */
    private val NORTH_AMERICAN = Regex("""(?<![\w+])(?:\+?1[\s.-]?)?(?:\(\d{3}\)|\d{3})[\s.-]?\d{3}[\s.-]?\d{4}(?!\d)""")

    /** Any other number written with a leading plus and a country code. */
    private val INTERNATIONAL = Regex("""(?<![\w+])\+\d{1,3}[\s.-]?\d(?:[\s.-]?\d){6,11}(?!\d)""")

    /** The numbers in [text] as written, in order, without repeats, at most [limit] of them. */
    fun find(text: String, limit: Int = 3): List<String> {
        val found = LinkedHashMap<String, String>()
        for (match in (NORTH_AMERICAN.findAll(text) + INTERNATIONAL.findAll(text)).sortedBy { it.range.first }) {
            val written = match.value.trim()
            val digits = written.count { it.isDigit() }
            if (digits !in 10..15) continue
            found.putIfAbsent(written.filter { it.isDigit() }, written)
            if (found.size >= limit) break
        }
        return found.values.toList()
    }
}
