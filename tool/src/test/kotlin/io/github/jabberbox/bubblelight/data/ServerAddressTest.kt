package io.github.jabberbox.bubblelight.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAddressTest {
    @Test
    fun aGivenSchemeIsKept() {
        assertEquals("http://mac.example.net", Store.normalizeUrl("http://mac.example.net"))
        assertEquals("https://mac.example.net", Store.normalizeUrl("https://mac.example.net"))
        assertEquals("https://mac.example.net", Store.normalizeUrl("HTTPS://mac.example.net"))
    }

    @Test
    fun noSchemeMeansHttpsForAnythingOnTheInternet() {
        assertEquals("https://mac.example.net", Store.normalizeUrl("mac.example.net"))
        assertEquals("https://mac.tail1234.ts.net", Store.normalizeUrl("  mac.tail1234.ts.net/  "))
        assertEquals("https://8.8.8.8:1234", Store.normalizeUrl("8.8.8.8:1234"))
        assertEquals("https://172.32.0.1", Store.normalizeUrl("172.32.0.1"))
    }

    @Test
    fun noSchemeMeansHttpForTheHomeNetwork() {
        assertEquals("http://192.168.1.20:1234", Store.normalizeUrl("192.168.1.20:1234"))
        assertEquals("http://10.0.2.2:8099", Store.normalizeUrl("10.0.2.2:8099"))
        assertEquals("http://172.16.5.4", Store.normalizeUrl("172.16.5.4"))
        assertEquals("http://172.31.255.1:80", Store.normalizeUrl("172.31.255.1:80"))
        assertEquals("http://my-mac.local:1234", Store.normalizeUrl("my-mac.local:1234"))
        assertEquals("http://localhost:1234", Store.normalizeUrl("localhost:1234"))
    }

    @Test
    fun aGivenSchemeStillWinsOnTheHomeNetwork() {
        assertEquals("https://192.168.1.20:1234", Store.normalizeUrl("https://192.168.1.20:1234"))
    }

    @Test
    fun unusableInputIsRejected() {
        assertNull(Store.normalizeUrl(""))
        assertNull(Store.normalizeUrl("   "))
        assertNull(Store.normalizeUrl("two words.com"))
        assertNull(Store.normalizeUrl("nodots"))
        assertNull(Store.normalizeUrl("https://"))
    }
}
