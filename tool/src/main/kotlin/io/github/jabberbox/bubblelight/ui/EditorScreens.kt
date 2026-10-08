package io.github.jabberbox.bubblelight.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTextInputEditor

/**
 * One line of text on the Light keyboard, returned to whoever opened it. Starts empty on purpose: nothing is
 * prefilled, and the screen returns nothing if it is backed out of or submitted blank.
 */
class TextEntryScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val submitLabel: String,
) : BubbleSimpleScreen<String>(sealedActivity) {
    @Composable
    override fun Body() {
        val state = rememberTextFieldState("")
        val keyboardOptions = rememberKeyboardOptions()
        BubbleTheme {
            LightTextInputEditor(
                title = title,
                state = state,
                onSubmit = { text -> text.toString().takeIf { it.isNotBlank() }?.let { goBack(it) } },
                onBack = { goBack() },
                keyboardOptionsFlow = keyboardOptions,
                submitLabel = submitLabel,
                singleLine = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Full-screen writing view for a message: the title is who it goes to, and the send icon returns the text. */
class ComposeScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
) : BubbleSimpleScreen<String>(sealedActivity) {
    @Composable
    override fun Body() {
        val state = rememberTextFieldState("")
        val keyboardOptions = rememberKeyboardOptions()
        BubbleTheme {
            LightTextInputEditor(
                title = title,
                state = state,
                onSubmit = { text -> text.toString().takeIf { it.isNotBlank() }?.let { goBack(it) } },
                onBack = { goBack() },
                keyboardOptionsFlow = keyboardOptions,
                submitLabel = "SEND",
                submitIcon = LightIcons.SEND,
                initialCaps = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
