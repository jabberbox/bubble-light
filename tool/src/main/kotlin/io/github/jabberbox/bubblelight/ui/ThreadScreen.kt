package io.github.jabberbox.bubblelight.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.jabberbox.bubblelight.BubbleGraph
import io.github.jabberbox.bubblelight.data.Contacts
import io.github.jabberbox.bubblelight.data.MessageEntity
import io.github.jabberbox.bubblelight.data.MessageState
import io.github.jabberbox.bubblelight.data.Tapback
import io.github.jabberbox.bubblelight.data.Tapbacks
import io.github.jabberbox.bubblelight.media.PhotoRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    /** Shows more of what is stored, and asks the server for more once the store runs out. */
    fun loadOlder() {
        if (loadingOlder) return
        loadingOlder = true
        limit.update { it + PAGE }
        viewModelScope.launch {
            try {
                repo.loadOlder(guids.value)
            } finally {
                loadingOlder = false
            }
        }
    }

    fun send(text: String) = repo.send(sendTarget, text)

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
        val listState = rememberLazyListState()

        // Ask for more history when the oldest loaded message comes into view.
        LaunchedEffect(messages.size) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .filter { it >= 0 && it >= messages.size - 3 }
                .distinctUntilChanged()
                .collect { viewModel.loadOlder() }
        }
        // Stay pinned to the newest message when a new one arrives while at the bottom.
        LaunchedEffect(messages.firstOrNull()?.id) {
            if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
        }

        BubbleTheme {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(title),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            // Messages arrive newest first; the reversed layout puts index 0 at the bottom, with older history above.
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.spacedBy(1f.gridUnitsAsDp()),
            ) {
                items(messages, key = { it.id }) { m -> MessageItem(m) }
            }
            LightBottomBar(
                items = listOf(
                    callNumber?.let { LightBarButton.LightIcon(icon = LightIcons.CALL, onClick = viewModel::call) },
                    LightBarButton.LightIcon(icon = LightIcons.COMPOSE_MESSAGE, onClick = ::compose),
                ),
            )
        }
    }

    private fun compose() {
        val title = viewModel.title.value
        navigateTo({ ComposeScreen(it, title = title) }) { text -> viewModel.send(text) }
    }

    @Composable
    private fun MessageItem(m: UiMessage) {
        if (m.event) {
            LightText(
                text = m.text,
                variant = LightTextVariant.Fine,
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
            LightText(text = m.time, variant = LightTextVariant.Fine, lighten = true, align = end, modifier = Modifier.fillMaxWidth())
            if (loader != null) {
                m.photos.forEach { photo -> PhotoBlock(photo, m.mine, loader) { openPhoto(photo) } }
            }
            if (m.text.isNotEmpty()) {
                LightText(text = m.text, variant = LightTextVariant.Copy, lighten = m.dim, align = end, modifier = Modifier.fillMaxWidth())
            }
            m.reactions?.let {
                LightText(text = it, variant = LightTextVariant.Fine, lighten = true, align = end, modifier = Modifier.fillMaxWidth())
            }
            m.status?.let {
                LightText(text = it, variant = LightTextVariant.Fine, lighten = true, align = TextAlign.End, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    private fun openPhoto(ref: PhotoRef) {
        navigateTo({ PhotoScreen(it, ref) })
    }
}
