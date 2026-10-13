package io.github.jabberbox.bubblelight.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardScaleTest {
    @Test
    fun theLp3ScreenIsDrawnAtThreePixelsPerDp() {
        assertEquals(3f, KeyboardScale.density(1080f), 0.0001f)
    }

    @Test
    fun theKeyboardGrowsByTheRatioMeasuredAgainstStock() {
        // LightOS runs the LP3 at 2.55 pixels per dp; stock keys are 104 px apart where the library's are 89 px.
        val growth = KeyboardScale.density(1080f) / 2.55f
        assertTrue(growth in 1.16f..1.19f)
        assertEquals(104f / 89f, growth, 0.01f)
    }
}
