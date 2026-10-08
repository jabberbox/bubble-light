package io.github.jabberbox.bubblelight.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import io.github.jabberbox.bubblelight.BubbleGraph
import io.github.jabberbox.bubblelight.data.Contacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A person to start a chat with: a contact, or a number or email typed in directly. */
data class Recipient(val address: String, val name: String?)

/** The pick handed back to the chat list: who, and the conversation already open with them, if any. */
data class Picked(val address: String, val name: String?, val existingGuid: String?)

class NewMessageViewModel(private val graph: BubbleGraph) : LightViewModel<Picked>() {
    private val repo = requireNotNull(graph.session.value) { "needs a signed-in session" }.repo
    private val query = MutableStateFlow("")
    val text: StateFlow<String> = query.asStateFlow()

    val results: StateFlow<List<Recipient>> = combine(query, repo.contacts) { q, contacts ->
        val typed = if (Contacts.looksLikeAddress(q)) listOf(Recipient(Contacts.dial(q), null)) else emptyList()
        typed + contacts.search(q).map { Recipient(it.address, it.name) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        query.value = value
    }

    /** A conversation with this person may exist already, and the message belongs in it. */
    fun pick(recipient: Recipient, done: (Picked) -> Unit) {
        viewModelScope.launch {
            done(Picked(recipient.address, recipient.name, repo.conversationWith(recipient.address)?.guid))
        }
    }
}

/** Pick who to write to: type a name, number or email, and choose from the matches. */
class NewMessageScreen(sealedActivity: SealedLightActivity) : BubbleScreen<Picked, NewMessageViewModel>(sealedActivity) {
    override val viewModelClass: Class<NewMessageViewModel>
        get() = NewMessageViewModel::class.java

    override fun createViewModel() = NewMessageViewModel(graph)

    @Composable
    override fun Body() {
        val query by viewModel.text.collectAsState()
        val results by viewModel.results.collectAsState()
        BubbleTheme {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }),
                center = LightTopBarCenter.Text("New message"),
                modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
            )
            LightTextField(
                label = "To",
                value = query,
                placeholder = "name, number or email",
                onClick = {
                    navigateTo({ TextEntryScreen(it, title = "To", submitLabel = "SEARCH") }) { viewModel.setQuery(it.trim()) }
                },
                modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp()),
            )
            if (query.isNotBlank() && results.isEmpty()) {
                LightText(
                    text = "No matches",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
                )
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 0.5f.gridUnitsAsDp())) {
                items(results, key = { it.address }) { r ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .lightClickable { viewModel.pick(r) { picked -> goBack(picked) } }
                            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
                    ) {
                        LightText(
                            text = r.name ?: r.address,
                            variant = LightTextVariant.Copy,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (r.name != null) LightText(text = r.address, variant = LightTextVariant.Fine, lighten = true)
                    }
                }
            }
        }
    }
}
