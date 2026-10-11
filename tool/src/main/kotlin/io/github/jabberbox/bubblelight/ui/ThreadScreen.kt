package io.github.jabberbox.bubblelight.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.jabberbox.bubblelight.BubbleGraph
import io.github.jabberbox.bubblelight.data.ChatRepository
import io.github.jabberbox.bubblelight.data.Contacts
import io.github.jabberbox.bubblelight.data.MessageEntity
import io.github.jabberbox.bubblelight.data.MessageState
import io.github.jabberbox.bubblelight.data.Tapback
import io.github.jabberbox.bubblelight.data.Tapbacks
import io.github.jabberbox.bubblelight.media.PhotoRef
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModel(private val graph: BubbleGraph, private val chatGuid: String) : LightViewModel<Unit>() {
    private val repo = requireNotNull(graph.session.value) { "a thread needs a signed-in session" }.repo
    private val whileVisible = SharingStarted.WhileSubscribed(5_000)
    private val limit = MutableStateFlow(PAGE)
    private val formatTime = MessageFormat.formatter()
    private var loadingOlder = false
    private var readJob: Job? = null

    private val conversation = repo.conversation(chatGuid)

    /** Every room whose messages belong in this thread, which for a forked group is more than the one opened. */
    private val guids: StateFlow<List<String>> = conversation
        .map { it?.guids ?: listOf(chatGuid) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(chatGuid))

    val title: StateFlow<String> = combine(conversation, repo.contacts) { conv, contacts ->
        conv?.let { contacts.title(it.displayName, it.participants) }.orEmpty()
    }.stateIn(viewModelScope, whileVisible, "")

    /** The room a new message goes to. */
    private val sendTarget: String get() = guids.value.first()

    val messages: StateFlow<List<UiMessage>> = combine(guids, limit) { g, l -> g to l }
        .flatMapLatest { (g, l) ->
            repo.thread(g, l).flatMapLatest { rows ->
                // Tapbacks are needed only as far back as the messages on screen.
                repo.reactions(g, rows.lastOrNull()?.dateCreated ?: 0L).map { rows to Tapbacks.fold(it) }
            }
        }
        .combine(conversation) { (rows, taps), conv -> Triple(rows, taps, conv?.isGroup == true) }
        .combine(repo.contacts) { (rows, taps, isGroup), contacts -> format(rows, taps, isGroup, contacts) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, whileVisible, emptyList())

    private fun format(
        rows: List<MessageEntity>,
        taps: Map<String, List<Tapback>>,
        isGroup: Boolean,
        contacts: Contacts,
    ): List<UiMessage> {
        // Rows are newest first, so the first of mine that is not still sending is the one to give a status.
        val statusFor = rows.firstOrNull { it.fromMe && !it.isGroupEvent }?.takeIf { it.state == MessageState.OK }?.guid
        val ctx = ThreadContext(isGroup, contacts, taps, statusFor)
        return rows.mapNotNull { MessageFormat.toUi(it, ctx, formatTime) }
    }

    /** The number the call button would dial: only a one-to-one chat with a phone number has one. */
    val callNumber: StateFlow<String?> = conversation.map { conv ->
        conv?.takeIf { !it.isGroup }?.participants?.singleOrNull()?.takeIf { !it.contains('@') && it.count { c -> c.isDigit() } >= 7 }
    }.stateIn(viewModelScope, whileVisible, null)

    init {
        viewModelScope.launch { repo.openThread(guids.value) }
    }

    /** A chat on screen counts as read: when it opens, and each time a newer message shows up while it is visible. */
    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        readJob?.cancel()
        readJob = viewModelScope.launch {
            messages.map { it.firstOrNull()?.id }.distinctUntilChanged().filterNotNull().collect {
                repo.markRead(guids.value)
            }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        readJob?.cancel()
    }

    override fun onAppPause() {
        super.onAppPause()
        readJob?.cancel()
    }

    /**
     * Bumped each time a load of older history finishes without failing. The screen watches it, so that if the oldest
     * message is still in view after a load (a page made mostly of tapbacks adds few rows) it asks again, instead of
     * waiting for the next scroll.
     */
    private val _loadTick = MutableStateFlow(0)
    val loadTick: StateFlow<Int> = _loadTick.asStateFlow()

    private var moreHistory = true

    /** Shows more of what is stored, and asks the server for more once the store runs out. */
    fun loadOlder() {
        if (loadingOlder || !moreHistory) return
        loadingOlder = true
        Log.i("BubbleLight", "older history requested")
        limit.update { it + PAGE }
        viewModelScope.launch {
            try {
                when (repo.loadOlder(guids.value)) {
                    ChatRepository.Older.More -> _loadTick.update { it + 1 }
                    ChatRepository.Older.Done -> {
                        moreHistory = false
                        _loadTick.update { it + 1 }
                    }
                    ChatRepository.Older.Failed -> Unit
                }
            } finally {
                loadingOlder = false
            }
        }
    }

    fun send(text: String) = repo.send(sendTarget, text)

    /** Sends a finished recording, given by its path, as a voice message. */
    fun sendVoice(path: String) = repo.sendVoice(sendTarget, File(path))

    fun retry(id: String) = repo.retry(id)

    /** Hands the number to LightOS, which opens its dialer with it filled in. */
    fun call() {
        val number = callNumber.value ?: return
        viewModelScope.launch {
            callRemoteServiceMethod(LightServiceMethod.OpenDialer, LightServiceMethod.OpenDialer.Request(phoneNumber = number))
        }
    }

    private companion object {
        const val PAGE = 60
    }
}

