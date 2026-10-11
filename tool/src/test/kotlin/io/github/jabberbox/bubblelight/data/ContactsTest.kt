package io.github.jabberbox.bubblelight.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsTest {
    private val contacts = Contacts.from(
        listOf(
            "(608) 264-6591" to "Chris Campise",
            "+16082138014" to "Matt Smith",
            "Ada@Example.com" to "Ada Lovelace",
        ),
    )

    @Test
    fun everyFormatOfANumberFindsTheSameName() {
        assertEquals("Chris Campise", contacts.name("+16082646591"))
        assertEquals("Chris Campise", contacts.name("608-264-6591"))
        assertEquals("Chris Campise", contacts.name("6082646591"))
        assertEquals("Ada Lovelace", contacts.name("ada@example.com"))
        assertNull(contacts.name("+16085550000"))
    }

    @Test
    fun titlesFollowTheStockApp() {
        val chris = listOf("+16082646591")
        assertEquals("Chris Campise", contacts.title("", chris))
        assertEquals("CC, MS", contacts.title("", listOf("+16082646591", "+16082138014")))
        // A single-word name gives one letter, and a number outside the address book stays as it is.
        val oneWord = Contacts.from(listOf("+16081111111" to "Tee", "+16082222222" to "Pablo van der Berg"))
        assertEquals("T, PB, +16085550000", oneWord.title("", listOf("+16081111111", "+16082222222", "+16085550000")))
        assertEquals("Roger Dodger Fans", contacts.title("Roger Dodger Fans", listOf("+16082646591", "+16082138014")))
        assertEquals("+16085550000", contacts.title("", listOf("+16085550000")))
        assertEquals("Unknown", contacts.title("", emptyList()))
    }

    @Test
    fun theSenderLabelIsAFirstNameOrTheRawAddress() {
        assertEquals("Chris", contacts.sender("+16082646591"))
        assertEquals("+16085550000", contacts.sender("+16085550000"))
        assertEquals("Chris Campise", contacts.fullName("+16082646591"))
        assertEquals("+16085550000", contacts.fullName("+16085550000"))
    }

    @Test
    fun searchMatchesNamesAndNumbers() {
        assertEquals(listOf("Chris Campise"), contacts.search("chris").map { it.name })
        assertEquals(listOf("Matt Smith"), contacts.search("213-8014").map { it.name })
        assertEquals(listOf("Ada Lovelace"), contacts.search("example.com").map { it.name })
        assertEquals(emptyList<Contact>(), contacts.search(""))
    }

    @Test
    fun searchReturnsAnAddressAChatCanBeStartedWith() {
        assertEquals("+16082646591", contacts.search("chris").single().address)
        assertEquals("ada@example.com", contacts.search("ada").single().address)
    }

    @Test
    fun dialProducesTheFormIMessageUsesAsAHandle() {
        assertEquals("+16082646591", Contacts.dial("(608) 264-6591"))
        assertEquals("+16082646591", Contacts.dial("1 608 264 6591"))
        assertEquals("+447911123456", Contacts.dial("+44 7911 123456"))
        assertEquals("ada@example.com", Contacts.dial(" Ada@Example.com "))
    }

    @Test
    fun recognizesWhatCanBeTypedAsARecipient() {
        assertTrue(Contacts.looksLikeAddress("608-264-6591"))
        assertTrue(Contacts.looksLikeAddress("+16082646591"))
        assertTrue(Contacts.looksLikeAddress("ada@example.com"))
        assertFalse(Contacts.looksLikeAddress("chris"))
        assertFalse(Contacts.looksLikeAddress("608"))
        assertFalse(Contacts.looksLikeAddress("ada@example"))
    }

    @Test
    fun entriesRoundTripThroughStorage() {
        val again = Contacts.fromEntities(contacts.entries())
        assertEquals("Chris Campise", again.name("6082646591"))
        assertEquals("+16082646591", again.search("chris").single().address)
    }

    @Test
    fun storeNormalizesTheServerAddress() {
        assertEquals("https://mac.tail1.ts.net", Store.normalizeUrl(" mac.tail1.ts.net/ "))
        assertEquals("http://100.89.110.8:1234", Store.normalizeUrl("http://100.89.110.8:1234"))
        assertNull(Store.normalizeUrl(""))
        assertNull(Store.normalizeUrl("not a url"))
        assertNull(Store.normalizeUrl("hostwithoutdot"))
    }
}
