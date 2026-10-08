package io.github.jabberbox.bubblelight.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.jabberbox.bubblelight.BubbleGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val graph: BubbleGraph) : LightViewModel<Unit>() {
    /** Read receipts need the server's Private API, which a Mac with System Integrity Protection on does not have. */
    val receiptsAvailable: Boolean = graph.store.privateApi

    val server: String = graph.store.baseUrl?.substringAfter("://")?.substringBefore('/').orEmpty()

    private val _receipts = MutableStateFlow(graph.store.sendReadReceipts)
    val receipts: StateFlow<Boolean> = _receipts.asStateFlow()

    private val _confirmSignOut = MutableStateFlow(false)
    val confirmSignOut: StateFlow<Boolean> = _confirmSignOut.asStateFlow()

    fun toggleReceipts() {
        if (!receiptsAvailable) return
        val on = !_receipts.value
        graph.store.updateSendReadReceipts(on)
        _receipts.value = on
    }

    /** The first tap asks for confirmation, the second signs out and forgets everything stored from the server. */
    fun signOut(done: () -> Unit) {
        if (!_confirmSignOut.value) {
            _confirmSignOut.value = true
            return
        }
        viewModelScope.launch {
            graph.signOut()
            done()
        }
    }
}

class SettingsScreen(sealedActivity: SealedLightActivity) : BubbleScreen<Unit, SettingsViewModel>(sealedActivity) {
    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel() = SettingsViewModel(graph)

    @Composable
    override fun Body() {
        val receipts by viewModel.receipts.collectAsState()
        val confirm by viewModel.confirmSignOut.collectAsState()
        BubbleTheme {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Settings"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable(enabled = viewModel.receiptsAvailable) { viewModel.toggleReceipts() }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        LightText(text = "Read receipts", variant = LightTextVariant.Copy, lighten = !viewModel.receiptsAvailable)
                        if (!viewModel.receiptsAvailable) {
                            LightText(
                                text = "Needs the server's Private API",
                                variant = LightTextVariant.Fine,
                                lighten = true,
                            )
                        }
                    }
                    if (viewModel.receiptsAvailable) {
                        LightIcon(icon = if (receipts) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF, size = 1.5f)
                    }
                }
                LightText(
                    text = if (confirm) "Tap again to sign out" else "Sign out",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { viewModel.signOut { goBack() } }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                )
                LightText(
                    text = "Server: ${viewModel.server}",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
                LightText(
                    text = "Messages travel only between this phone and your own server. Ones that arrive while this tool is closed show up when you open it.",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                )
            }
        }
    }
}
