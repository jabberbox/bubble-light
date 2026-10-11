package io.github.jabberbox.bubblelight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

/*
 * LightOS draws its own Phone and Messaging screens with sizes taken from the same table as the SDK, but scaled by the
 * screen width (width / 480, which is 2.25 pixels per design pixel on the LP3) where the SDK scales by height. The
 * difference is about 8%, enough to see against the stock screens, so threads and the chat list use these helpers
 * instead of LightText to sit exactly where the stock ones do. The numbers were measured from the stock Messaging
 * screens on a real LP3.
 */

/** Design pixels of the LightOS style table, as the size the stock screens give them. */
@Composable
private fun stockSp(designPx: Float): TextUnit {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    return with(LocalDensity.current) { (designPx * screenWidthDp / DESIGN_WIDTH).dp.toSp() }
}

/** Design pixels as the length the stock screens give them. */
@Composable
fun Float.stockDp(): Dp = (this * LocalConfiguration.current.screenWidthDp / DESIGN_WIDTH).dp

/**
 * The text styles the stock screens use. [nudgeDesignPx] moves the text down inside its line box, because Compose and
 * the stock app place a glyph differently in a line of the same height.
 */
enum class StockType(val sizeDesignPx: Float, val lineHeightDesignPx: Float, val nudgeDesignPx: Float) {
    /** A name in the chat list. */
    Name(30f, 40.5f, 2.2f),

    /** The body of a message. */
    Message(20f, 23f, -0.9f),

    /** The stamp above a message, and the small lines under one. */
    Stamp(16f, 15.5f, 0f),
}

/** The stock text is about 0.75% wider than the same text drawn here, which the stock app's whole-pixel glyph widths explain. */
private const val TRACKING_EM = 0.0075f

@Composable
fun StockText(
    text: String,
    style: StockType,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
    lighten: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val colors = LightThemeTokens.colors
    val base = LightThemeTokens.typography.copy
    BasicText(
        text = text,
        modifier = modifier.offset(y = style.nudgeDesignPx.stockDp()),
        style = TextStyle(
            color = if (lighten) colors.contentSecondary else colors.content,
            fontFamily = base.fontFamily,
            fontWeight = base.fontWeight,
            fontSize = stockSp(style.sizeDesignPx),
            letterSpacing = stockSp(style.sizeDesignPx * TRACKING_EM),
            lineHeight = stockSp(style.lineHeightDesignPx),
            textAlign = align ?: TextAlign.Unspecified,
        ),
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** Width of the stock scrollbar's gutter, 90 pixels on the LP3. */
const val STOCK_SCROLLBAR_UNITS = 2.25f

/**
 * The scrollbar the stock screens draw next to a list: a thin rail the height of the track with a thicker thumb on it.
 * It only shows where the list is, since a list whose rows differ in height has no exact length to drag over.
 * [fromEnd] is for a list laid out in reverse, whose first item sits at the bottom.
 */
@Composable
fun StockScrollbar(state: LazyListState, fromEnd: Boolean = false, modifier: Modifier = Modifier) {
    val color = LightThemeTokens.colors.content
    // Fraction of the way down the list the top edge has got, and what share of it is on screen.
    val position by remember(state, fromEnd) {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty() || info.totalItemsCount == 0) return@derivedStateOf null
            if (!state.canScrollBackward && !state.canScrollForward) return@derivedStateOf null
            val average = visible.sumOf { it.size }.toFloat() / visible.size
            val total = average * info.totalItemsCount
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            // The length is an estimate from the rows on screen, so it can come out under the viewport for a list that does scroll.
            val max = (total - viewport).coerceAtLeast(1f)
            val offset = state.firstVisibleItemIndex * average + state.firstVisibleItemScrollOffset
            val fromStart = (if (fromEnd) max - offset else offset).coerceIn(0f, max)
            (fromStart / max) to (viewport / maxOf(total, viewport)).coerceIn(0.1f, 0.85f)
        }
    }
    // The gutter is kept when there is nothing to scroll, so text keeps the same right margin as the stock thread does.
    BoxWithConstraints(modifier.width(STOCK_SCROLLBAR_UNITS.gridUnitsAsDp()).fillMaxHeight()) {
        val shown = position ?: return@BoxWithConstraints
        val trackHeight = maxHeight
        val thumbHeight = trackHeight * shown.second
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.width(1.dp).fillMaxHeight().align(Alignment.Center).background(color))
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (trackHeight - thumbHeight) * shown.first)
                    .width(5.dp)
                    .height(thumbHeight)
                    .background(color),
            )
        }
    }
}

private const val DESIGN_WIDTH = 480f
