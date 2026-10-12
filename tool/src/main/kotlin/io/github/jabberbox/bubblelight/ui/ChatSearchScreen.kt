package io.github.jabberbox.bubblelight.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.jabberbox.bubblelight.BubbleGraph
import io.github.jabberbox.bubblelight.data.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whether a chat matches a search: every word typed appears somewhere in its names and addresses. */
internal fun ChatRow.matches(query: String): Boolean {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return words.isNotEmpty() && words.all { haystack.contains(it) }
}

/** A message that contains what was searched for, with the chat it is in. [chatId] opens that chat. */
data class MessageHit(val messageGuid: String, val chatId: String, val title: String, val date: Long, val snippet: String)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSearchViewModel(private val graph: BubbleGraph, private val query: String) : LightViewModel<String>() {
    /** Chats whose name, members or numbers match, which is instant. */
    val chats: StateFlow<List<ChatRow>> = graph.session.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else chatRows(s).map { all -> all.filter { it.matches(query) } }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _hits = MutableStateFlow<List<MessageHit>>(emptyList())

    /** Messages that contain the words typed: those on the phone at once, then whatever the server adds from further back. */
    val hits: StateFlow<List<MessageHit>> = _hits.asStateFlow()

    private val _searching = MutableStateFlow(true)

    /** True until the server has answered or given up, so the screen can say it is still looking. */
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    val formatTime: (Long) -> String = MessageFormat.formatter()

    init {
        viewModelScope.launch {
            val repo = graph.session.value?.repo
            if (repo == null) {
                _searching.value = false
                return@launch
            }
            val local = repo.searchLocal(query)
            publish(local)
            // The server holds the whole history, the phone only what it has downloaded.
            val remote = repo.searchServer(query)
            if (remote != null) publish(local + remote)
            _searching.value = false
        }
    }

    private suspend fun publish(messages: List<MessageEntity>) {
        val session = graph.session.value ?: return
        val conversations = session.repo.conversations().first()
        val contacts = session.repo.contacts.value
        _hits.value = messages
            .distinctBy { it.guid }
            .sortedByDescending { it.dateCreated }
            .mapNotNull { m ->
                // A message in a forked room belongs to the conversation that includes the room.
                val conv = conversations.firstOrNull { m.chatGuid in it.guids } ?: return@mapNotNull null
                MessageHit(m.guid, conv.guid, contacts.title(conv.displayName, conv.participants), m.dateCreated, Snippets.around(m.text, query))
            }
            .take(MAX_HITS)
    }

    private companion object {
        const val MAX_HITS = 40
    }
}

/**
 * The chats and the messages that match what was typed. Chats are the same rows as the list; a message shows who it is
 * with and a little of its text around the match. Tapping either opens the chat.
 */
class ChatSearchScreen(
    sealedActivity: SealedLightActivity,
    private val query: String,
) : BubbleScreen<String, ChatSearchViewModel>(sealedActivity) {
    override val viewModelClass: Class<ChatSearchViewModel>
        get() = ChatSearchViewModel::class.java

    override fun createViewModel() = ChatSearchViewModel(graph, query)

    @Composable
    override fun Body() {
        val chats by viewModel.chats.collectAsState()
        val hits by viewModel.hits.collectAsState()
        val searching by viewModel.searching.collectAsState()
        val listState = rememberLazyListState()
        BubbleTheme {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(query),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            if (chats.isEmpty() && hits.isEmpty() && !searching) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LightText(text = "No matches", variant = LightTextVariant.Copy, lighten = true)
                }
            } else {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (chats.isNotEmpty()) {
                            item { SectionLabel("Chats") }
                            items(chats) { chat -> ChatRowItem(chat) { goBack(chat.id) } }
                        }
                        if (hits.isNotEmpty() || searching) {
                            item { SectionLabel(if (searching && hits.isEmpty()) "Searching messages..." else "Messages") }
                            items(hits) { hit -> HitRow(hit) }
                        }
                    }
                    StockScrollbar(listState)
                }
            }
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        StockText(
            text = text,
            style = StockType.Stamp,
            lighten = true,
            modifier = Modifier.fillMaxWidth().padding(start = 2f.gridUnitsAsDp(), top = 0.25f.gridUnitsAsDp(), bottom = 0.5f.gridUnitsAsDp()),
        )
    }

    @Composable
    private fun HitRow(hit: MessageHit) {
        Column(
            Modifier
                .fillMaxWidth()
                .lightClickable { goBack(hit.chatId) }
                .padding(start = 2f.gridUnitsAsDp(), top = 0.5f.gridUnitsAsDp(), bottom = 0.75f.gridUnitsAsDp()),
        ) {
            StockText(
                text = hit.title + "  " + viewModel.formatTime(hit.date),
                style = StockType.Stamp,
                lighten = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StockText(text = hit.snippet, style = StockType.Message, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
