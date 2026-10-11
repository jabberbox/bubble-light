package io.github.jabberbox.bubblelight.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightProgressBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import io.github.jabberbox.bubblelight.BubbleGraph
import io.github.jabberbox.bubblelight.data.SyncProgress

/** The Light theme with the screen background, as a column that fills the screen. */
@Composable
fun BubbleTheme(content: @Composable ColumnScope.() -> Unit) {
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(colors = themeColors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
            content = content,
        )
    }
}

/**
 * The one mark that tells Bubble Light's list from the stock one: a small outlined speech bubble in the secondary
 * gray, about one grid unit tall. Drawn as a single outline so the tail opens into the bubble.
 */
@Composable
fun BubbleMark(modifier: Modifier = Modifier) {
    val color = LightThemeTokens.colors.contentSecondary
    Canvas(modifier.size(width = 1.1f.gridUnitsAsDp(), height = 1f.gridUnitsAsDp())) {
        val stroke = 2.5.dp.toPx()
        val half = stroke / 2
        val right = size.width - half
        val body = size.height * 0.78f
        val r = size.height * 0.28f
        val path = Path().apply {
            moveTo(half + r, half)
            lineTo(right - r, half)
            arcTo(Rect(right - 2 * r, half, right, half + 2 * r), -90f, 90f, false)
            lineTo(right, body - r)
            arcTo(Rect(right - 2 * r, body - 2 * r, right, body), 0f, 90f, false)
            lineTo(size.width * 0.42f, body)
            lineTo(size.width * 0.16f, size.height - half)
            lineTo(size.width * 0.22f, body)
            lineTo(half + r, body)
            arcTo(Rect(half, body - 2 * r, half + 2 * r, body), 90f, 90f, false)
            lineTo(half, half + r)
            arcTo(Rect(half, half, half + 2 * r, half + 2 * r), 180f, 90f, false)
            close()
        }
        drawPath(path, color, style = Stroke(width = stroke, join = StrokeJoin.Round))
    }
}

/** A line saying how far a full download has got, with the Light progress bar under it. */
@Composable
fun SyncProgressView(progress: SyncProgress, modifier: Modifier = Modifier) {
    Column(modifier) {
        LightText(
            text = "Syncing ${progress.percent}%  ${progress.label}",
            variant = LightTextVariant.Fine,
            lighten = true,
            modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
        )
        LightProgressBar(LightThemeTokens.colors, progress.fraction)
    }
}

/**
 * A screen that keeps the live connection to the server running while the tool is on screen, and lets it go
 * when the tool is not, since a tool cannot hold a connection open in the background.
 */
abstract class BubbleScreen<R, VM : LightViewModel<R>>(sealedActivity: SealedLightActivity) :
    LightScreen<R, VM>(sealedActivity) {
    protected val graph: BubbleGraph get() = BubbleGraph.get(lightContext)

    /** Keyed by this screen so a second screen of the same class never inherits the first one's remembered state. */
    @Composable
    final override fun Content() = key(this) { Body() }

    @Composable
    protected abstract fun Body()

    override fun willShow() = graph.setForeground(true)

    override fun onAppPause() = graph.setForeground(false)
}

/** [BubbleScreen] for screens with no view model of their own. */
abstract class BubbleSimpleScreen<R>(sealedActivity: SealedLightActivity) : SimpleLightScreen<R>(sealedActivity) {
    protected val graph: BubbleGraph get() = BubbleGraph.get(lightContext)

    @Composable
    final override fun Content() = key(this) { Body() }

    @Composable
    protected abstract fun Body()

    override fun willShow() = graph.setForeground(true)

    override fun onAppPause() = graph.setForeground(false)
}
