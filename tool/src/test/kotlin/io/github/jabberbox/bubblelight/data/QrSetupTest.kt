package io.github.jabberbox.bubblelight.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrSetupTest {
    @Test
    fun readsPasswordAndAddressAndIgnoresTheFirebaseFields() {
        val code = """["hunter2","https://abc.trycloudflare.com","project","bucket","key","https://db.example","client","app"]"""
        assertEquals(ServerSetup("https://abc.trycloudflare.com", "hunter2"), QrSetup.parse(code))
    }

    @Test
    fun acceptsTheMinimumOfPasswordAndAddress() {
        assertEquals(ServerSetup("https://mac.example.net", "pw"), QrSetup.parse("""["pw","https://mac.example.net"]"""))
    }

    @Test
    fun keepsThePasswordExactlyAsGiven() {
        assertEquals("p w!\"1", QrSetup.parse("""["p w!\"1","https://mac.example.net"]""")?.password)
    }

    @Test
    fun trimsATrailingSlashAndSurroundingWhitespaceOnTheCode() {
        assertEquals("https://mac.example.net", QrSetup.parse("  [\"pw\",\"https://mac.example.net/\"]\n")?.url)
    }

    @Test
    fun anAddressWithoutASchemeGetsHttpsOrHttpByWhereItLives() {
        assertEquals("https://mac.example.net:1234", QrSetup.parse("""["pw","mac.example.net:1234"]""")?.url)
        assertEquals("http://192.168.1.20:1234", QrSetup.parse("""["pw","192.168.1.20:1234"]""")?.url)
    }

    @Test
    fun rejectsAnythingThatIsNotASetupCode() {
        assertNull(QrSetup.parse(""))
        assertNull(QrSetup.parse("https://example.com"))
        assertNull(QrSetup.parse("not json"))
        assertNull(QrSetup.parse("""{"password":"pw","server":"https://mac.example.net"}"""))
        assertNull(QrSetup.parse("""["only-a-password"]"""))
        assertNull(QrSetup.parse("""["","https://mac.example.net"]"""))
        assertNull(QrSetup.parse("""["   ","https://mac.example.net"]"""))
        assertNull(QrSetup.parse("""[12345,"https://mac.example.net"]"""))
        assertNull(QrSetup.parse("""["pw",12345]"""))
        assertNull(QrSetup.parse("""["pw","not a server address"]"""))
        assertNull(QrSetup.parse("""["pw","nodots"]"""))
    }
}
