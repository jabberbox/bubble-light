package io.github.jabberbox.bubblelight.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipartTest {
    private val form = Multipart(
        fields = linkedMapOf("chatGuid" to "iMessage;-;+16081111111", "isAudioMessage" to "true"),
        fileField = "attachment",
        fileName = "Audio Message.m4a",
        mimeType = "audio/mp4",
        boundary = "B",
    )

    @Test
    fun fieldsComeFirstThenTheFilePartHeader() {
        val head = String(form.head, Charsets.UTF_8)
        assertTrue(head.startsWith("--B\r\nContent-Disposition: form-data; name=\"chatGuid\"\r\n\r\niMessage;-;+16081111111\r\n"))
        assertTrue(head.contains("name=\"isAudioMessage\"\r\n\r\ntrue\r\n"))
        assertTrue(head.endsWith("--B\r\nContent-Disposition: form-data; name=\"attachment\"; filename=\"Audio Message.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n"))
    }

    @Test
    fun theBodyEndsWithTheClosingBoundaryAndTheLengthAddsUp() {
        assertEquals("\r\n--B--\r\n", String(form.tail, Charsets.UTF_8))
        assertEquals(form.head.size + 1000L + form.tail.size, form.length(1000))
        assertEquals("multipart/form-data; boundary=B", form.contentType)
    }

    @Test
    fun aQuoteInAFileNameCannotBreakOutOfTheHeader() {
        val odd = Multipart(emptyMap(), "attachment", "a\"b\r\nX: y.m4a", "audio/mp4", boundary = "B")
        val head = String(odd.head, Charsets.UTF_8)
        assertTrue(head.contains("filename=\"a%22bX: y.m4a\""))
    }
}
