package io.github.jabberbox.bubblelight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
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
import io.github.jabberbox.bubblelight.data.SyncProgress
import io.github.jabberbox.bubblelight.data.SyncStatus
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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

/**
 * One row of the chat list. [haystack] is every name and address of the chat in lower case, which is what a search
 * looks through. A chat is [known] when someone in it is in the address book (or it has a name of its own), and
 * [muted] rows are drawn in the lighter gray.
 */
data class ChatRow(
    val id: String,
    val name: String,
    val unread: Boolean,
    val haystack: String = "",
    val known: Boolean = true,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
)

/** How the full list is split between the main list and the rows behind its last rows: unknown senders and archived. */
object ChatLists {
    /** The id of a last row that opens the unknown senders instead of a chat. */
    const val UNKNOWN_ID = "unknown-senders"

    /** The id of a last row that opens the archived chats instead of a chat. */
    const val ARCHIVED_ID = "archived-chats"

    /**
     * Pinned chats first, then chats from people you know, then a row each for the unknown senders and the archive,
     * when there are any. An archived chat is only ever in the archive, even if it is pinned.
     */
    fun main(all: List<ChatRow>): List<ChatRow> {
        val unknown = unknown(all)
        val archived = archived(all)
        val live = all.filter { !it.archived }
        val footers = buildList {
            if (unknown.isNotEmpty()) add(ChatRow(UNKNOWN_ID, "Unknown senders", unread = unknown.any { it.unread }, muted = true))
            if (archived.isNotEmpty()) add(ChatRow(ARCHIVED_ID, "Archived", unread = false, muted = true))
        }
        return live.filter { it.pinned } + live.filter { !it.pinned && it.known } + footers
    }

    /** Chats from numbers and addresses that are not in the address book, unless pinned or archived. */
    fun unknown(all: List<ChatRow>): List<ChatRow> = all.filter { !it.archived && !it.pinned && !it.known }

    /** Chats put away, until something new arrives in them. */
    fun archived(all: List<ChatRow>): List<ChatRow> = all.filter { it.archived }
}

/** Height of a row in the chat list, in grid units: the stock list's 150 pixel rows and 10 pixel gaps. */
private const val ROW_UNITS = 4f

/** How long the answer to a tap on the top band stays up. */
private const val FLASH_MS = 3_000L

/** The rows of the chat list for a session, newest first. */
internal fun chatRows(s: Session): Flow<List<ChatRow>> =
    combine(s.repo.conversations(), s.repo.contacts, s.repo.pinned, s.repo.archived) { conversations, contacts, pinned, archived ->
        conversations.map { conv ->
            val name = contacts.title(conv.displayName, conv.participants)
            val everyone = conv.participants.flatMap { listOf(contacts.fullName(it), it) }
            ChatRow(
                id = conv.guid,
                name = name,
                unread = conv.unread,
                haystack = (listOf(conv.displayName, name) + everyone).joinToString(" ").lowercase(),
                // Before the address book has loaded nobody can be told from a stranger, so everyone counts as known.
                known = contacts.isEmpty || conv.displayName.isNotBlank() || contacts.knowsAny(conv.participants),
                pinned = conv.guids.any { it in pinned },
                // Put away until something newer than what it held at the time arrives.
                archived = conv.guids.any { (archived[it] ?: -1L) >= conv.lastDate },
            )
        }
    }

/**
 * The stock list: rows four grid units tall, which lets the SDK's list draw its scrollbar the way the stock one is.
 * No item keys on purpose: with keys the list holds the first visible row in place, so a chat that just became the
 * newest would appear above the top edge instead of at it.
 */
@Composable
internal fun ChatRowList(rows: List<ChatRow>, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    LightLazyScrollView(modifier.fillMaxWidth(), uniformItemHeightGridUnits = ROW_UNITS) {
        items(rows) { chat -> ChatRowItem(chat) { onOpen(chat.id) } }
    }
}

