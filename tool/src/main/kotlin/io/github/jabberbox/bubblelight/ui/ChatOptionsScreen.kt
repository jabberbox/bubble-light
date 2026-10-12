package io.github.jabberbox.bubblelight.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.jabberbox.bubblelight.BubbleGraph
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the options screen reports back to the thread that opened it. */
object ChatOptionsResult {
    /** The chat was archived, so the thread has nothing left to show and should close. */
    const val ARCHIVED = "archived"
}

class ChatOptionsViewModel(graph: BubbleGraph, chatGuid: String) : LightViewModel<String>() {
    private val repo = requireNotNull(graph.session.value) { "chat options need a signed-in session" }.repo
    private val conversation = repo.conversation(chatGuid)
    private val whileVisible = SharingStarted.WhileSubscribed(5_000)

    val title: StateFlow<String> = combine(conversation, repo.contacts) { conv, contacts ->
        conv?.let { contacts.title(it.displayName, it.participants) }.orEmpty()
    }.stateIn(viewModelScope, whileVisible, "")

    val pinned: StateFlow<Boolean> = combine(conversation, repo.pinned) { conv, pins ->
        conv?.guids?.any { it in pins } == true
    }.stateIn(viewModelScope, whileVisible, false)

    val archived: StateFlow<Boolean> = combine(conversation, repo.archived) { conv, put ->
        conv != null && conv.guids.any { (put[it] ?: -1L) >= conv.lastDate }
    }.stateIn(viewModelScope, whileVisible, false)

    fun togglePin() {
        viewModelScope.launch {
            val conv = conversation.first() ?: return@launch
            repo.setPinned(conv.guids, !pinned.value)
        }
    }

    /** Puts the chat away, or brings it back. Archiving also unpins it, since an archived chat is never on the main list. */
    fun toggleArchive(done: (archivedNow: Boolean) -> Unit) {
        viewModelScope.launch {
            val conv = conversation.first() ?: return@launch
            if (archived.value) {
                repo.unarchive(conv.guids)
                done(false)
            } else {
                repo.setPinned(conv.guids, false)
                repo.setArchived(conv.guids, conv.lastDate)
                done(true)
            }
        }
    }
}

/**
 * A chat's options, opened from the title of its thread, where the stock app opens the contact. Rows are the same
 * size as the chat list's.
 */
class ChatOptionsScreen(
    sealedActivity: SealedLightActivity,
    private val chatGuid: String,
) : BubbleScreen<String, ChatOptionsViewModel>(sealedActivity) {
    override val viewModelClass: Class<ChatOptionsViewModel>
        get() = ChatOptionsViewModel::class.java

    override fun createViewModel() = ChatOptionsViewModel(graph, chatGuid)

    @Composable
    override fun Body() {
        val title by viewModel.title.collectAsState()
        val pinned by viewModel.pinned.collectAsState()
        val archived by viewModel.archived.collectAsState()
        BubbleTheme {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(title),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            if (!archived) {
                OptionRow(if (pinned) "Unpin chat" else "Pin chat") { viewModel.togglePin() }
            }
            OptionRow(if (archived) "Unarchive chat" else "Archive chat") {
                viewModel.toggleArchive { archivedNow -> if (archivedNow) goBack(ChatOptionsResult.ARCHIVED) else goBack() }
            }
        }
    }

    @Composable
    private fun OptionRow(label: String, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(4f.gridUnitsAsDp())
                .lightClickable(onClick = onClick)
                .padding(top = 0.25f.gridUnitsAsDp()),
        ) {
            androidx.compose.foundation.layout.Spacer(Modifier.width(2f.gridUnitsAsDp()))
            StockText(text = label, style = StockType.Name, maxLines = 1)
        }
    }
}
