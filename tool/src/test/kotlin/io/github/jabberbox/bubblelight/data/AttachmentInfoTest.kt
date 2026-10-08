package io.github.jabberbox.bubblelight.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentInfoTest {
    @Test
    fun aTypedImageIsAnImage() {
        assertTrue(AttachmentInfo("a", "image/jpeg", "x.jpg").isImage)
        assertEquals("Photo", AttachmentInfo("a", "image/png", "x.png").typeLabel)
    }

    @Test
    fun anUntypedFileIsAnImageOnlyByItsExtension() {
        // A message sent over AppleScript can come back before the Mac has worked out its type.
        assertTrue(AttachmentInfo("a", null, "IMG_1.JPG").isImage)
        assertTrue(AttachmentInfo("a", null, "photo.heic").isImage)
        assertEquals("Photo", AttachmentInfo("a", null, "photo.jpeg").typeLabel)
        assertFalse(AttachmentInfo("a", null, "notes.pdf").isImage)
        assertFalse(AttachmentInfo("a", null, null).isImage)
        assertFalse(AttachmentInfo("a", null, "noextension").isImage)
    }

    @Test
    fun aKnownNonImageTypeIsNeverAnImageWhateverItIsCalled() {
        assertFalse(AttachmentInfo("a", "video/quicktime", "clip.jpg").isImage)
        assertEquals("Video", AttachmentInfo("a", "video/quicktime", "clip.mov").typeLabel)
    }
}
