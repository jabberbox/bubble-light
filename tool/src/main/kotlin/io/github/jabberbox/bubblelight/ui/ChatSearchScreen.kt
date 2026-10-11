package io.github.jabberbox.bubblelight.ui

import androidx.compose.foundation.layout.Box
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
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import io.github.jabberbox.bubblelight.BubbleGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Whether a chat matches a search: every word typed appears somewhere in its names and addresses. */
internal fun ChatRow.matches(query: String): Boolean {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return words.isNotEmpty() && words.all { haystack.contains(it) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSearchViewModel(graph: BubbleGraph, private val query: String) : LightViewModel<String>() {
    val rows: StateFlow<List<ChatRow>> = graph.session.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else chatRows(s).map { all -> all.filter { it.matches(query) } }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** The chats whose name, members or numbers contain what was typed, in the same rows as the chat list. */
class ChatSearchScreen(
    sealedActivity: SealedLightActivity,
    private val query: String,
) : BubbleScreen<String, ChatSearchViewModel>(sealedActivity) {
    override val viewModelClass: Class<ChatSearchViewModel>
        get() = ChatSearchViewModel::class.java

    override fun createViewModel() = ChatSearchViewModel(graph, query)

    @Composable
    override fun Body() {
        val rows by viewModel.rows.collectAsState()
        BubbleTheme {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(query),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            if (rows.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LightText(text = "No matches", variant = LightTextVariant.Copy, lighten = true)
                }
            } else {
                ChatRowList(rows, Modifier.weight(1f), onOpen = { guid -> goBack(guid) })
            }
        }
    }
}