@Composable
internal fun ChatRowItem(chat: ChatRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_UNITS.gridUnitsAsDp())
            .lightClickable(onClick = onClick)
            .padding(top = 0.25f.gridUnitsAsDp()),
    ) {
        // The stock rows keep a 16 pixel column for the unread dot in front of the name at 80 pixels. Always laid
        // out so names never shift when read state changes.
        Box(
            Modifier
                .padding(start = 0.8f.gridUnitsAsDp(), top = 0.925f.gridUnitsAsDp())
                .size(0.4f.gridUnitsAsDp())
                .clip(CircleShape)
                .background(if (chat.unread) LightThemeTokens.colors.content else Color.Transparent),
        )
        Spacer(Modifier.width(0.8f.gridUnitsAsDp()))
        StockText(
            text = chat.name,
            style = StockType.Name,
            lighten = chat.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

data class LoginUi(val busy: Boolean = false, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListViewModel(private val graph: BubbleGraph) : LightViewModel<Unit>() {
    private val whileVisible = SharingStarted.WhileSubscribed(5_000)

    val ready: StateFlow<Boolean> = graph.ready
    val session: StateFlow<Session?> = graph.session

    val rows: StateFlow<List<ChatRow>> = graph.session.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else chatRows(s)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, whileVisible, emptyList())

    /** How far a full download has got, while one runs, from the first sync or from a resync. */
    val progress: StateFlow<SyncProgress?> = graph.session.flatMapLatest { s ->
        s?.repo?.progress ?: flowOf<SyncProgress?>(null)
    }.stateIn(viewModelScope, whileVisible, null)

    val status: StateFlow<SyncStatus> = graph.session.flatMapLatest { s ->
        s?.repo?.status ?: flowOf(SyncStatus.Syncing)
    }.stateIn(viewModelScope, whileVisible, SyncStatus.Syncing)

    private val _login = MutableStateFlow(LoginUi())
    val login: StateFlow<LoginUi> = _login.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** A one-line problem to show above the list, such as a first message that could not be sent. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _flash = MutableStateFlow<String?>(null)

    /** The answer to a tap on the top band, shown there for a few seconds. */
    val flash: StateFlow<String?> = _flash.asStateFlow()

    private var syncTap: Job? = null

    /** Syncs with the server now and reports what came of it. A tap while one is running is ignored. */
    fun syncNow() {
        val repo = graph.session.value?.repo ?: return
        if (syncTap?.isActive == true) return
        syncTap = viewModelScope.launch {
            _flash.value = SyncNote.WORKING
            val note = SyncNote.of(repo.syncNow())
            _flash.value = note
            delay(FLASH_MS)
            if (_flash.value == note) _flash.value = null
        }
    }

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
        val progress by viewModel.progress.collectAsState()
        val visible = remember(rows) { ChatLists.main(rows) }
        // The stock list has no title bar, only the same height of empty space above the first row. A sync or a
        // problem, when there is one, is shown in that space. Otherwise it holds the one mark that tells this list
        // from the stock one: a small speech bubble.
        // A tap anywhere in the band asks for a sync and shows the answer here for a moment.
        val flash by viewModel.flash.collectAsState()
        val line = flash ?: notice ?: if (status == SyncStatus.Offline) "Offline, will retry" else null
        val running = progress
        Box(
            Modifier.fillMaxWidth().height(3f.gridUnitsAsDp()).lightClickable { viewModel.syncNow() }.padding(horizontal = 2f.gridUnitsAsDp()),
            // The mark sits at the left edge of the names below it, 80 pixels in.
            contentAlignment = if (running != null || line != null) Alignment.BottomStart else Alignment.CenterStart,
        ) {
            if (running != null) {
                SyncProgressView(running, Modifier.padding(bottom = 0.25f.gridUnitsAsDp()))
            } else if (line != null) {
                LightText(text = line, variant = LightTextVariant.Fine, lighten = true, modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()))
            } else {
                BubbleMark()
            }
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
            ChatRowList(visible, Modifier.weight(1f)) { id ->
                when (id) {
                    ChatLists.UNKNOWN_ID -> openSubList(SubList.Unknown)
                    ChatLists.ARCHIVED_ID -> openSubList(SubList.Archived)
                    else -> openThread(id)
                }
            }
        }
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(icon = LightIcons.SETTINGS, onClick = ::openSettings),
                LightBarButton.LightIcon(icon = LightIcons.SEARCH, onClick = ::search),
                LightBarButton.LightIcon(icon = LightIcons.COMPOSE_MESSAGE, onClick = ::newMessage),
            ),
        )
    }

    private fun openThread(guid: String) {
        navigateTo({ ThreadScreen(it, guid) })
    }

    /** A list kept behind the main one: the unknown senders, or the archive. */
    private fun openSubList(which: SubList) {
        navigateTo({ SubListScreen(it, which) }) { guid -> openThread(guid) }
    }

    /** Asks for words with the Light keyboard, as the stock Phone app does, then lists the chats and messages that match. */
    private fun search() {
        navigateTo({ TextEntryScreen(it, title = "Search", submitLabel = "SEARCH", submitIcon = LightIcons.SEARCH) }) { words ->
            navigateTo({ ChatSearchScreen(it, words.trim()) }) { guid -> openThread(guid) }
        }
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
