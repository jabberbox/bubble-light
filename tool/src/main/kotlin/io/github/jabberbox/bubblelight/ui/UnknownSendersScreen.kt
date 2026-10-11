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

@OptIn(ExperimentalCoroutinesApi::class)
class UnknownSendersViewModel(graph: BubbleGraph) : LightViewModel<String>() {
    val rows: StateFlow<List<ChatRow>> = graph.session.flatMapLatest { s ->
        if (s == null) flowOf(emptyList()) else chatRows(s).map(ChatLists::unknown)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * The chats from numbers and addresses that are not in the Mac's address book, which the main list keeps behind its
 * last row: verification codes, delivery notices and strangers. Nothing is hidden or deleted, only moved out of the way.
 */
class UnknownSendersScreen(sealedActivity: SealedLightActivity) : BubbleScreen<String, UnknownSendersViewModel>(sealedActivity) {
    override val viewModelClass: Class<UnknownSendersViewModel>
        get() = UnknownSendersViewModel::class.java

    override fun createViewModel() = UnknownSendersViewModel(graph)

    @Composable
    override fun Body() {
        val rows by viewModel.rows.collectAsState()
        BubbleTheme {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("Unknown senders"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            if (rows.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LightText(text = "No unknown senders", variant = LightTextVariant.Copy, lighten = true)
                }
            } else {
                ChatRowList(rows, Modifier.weight(1f)) { guid -> goBack(guid) }
            }
        }
    }
}
