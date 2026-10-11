package io.github.jabberbox.bubblelight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.jabberbox.bubblelight.BubbleGraph
import io.github.jabberbox.bubblelight.Session
import io.github.jabberbox.bubblelight.api.ApiException
import io.github.jabberbox.bubblelight.data.SyncStatus
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatRow(val id: String, val name: String, val unread: Boolean)

data class LoginUi(val busy: Boolean = false, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListViewModel(private val graph: BubbleGraph) : LightViewModel<Unit>() {
    private val whileVisible = SharingStarted.WhileSubscribed(5_000)

    val ready: StateFlow<Boolean> = graph.ready
    val session: StateFlow<Session?> = graph.session

    val rows: StateFlow<List<ChatRow>> = graph.session.flatMapLatest { s ->
        if (s == null) {
            flowOf(emptyList())
        } else {
            combine(s.repo.conversations(), s.repo.contacts) { conversations, contacts ->
                conversations.map { ChatRow(it.guid, contacts.title(it.displayName, it.participants), it.unread) }
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, whileVisible, emptyList())

    val status: StateFlow<SyncStatus> = graph.session.flatMapLatest { s ->
        s?.repo?.status ?: flowOf(SyncStatus.Syncing)
    }.stateIn(viewModelScope, whileVisible, SyncStatus.Syncing)

    private val _login = MutableStateFlow(LoginUi())
    val login: StateFlow<LoginUi> = _login.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** A one-line problem to show above the list, such as a first message that could not be sent. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Checks the password against the server. The password is never logged. */
    fun signIn(url: String, password: String) {
        if (_login.value.busy) return
        _login.value = LoginUi(busy = true)
        viewModelScope.launch {
            try {
                graph.signIn(url, password)
                _login.value = LoginUi()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _login.value = LoginUi(
                    error = if (e.isAuthError) "That password was not accepted." else "The server answered with an error (${e.code}).",
                )
            } catch (e: IOException) {
                _login.value = LoginUi(error = "Could not reach the server. Check the address, and that the server is running and reachable from this phone.")
            } catch (e: Exception) {
                _login.value = LoginUi(error = "Something went wrong (${e::class.simpleName}).")
            }
        }
    }

    /** Sends the first message to someone new, which creates the chat, then reports its guid. */
    fun startChat(address: String, text: String, onStarted: (String) -> Unit) {
        val repo = graph.session.value?.repo ?: return
        _notice.value = null
        viewModelScope.launch {
            try {
                onStarted(repo.startChat(listOf(address), text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = "Could not send that message."
            }
        }
    }
}

@InitialScreen
class ChatListScreen(sealedActivity: SealedLightActivity) : BubbleScreen<Unit, ChatListViewModel>(sealedActivity) {
    override val viewModelClass: Class<ChatListViewModel>
        get() = ChatListViewModel::class.java

    override fun createViewModel() = ChatListViewModel(graph)

    @Composable
    override fun Body() {
        val ready by viewModel.ready.collectAsState()
        val session by viewModel.session.collectAsState()
        BubbleTheme {
            when {
                !ready -> Message("Loading...")
                session == null -> SignedOut()
                else -> Chats()
            }
        }
    }

    @Composable
    private fun ColumnScope.Message(text: String) {
        LightTopBar(center = LightTopBarCenter.Text("Bubble Light"), modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            LightText(text = text, variant = LightTextVariant.Copy, align = TextAlign.Center, lighten = true)
        }
    }

    @Composable
    private fun ColumnScope.SignedOut() {
        val login by viewModel.login.collectAsState()
        LightTopBar(center = LightTopBarCenter.Text("Bubble Light"), modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()))
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp()),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LightText(
                text = if (login.busy) "Connecting..." else "iMessage through your own BlueBubbles server.",
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
            )
            if (!login.busy) {
                LightText(
                    text = "Scan the QR code your server shows, or type its address and password. Both stay on this phone.",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    align = TextAlign.Center,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            }
            login.error?.let {
                LightText(
                    text = it,
                    variant = LightTextVariant.Fine,
                    align = TextAlign.Center,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            }
        }
        LightBottomBar(
            items = listOf(
                if (login.busy) null else LightBarButton.Text(text = if (login.error != null) "TRY AGAIN" else "SET UP", onClick = ::startSetup),
            ),
        )
    }

    private fun startSetup() {
        navigateTo({ SetupScreen(it) }) { setup -> viewModel.signIn(setup.url, setup.password) }
    }

    @Composable
    private fun ColumnScope.Chats() {
        val rows by viewModel.rows.collectAsState()
        val status by viewModel.status.collectAsState()
        val notice by viewModel.notice.collectAsState()
        LightTopBar(center = LightTopBarCenter.Text("Bubble Light"), modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()))
        val line = notice ?: if (status == SyncStatus.Offline) "Offline, will retry" else null
        if (line != null) {
            LightText(
                text = line,
                variant = LightTextVariant.Fine,
                lighten = true,
                modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
            )
        }
        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                LightText(
                    text = if (status == SyncStatus.Syncing) "Syncing your chats..." else "No chats",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                )
            }
        } else {
            // No item keys on purpose: with keys the list holds the first visible row in place, so a chat that just
            // became the newest would appear above the top edge instead of at it.
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(rows) { chat -> ChatRowItem(chat) { openThread(chat.id) } }
            }
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(icon = LightIcons.SETTINGS, onClick = ::openSettings),
                LightBarButton.LightIcon(icon = LightIcons.COMPOSE_MESSAGE, onClick = ::newMessage),
            ),
        )
    }

    @Composable
    private fun ChatRowItem(chat: ChatRow, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Always laid out so names never shift when read state changes.
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (chat.unread) LightThemeTokens.colors.content else Color.Transparent),
            )
            Spacer(Modifier.width(0.75f.gridUnitsAsDp()))
            LightText(
                text = chat.name,
                variant = LightTextVariant.Copy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }

    private fun openThread(guid: String) {
        navigateTo({ ThreadScreen(it, guid) })
    }

    private fun openSettings() {
        navigateTo({ SettingsScreen(it) })
    }

    private fun newMessage() {
        navigateTo({ NewMessageScreen(it) }) { picked ->
            val existing = picked.existingGuid
            if (existing != null) {
                openThread(existing)
            } else {
                navigateTo({ ComposeScreen(it, title = picked.name ?: picked.address) }) { text ->
                    viewModel.startChat(picked.address, text) { guid -> openThread(guid) }
                }
            }
        }
    }
}
