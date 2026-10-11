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
import io.github.jabberbox.bubblelight.api.ApiException
import io.github.jabberbox.bubblelight.data.ServerSetup
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val graph: BubbleGraph) : LightViewModel<Unit>() {
    private val _server = MutableStateFlow(host())
    val server: StateFlow<String> = _server.asStateFlow()

    /** Read receipts need the server's Private API, which a Mac with System Integrity Protection on does not have. */
    private val _receiptsAvailable = MutableStateFlow(graph.store.privateApi)
    val receiptsAvailable: StateFlow<Boolean> = _receiptsAvailable.asStateFlow()

    private val _receipts = MutableStateFlow(graph.store.sendReadReceipts)
    val receipts: StateFlow<Boolean> = _receipts.asStateFlow()

    private val _confirmSignOut = MutableStateFlow(false)
    val confirmSignOut: StateFlow<Boolean> = _confirmSignOut.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)

    /** What the last Change server attempt did, shown under the server line. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private fun host(): String = graph.store.baseUrl?.substringAfter("://")?.substringBefore('/').orEmpty()

    fun toggleReceipts() {
        if (!_receiptsAvailable.value) return
        val on = !_receipts.value
        graph.store.updateSendReadReceipts(on)
        _receipts.value = on
    }

    /** Connects to the new server. A failed attempt leaves the current connection exactly as it was. */
    fun changeServer(setup: ServerSetup) {
        _status.value = "Connecting..."
        viewModelScope.launch {
            try {
                graph.changeServer(setup.url, setup.password)
                _server.value = host()
                _receiptsAvailable.value = graph.store.privateApi
                _receipts.value = graph.store.sendReadReceipts
                _status.value = "Connected."
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _status.value = if (e.isAuthError) "That password was not accepted. Nothing was changed." else "The server answered with an error (${e.code}). Nothing was changed."
            } catch (e: IOException) {
                _status.value = "Could not reach that server. Nothing was changed."
            } catch (e: Exception) {
                _status.value = "Something went wrong (${e::class.simpleName}). Nothing was changed."
            }
        }
    }

    /** Wipes the local copy and downloads every chat again. The sign-in is kept. */
    fun resync() {
        _status.value = "Resyncing. Your chats will fill back in over the next minute."
        viewModelScope.launch {
            try {
                graph.resync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = "Could not resync (${e::class.simpleName})."
            }
        }
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
        val receiptsAvailable by viewModel.receiptsAvailable.collectAsState()
        val confirm by viewModel.confirmSignOut.collectAsState()
        val server by viewModel.server.collectAsState()
        val status by viewModel.status.collectAsState()
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
                        .lightClickable(enabled = receiptsAvailable) { viewModel.toggleReceipts() }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        LightText(text = "Read receipts", variant = LightTextVariant.Copy, lighten = !receiptsAvailable)
                        if (!receiptsAvailable) {
                            LightText(
                                text = "Needs the server's Private API",
                                variant = LightTextVariant.Fine,
                                lighten = true,
                            )
                        }
                    }
                    if (receiptsAvailable) {
                        LightIcon(icon = if (receipts) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF, size = 1.5f)
                    }
                }
                LightText(
                    text = "Change server",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable(onClick = ::changeServer)
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                )
                LightText(
                    text = "Resync",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { viewModel.resync() }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                )
                LightText(
                    text = if (confirm) "Tap again to sign out" else "Sign out",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { viewModel.signOut { goBack() } }
                        .padding(vertical = 0.75f.gridUnitsAsDp()),
                )
                LightText(
                    text = "Server: $server",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
                status?.let {
                    LightText(
                        text = it,
                        variant = LightTextVariant.Fine,
                        modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
                    )
                }
                LightText(
                    text = "Messages travel only between this phone and your own server. Ones that arrive while this tool is closed show up when you open it.",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.padding(top = 0.75f.gridUnitsAsDp()),
                )
            }
        }
    }

    private fun changeServer() {
        navigateTo({ SetupScreen(it, title = "Change server") }) { setup -> viewModel.changeServer(setup) }
    }
}
