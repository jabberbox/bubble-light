package io.github.jabberbox.bubblelight.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneLinksTest {
    @Test
    fun findsNumbersWrittenTheCommonWays() {
        assertEquals(listOf("(608) 264-6591"), PhoneLinks.find("Call me at (608) 264-6591 tomorrow"))
        assertEquals(listOf("608-264-6591"), PhoneLinks.find("608-264-6591"))
        assertEquals(listOf("608.264.6591"), PhoneLinks.find("text 608.264.6591 please"))
        assertEquals(listOf("6082646591"), PhoneLinks.find("it's 6082646591"))
        assertEquals(listOf("+16082646591"), PhoneLinks.find("+16082646591"))
        assertEquals(listOf("+1 608 264 6591"), PhoneLinks.find("my number: +1 608 264 6591."))
    }

    @Test
    fun findsInternationalNumbers() {
        assertEquals(listOf("+44 20 7946 0958"), PhoneLinks.find("London office +44 20 7946 0958"))
    }

    @Test
    fun ignoresThingsThatAreNotPhoneNumbers() {
        assertEquals(emptyList<String>(), PhoneLinks.find("Your code is 123456"))
        assertEquals(emptyList<String>(), PhoneLinks.find("On 2026-10-11 at 12:30"))
        assertEquals(emptyList<String>(), PhoneLinks.find("Total: \$1,000,000.00"))
        // A long run of digits, such as a tracking number, is not a phone number with extra digits.
        assertEquals(emptyList<String>(), PhoneLinks.find("Tracking 1234567890123456"))
        assertEquals(emptyList<String>(), PhoneLinks.find(""))
    }

    @Test
    fun listsEachNumberOnceInOrderAndStopsAtTheLimit() {
        val text = "608-264-6591 or (608) 264-6591 or 6082646591, also 415-555-0100, 212-555-0101 and 312-555-0102"
        assertEquals(listOf("608-264-6591", "415-555-0100", "212-555-0101"), PhoneLinks.find(text))
    }
}
