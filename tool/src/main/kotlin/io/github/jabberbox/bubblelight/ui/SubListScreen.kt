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

/** The lists kept behind the main chat list. */
enum class SubList(val title: String, val empty: String) {
    /** Chats from numbers and addresses that are not in the Mac's address book. */
    Unknown("Unknown senders", "No unknown senders"),

    /** Chats put away, which come back by themselves when something new arrives. */
    Archived("Archived", "Nothing archived"),
}

@OptIn(ExperimentalCoroutinesApi::class)
class SubListViewModel(graph: BubbleGraph, which: SubList) : LightViewModel<String>() {
    val rows: StateFlow<List<ChatRow>> = graph.session.flatMapLatest { s ->
        if (s == null) {
            flowOf(emptyList())
        } else {
            chatRows(s).map { all -> if (which == SubList.Unknown) ChatLists.unknown(all) else ChatLists.archived(all) }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * A list kept behind the main one, in the same rows. Unknown senders are verification codes, delivery notices and
 * strangers; archived chats are ones put away from a chat's options. Nothing is hidden or deleted, only moved out of
 * the way.
 */
class SubListScreen(
    sealedActivity: SealedLightActivity,
    private val which: SubList,
) : BubbleScreen<String, SubListViewModel>(sealedActivity) {
    override val viewModelClass: Class<SubListViewModel>
        get() = SubListViewModel::class.java

    override fun createViewModel() = SubListViewModel(graph, which)

    @Composable
    override fun Body() {
        val rows by viewModel.rows.collectAsState()
        BubbleTheme {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text(which.title),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            if (rows.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LightText(text = which.empty, variant = LightTextVariant.Copy, lighten = true)
                }
            } else {
                ChatRowList(rows, Modifier.weight(1f)) { guid -> goBack(guid) }
            }
        }
    }
}
