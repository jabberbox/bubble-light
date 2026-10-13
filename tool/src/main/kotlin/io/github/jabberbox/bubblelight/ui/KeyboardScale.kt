package io.github.jabberbox.bubblelight.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/*
 * The keyboard in Light's library is drawn in fixed dp (keys 35 dp wide, 164 dp tall) for a screen 360 dp wide, which is
 * the LP3 at 3.0 pixels per dp. LightOS runs the phone at 2.55 pixels per dp, so the same keyboard comes out 15% smaller
 * than the one in the stock tools, which fill the width. Everything else in the SDK is sized from the screen's dp width
 * and the density together, so it does not change when both are moved by the same factor. Wrapping an editor in
 * [AtKeyboardDensity] therefore grows only the keyboard, to the stock size, and leaves the rest where it was.
 */
object KeyboardScale {
    /** The screen width, in dp, that the keyboard library was drawn for. */
    const val DESIGN_WIDTH_DP = 360f

    /** The density at which a screen [widthPx] wide is [DESIGN_WIDTH_DP] across. */
    fun density(widthPx: Float): Float = widthPx / DESIGN_WIDTH_DP
}

/** Runs [content] as if the screen were [KeyboardScale.DESIGN_WIDTH_DP] across, which is the size the keyboard is drawn for. */
@Composable
fun AtKeyboardDensity(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    val configuration = LocalConfiguration.current
    val widthPx = configuration.screenWidthDp * current.density
    val heightPx = configuration.screenHeightDp * current.density
    val target = KeyboardScale.density(widthPx)
    val scaled = remember(target, current.fontScale) { Density(target, current.fontScale) }
    val scaledConfiguration = remember(configuration, target) {
        Configuration(configuration).apply {
            screenWidthDp = (widthPx / target).toInt()
            screenHeightDp = (heightPx / target).toInt()
        }
    }
    CompositionLocalProvider(LocalDensity provides scaled, LocalConfiguration provides scaledConfiguration) {
        content()
    }
}
