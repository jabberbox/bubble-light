package io.github.jabberbox.bubblelight.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextInputEditor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

private const val DRAFT_DELAY_MS = 400L

/**
 * One line of text on the Light keyboard, returned to whoever opened it. Starts empty on purpose: nothing is
 * prefilled, and the screen returns nothing if it is backed out of or submitted blank.
 */
class TextEntryScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val submitLabel: String,
    private val submitIcon: LightIconConfiguration? = null,
) : BubbleSimpleScreen<String>(sealedActivity) {
    @Composable
    override fun Body() {
        val state = rememberTextFieldState("")
        val keyboardOptions = rememberKeyboardOptions()
        BubbleTheme {
            AtKeyboardDensity {
                LightTextInputEditor(
                    title = title,
                    state = state,
                    onSubmit = { text -> text.toString().takeIf { it.isNotBlank() }?.let { goBack(it) } },
                    onBack = { goBack() },
                    keyboardOptionsFlow = keyboardOptions,
                    submitLabel = submitLabel,
                    submitIcon = submitIcon,
                    singleLine = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Full-screen writing view for a message: the title is who it goes to, and the send icon returns the text.
 *
 * With a [draftKey] what is typed is kept for that chat, so backing out or being interrupted loses nothing, and it is
 * there again the next time the chat's editor opens. Sending clears it.
 */
class ComposeScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val draftKey: String? = null,
) : BubbleSimpleScreen<String>(sealedActivity) {
    @OptIn(FlowPreview::class)
    @Composable
    override fun Body() {
        val state = rememberTextFieldState(draftKey?.let { graph.store.draft(it) }.orEmpty())
        val keyboardOptions = rememberKeyboardOptions()
        val sent = remember { AtomicBoolean(false) }
        if (draftKey != null) {
            // Saved a moment after typing stops, so each key is not a write.
            LaunchedEffect(state) {
                snapshotFlow { state.text.toString() }.debounce(DRAFT_DELAY_MS).collect { text ->
                    if (!sent.get()) graph.store.setDraft(draftKey, text)
                }
            }
        }
        BubbleTheme {
            AtKeyboardDensity {
                LightTextInputEditor(
                    title = title,
                    state = state,
                    onSubmit = { text ->
                        text.toString().takeIf { it.isNotBlank() }?.let {
                            sent.set(true)
                            draftKey?.let { key -> graph.store.setDraft(key, "") }
                            goBack(it)
                        }
                    },
                    onBack = {
                        draftKey?.let { key -> graph.store.setDraft(key, state.text.toString()) }
                        goBack()
                    },
                    keyboardOptionsFlow = keyboardOptions,
                    submitLabel = "SEND",
                    submitIcon = LightIcons.SEND,
                    initialCaps = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
