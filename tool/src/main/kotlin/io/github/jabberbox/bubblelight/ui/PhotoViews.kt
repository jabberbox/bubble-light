package io.github.jabberbox.bubblelight.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.lightClickable
import io.github.jabberbox.bubblelight.media.MediaLoader
import io.github.jabberbox.bubblelight.media.PhotoRef

private sealed interface PhotoState {
    data object Loading : PhotoState
    data object Failed : PhotoState
    data class Ready(val bitmap: ImageBitmap) : PhotoState
}

/** A photo inside a thread, sized to the column. Tapping opens it full screen. */
@Composable
fun PhotoBlock(ref: PhotoRef, mine: Boolean, loader: MediaLoader, onOpen: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        var attempt by remember(ref.guid) { mutableIntStateOf(0) }
        val state by produceState<PhotoState>(PhotoState.Loading, ref.guid, attempt) {
            value = PhotoState.Loading
            val bitmap = loader.bitmap(ref, widthPx)
            value = if (bitmap != null) PhotoState.Ready(bitmap) else PhotoState.Failed
        }
        when (val s = state) {
            PhotoState.Loading -> LightText(text = "Loading photo...", variant = LightTextVariant.Fine, lighten = true)
            PhotoState.Failed -> LightText(
                text = "Photo unavailable, tap to retry",
                variant = LightTextVariant.Fine,
                lighten = true,
                modifier = Modifier.lightClickable { attempt++ },
            )
            is PhotoState.Ready -> Image(
                bitmap = s.bitmap,
                contentDescription = "Photo",
                contentScale = ContentScale.Fit,
                alignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).lightClickable(onClick = onOpen),
            )
        }
    }
}

/** Full-screen photo with pinch to zoom, drag to pan and double tap to reset. */
class PhotoScreen(
    sealedActivity: SealedLightActivity,
    private val ref: PhotoRef,
) : BubbleSimpleScreen<Unit>(sealedActivity) {
    @Composable
    override fun Body() {
        val loader = graph.session.value?.loader
        val density = LocalDensity.current
        val screenWidthDp = LocalConfiguration.current.screenWidthDp
        // Decode at twice the screen width so zooming in stays reasonably sharp.
        val widthPx = with(density) { screenWidthDp.dp.roundToPx() } * 2
        val bitmap by produceState<ImageBitmap?>(null, ref.guid) { value = loader?.bitmap(ref, widthPx) }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        BubbleTheme {
            LightTopBar(leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }))
            Box(Modifier.weight(1f).fillMaxSize()) {
                val shown = bitmap
                if (shown != null) {
                    Image(
                        bitmap = shown,
                        contentDescription = "Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    offset = if (scale <= 1f) Offset.Zero else offset + pan
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
                            }
                            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                    )
                } else {
                    LightText(
                        text = "Loading photo...",
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )
                }
            }
        }
    }
}