// Measured from the stock thread, in design pixels (2.25 screen pixels each on the LP3). The stock messages repeat every
// 179 pixels, which here is the 108 pixel stamp-and-text block plus this gap, and a stamp sits 5 pixels above its text.
private const val MESSAGE_GAP_DESIGN_PX = 31.5f
private const val STAMP_GAP_DESIGN_PX = 2.2f

// The stock attach sheet: 650 pixels tall (from y = 590 to the bottom), with its icons' tops 197 pixels below its top
// edge less the 40 pixels of touch margin around each icon, which is 4 grid units square here.
private const val SHEET_UNITS = 16.25f
private const val SHEET_ICONS_TOP_UNITS = 3.925f

// The stock group thread puts the stamp 50 pixels under the sender's name, which two boxes of this size already take up.
private const val SENDER_GAP_DESIGN_PX = 0.4f

class ThreadScreen(
    sealedActivity: SealedLightActivity,
    private val chatGuid: String,
) : BubbleScreen<Unit, ThreadViewModel>(sealedActivity) {
    override val viewModelClass: Class<ThreadViewModel>
        get() = ThreadViewModel::class.java

    override fun createViewModel() = ThreadViewModel(graph, chatGuid)

    @Composable
    override fun Body() {
        val title by viewModel.title.collectAsState()
        val messages by viewModel.messages.collectAsState()
        val callNumber by viewModel.callNumber.collectAsState()
        val loadTick by viewModel.loadTick.collectAsState()
        val listState = rememberLazyListState()

        // Ask for more history when the oldest loaded message comes into view, and again after each load if it still is.
        LaunchedEffect(messages.size, loadTick) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .filter { it >= 0 && it >= messages.size - 3 }
                .distinctUntilChanged()
                .collect { viewModel.loadOlder() }
        }
        // Stay pinned to the newest message when a new one arrives while at the bottom.
        LaunchedEffect(messages.firstOrNull()?.id) {
            if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
        }

        var attachOpen by remember { mutableStateOf(false) }

        BubbleTheme {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    LightTopBar(
                        leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { if (attachOpen) attachOpen = false else goBack() }),
                        center = LightTopBarCenter.Text(title),
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )
                    // The stock thread starts one grid unit under the top bar and ends one above the bottom bar, with its
                    // text 80 pixels in from the left and a scrollbar in the gutter on the right.
                    Row(Modifier.weight(1f).fillMaxWidth().padding(bottom = 1f.gridUnitsAsDp())) {
                        // Messages arrive newest first; the reversed layout puts index 0 at the bottom, with older history above.
                        LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(start = 2f.gridUnitsAsDp()),
                            verticalArrangement = Arrangement.spacedBy(MESSAGE_GAP_DESIGN_PX.stockDp()),
                        ) {
                            items(messages, key = { it.id }) { m -> MessageItem(m) }
                        }
                        StockScrollbar(listState, fromEnd = true)
                    }
                    LightBottomBar(
                        items = listOf(
                            callNumber?.let { LightBarButton.LightIcon(icon = LightIcons.CALL, onClick = viewModel::call) },
                            LightBarButton.LightIcon(icon = LightIcons.ADD, onClick = { attachOpen = true }),
                            LightBarButton.LightIcon(icon = LightIcons.COMPOSE_MESSAGE, onClick = ::compose),
                        ),
                    )
                }
                if (attachOpen) {
                    AttachSheet(onClose = { attachOpen = false }, onVoice = {
                        attachOpen = false
                        recordVoice()
                    })
                }
            }
        }
    }

    /**
     * What can be added to a message: the half-screen sheet the stock app opens from its plus button. Only a voice
     * message is offered, because the SDK gives a tool no way to open the camera or pick a photo. Taps outside the
     * sheet close it.
     */
    @Composable
    private fun BoxScope.AttachSheet(onClose: () -> Unit, onVoice: () -> Unit) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
        // The stock sheet starts 590 pixels down and has no rule, text or frame: its choices are bare icons in a row,
        // 80 pixels square and centered at 827 pixels, and a chevron where the bar's buttons were.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(SHEET_UNITS.gridUnitsAsDp())
                .background(LightThemeTokens.colors.background)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        ) {
            Spacer(Modifier.height(SHEET_ICONS_TOP_UNITS.gridUnitsAsDp()))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                SheetIcon(icon = LightIcons.AUDIO_MESSAGE, description = "Audio message", onClick = onVoice)
            }
            Spacer(Modifier.weight(1f))
            LightBottomBar(
                items = listOf(LightBarButton.LightIcon(icon = LightIcons.DOWN, onClick = onClose, contentDescription = "Close")),
                modifier = Modifier.offset(y = 0.675f.gridUnitsAsDp()),
            )
        }
    }

    /** A choice in the sheet, with a touch target wider than the 80 pixel icon. */
    @Composable
    private fun SheetIcon(icon: LightIconConfiguration, description: String, onClick: () -> Unit) {
        Box(
            Modifier.size(4f.gridUnitsAsDp()).lightClickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            LightIcon(icon = icon, contentDescription = description)
        }
    }

    private fun recordVoice() {
        val title = viewModel.title.value
        navigateTo({ VoiceMessageScreen(it, title) }) { path -> viewModel.sendVoice(path) }
    }

    private fun compose() {
        val title = viewModel.title.value
        navigateTo({ ComposeScreen(it, title = title) }) { text -> viewModel.send(text) }
    }

    @Composable
    private fun MessageItem(m: UiMessage) {
        if (m.event) {
            StockText(
                text = m.text,
                style = StockType.Stamp,
                lighten = true,
                align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            return
        }
        val end = if (m.mine) TextAlign.End else TextAlign.Start
        val loader = graph.session.value?.loader
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (m.failed) it.lightClickable { viewModel.retry(m.id) } else it },
            horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start,
        ) {
            // In a group the sender's name sits on its own line above the stamp, in the same small type.
            m.sender?.let {
                StockText(text = it, style = StockType.Stamp, align = end, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(SENDER_GAP_DESIGN_PX.stockDp()))
            }
            StockText(text = m.time, style = StockType.Stamp, align = end, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(STAMP_GAP_DESIGN_PX.stockDp()))
            if (loader != null) {
                m.photos.forEach { photo -> PhotoBlock(photo, m.mine, loader) { openPhoto(photo) } }
            }
            if (m.text.isNotEmpty()) {
                StockText(text = m.text, style = StockType.Message, lighten = m.dim, align = end, modifier = Modifier.fillMaxWidth())
            }
            m.reactions?.let {
                StockText(text = it, style = StockType.Stamp, lighten = true, align = end, modifier = Modifier.fillMaxWidth())
            }
            m.status?.let {
                StockText(text = it, style = StockType.Stamp, lighten = true, align = TextAlign.End, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    private fun openPhoto(ref: PhotoRef) {
        navigateTo({ PhotoScreen(it, ref) })
    }
}
