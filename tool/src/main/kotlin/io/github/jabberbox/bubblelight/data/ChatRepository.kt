package io.github.jabberbox.bubblelight.data

import androidx.room.withTransaction
import io.github.jabberbox.bubblelight.api.BlueBubblesApi
import io.github.jabberbox.bubblelight.api.ChatInfo
import io.github.jabberbox.bubblelight.api.ParsedMessage
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

enum class SyncStatus { Syncing, Live, Offline }

/**
 * Keeps the local database in step with the BlueBubbles server. Everything the screens show comes from the
 * database, which three paths write to: [sync] (a sweep of recent messages when the tool opens or the live
 * connection returns), [ingest] (live socket events) and the per-chat history calls. Sending writes a local
 * copy first and replaces it when the server's own copy arrives.
 */
class ChatRepository(
    private val db: AppDatabase,
    private val api: BlueBubblesApi,
    private val store: Store,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) {
    private val syncMutex = Mutex()
    private var lastSyncAt = 0L

    private val syncing = MutableStateFlow(false)
    private val syncFailed = MutableStateFlow(false)
    private val hasSynced = MutableStateFlow(false)

    /** Null until the live connection has reported, so the list does not flash "offline" while it connects. */
    private val socketUp = MutableStateFlow<Boolean?>(null)

    private val _contacts = MutableStateFlow(Contacts())
    val contacts: StateFlow<Contacts> = _contacts.asStateFlow()

    val status: Flow<SyncStatus> = combine(syncing, syncFailed, hasSynced, socketUp) { busy, failed, synced, socket ->
        when {
            busy || (!synced && socket != false && !failed) -> SyncStatus.Syncing
            failed || socket == false -> SyncStatus.Offline
            else -> SyncStatus.Live
        }
    }.distinctUntilChanged()

    init {
        scope.launch {
            db.messages().failStaleSending()
            _contacts.value = Contacts.fromEntities(db.contacts().all())
        }
    }

    fun setSocketState(up: Boolean?) {
        socketUp.value = up
    }

    // ---- reading -----------------------------------------------------------

    fun conversations(): Flow<List<Conversation>> = db.chats().observeAll().map { Conversations.collapse(it) }.distinctUntilChanged()

    /** The conversation that includes room [guid], so any sibling room of a forked chat finds the right one. */
    fun conversation(guid: String): Flow<Conversation?> =
        conversations().map { list -> list.firstOrNull { guid in it.guids } }.distinctUntilChanged()

    fun thread(guids: List<String>, limit: Int): Flow<List<MessageEntity>> = db.messages().observeThread(guids, limit)

    fun reactions(guids: List<String>, since: Long): Flow<List<MessageEntity>> = db.messages().observeReactions(guids, since)

    // ---- syncing -----------------------------------------------------------

    /**
     * Catches up with the server: every message since the last one seen, plus the newest few again so read
     * marks changed elsewhere show up. The first run takes one large sweep, which is also what builds the
     * chat list. A run already in progress covers the request.
     */
    suspend fun sync(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastSyncAt < MIN_SYNC_GAP_MS) return
        if (!syncMutex.tryLock()) return
        syncing.value = true
        try {
            runCatching { store.updatePrivateApi(withContext(Dispatchers.IO) { api.serverInfo().privateApiReady }) }
            val cursor = store.lastSeenDate
            val rows = ArrayList<ParsedMessage>()
            withContext(Dispatchers.IO) {
                if (cursor <= 0L) {
                    rows += api.messages(FIRST_SWEEP)
                } else {
                    var offset = 0
                    while (offset < GAP_MAX) {
                        val page = api.messages(PAGE, offset, after = cursor)
                        rows += page
                        if (page.size < PAGE) break
                        offset += page.size
                    }
                    rows += api.messages(REFRESH)
                }
            }
            ingest(rows)
            importChatList()
            hasSynced.value = true
            syncFailed.value = false
            lastSyncAt = System.currentTimeMillis()
            log("sync: ${rows.size} messages")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            syncFailed.value = true
            log("sync failed: ${e::class.simpleName} ${e.message}")
        } finally {
            syncing.value = false
            syncMutex.unlock()
        }
    }

    /**
     * Learns about every chat the server has, once. The sweep of recent messages only reaches the chats that are busy
     * right now, so the rest would never be listed, and neither would their history. Each chat comes with its newest
     * message, which is stored so the chat can be listed and ordered. A chat found here for the first time is old news,
     * so it starts out read rather than turning every quiet conversation into an unread one.
     *
     * A failure leaves the import to be tried again on the next sync and does not fail the sync itself.
     */
    private suspend fun importChatList() {
        if (store.chatListImported) return
        try {
            val entries = withContext(Dispatchers.IO) { api.chatList() }
            val known = db.chats().all().mapTo(HashSet()) { it.guid }
            val rows = entries.mapNotNull { entry -> entry.last?.let { ParsedMessage(it, entry.info) } }
            ingest(rows, advanceCursor = false)
            val fresh = rows.map { it.message.chatGuid }.filter { it !in known }.distinct()
            fresh.chunked(500).forEach { db.chats().setAllLocalRead(it) }
            store.markChatListImported()
            log("chat list: ${entries.size} chats, ${fresh.size} new")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("chat list failed: ${e::class.simpleName} ${e.message}")
        }
    }

    /** Stores messages from the server, whichever path they came by. */
    suspend fun ingest(rows: List<ParsedMessage>, advanceCursor: Boolean = true) {
        if (rows.isEmpty()) return
        db.withTransaction {
            // Rows arrive newest first, so the first chat object seen for a room is the freshest.
            val chats = LinkedHashMap<String, ChatInfo>()
            for (row in rows) row.chat?.let { chats.putIfAbsent(it.guid, it) }
            for (info in chats.values) {
                val participants = ChatEntity.joinAddresses(info.participants)
                db.chats().insertIfAbsent(ChatEntity(info.guid, info.displayName, participants, info.isGroup))
                db.chats().updateInfo(info.guid, info.displayName, participants, info.isGroup)
            }
            for (row in rows) row.message.tempGuid?.let { db.messages().dropEcho(it, row.message.guid) }
            db.messages().upsert(rows.map { it.message })
            db.chats().recompute(rows.map { it.message.chatGuid }.distinct())
        }
        if (advanceCursor) store.advanceLastSeen(rows.maxOf { it.message.dateCreated })
    }

    /** A chat was read somewhere on the account, so its dot clears here too. */
    suspend fun onReadElsewhere(chatGuid: String) {
        val chat = db.chats().get(chatGuid) ?: return
        db.chats().setLocalRead(listOf(chatGuid), chat.lastDate)
    }

    // ---- threads -----------------------------------------------------------

    /** Loads the newest messages of a conversation's rooms, since a sweep only holds the most recent few. */
    suspend fun openThread(guids: List<String>) {
        for (guid in guids) {
            try {
                val page = withContext(Dispatchers.IO) { api.chatMessages(guid, PAGE) }
                storeHistory(guid, page)
                log("thread opened: fetched ${page.size}, ${db.messages().count(guid)} stored, history complete: ${page.size < PAGE}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("open thread failed: ${e::class.simpleName}")
            }
        }
    }

    /** What a call to [loadOlder] left behind: more to fetch, nothing more, or a failure worth retrying. */
    enum class Older { More, Done, Failed }

    /** Fetches the next page of history before the oldest message stored, for each room not yet exhausted. */
    suspend fun loadOlder(guids: List<String>): Older {
        var more = false
        for (guid in guids) {
            try {
                val chat = db.chats().get(guid) ?: continue
                if (chat.historyExhausted) continue
                val before = db.messages().oldest(guid) ?: continue
                val page = withContext(Dispatchers.IO) { api.chatMessages(guid, PAGE, before) }
                storeHistory(guid, page)
                val done = page.size < PAGE
                if (!done) more = true
                log("older history: fetched ${page.size}, ${db.messages().count(guid)} stored, history complete: $done")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("history failed: ${e::class.simpleName}")
                return Older.Failed
            }
        }
        return if (more) Older.More else Older.Done
    }

    private suspend fun storeHistory(guid: String, page: List<MessageEntity>) {
        db.withTransaction {
            if (page.isNotEmpty()) db.messages().upsert(page)
            db.chats().recompute(listOf(guid))
            if (page.size < PAGE) db.chats().setExhausted(guid, true)
        }
    }

    /**
     * Marks a conversation read on this device, which clears its dot. When read receipts are on and the
     * server has the Private API, the server is told too, which also clears it on the other devices.
     */
    suspend fun markRead(guids: List<String>) {
        val chats = db.chats().getMany(guids)
        val newest = chats.maxOfOrNull { it.lastDate } ?: return
        db.chats().setLocalRead(guids, newest)
        if (store.sendReadReceipts && store.privateApi) {
            val live = chats.maxByOrNull { it.lastRealDate } ?: return
            runCatching { withContext(Dispatchers.IO) { api.markRead(live.guid) } }
        }
    }

    // ---- sending -----------------------------------------------------------

    /** Shows the message straight away, then sends it. It runs in the tool's scope so leaving the screen cannot strand it. */
    fun send(chatGuid: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val id = "temp-${UUID.randomUUID()}"
        val row = MessageEntity(
            guid = id, chatGuid = chatGuid, text = body, dateCreated = System.currentTimeMillis(),
            fromMe = true, sender = null, tempGuid = id, state = MessageState.SENDING,
        )
        scope.launch { deliver(row) { method -> api.sendText(chatGuid, body, id, method) } }
    }

    /** Stores the local copy, then runs [call] and settles the row from what it returns. */
    private suspend fun deliver(row: MessageEntity, call: (method: String) -> MessageEntity?) {
        db.withTransaction {
            db.messages().upsert(listOf(row))
            db.chats().recompute(listOf(row.chatGuid))
        }
        try {
            val method = if (store.privateApi) "private-api" else "apple-script"
            val saved = withContext(Dispatchers.IO) { call(method) }
            db.withTransaction {
                if (saved != null) {
                    db.messages().dropEcho(row.guid, saved.guid)
                    db.messages().upsert(listOf(saved.copy(tempGuid = row.guid)))
                } else {
                    db.messages().setState(row.guid, MessageState.OK)
                }
                db.chats().recompute(listOf(row.chatGuid))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("send failed: ${e::class.simpleName} ${e.message}")
            db.messages().setState(row.guid, MessageState.FAILED)
        }
    }

    /** Sends a failed message again as a new one, since the old copy may or may not have reached the Mac. */
    fun retry(guid: String) {
        scope.launch {
            val row = db.messages().get(guid) ?: return@launch
            if (row.state != MessageState.FAILED && row.error == 0) return@launch
            db.messages().delete(guid)
            db.chats().recompute(listOf(row.chatGuid))
            send(row.chatGuid, row.text)
        }
    }

    /**
     * Starts a chat with [addresses] by sending its first message, which is how iMessage creates one.
     * Returns the new chat's guid once its first message is stored, so it can be opened.
     */
    suspend fun startChat(addresses: List<String>, text: String): String {
        val guid = withContext(Dispatchers.IO) { api.newChat(addresses, text.trim()) }
        db.chats().insertIfAbsent(ChatEntity(guid, "", ChatEntity.joinAddresses(addresses), isGroup = addresses.size > 1))
        openThread(listOf(guid))
        return guid
    }

    /** The existing conversation with one person, if there is one, so a new message goes into it. */
    suspend fun conversationWith(address: String): Conversation? =
        Conversations.collapse(db.chats().all()).firstOrNull { !it.isGroup && it.participants.singleOrNull()?.let(Contacts::key) == Contacts.key(address) }

    // ---- contacts ----------------------------------------------------------

    suspend fun refreshContacts(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - store.lastContactsSync < CONTACTS_TTL_MS) return
        try {
            val entries = withContext(Dispatchers.IO) { api.contacts() }
            val contacts = Contacts.from(entries.map { it.address to it.name })
            db.withTransaction {
                db.contacts().clear()
                db.contacts().insert(contacts.entries())
            }
            _contacts.value = contacts
            store.updateLastContactsSync(System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("contacts failed: ${e::class.simpleName}")
        }
    }

    /** Downloads one attachment to [dest], for the photo loader. */
    fun download(guid: String, dest: java.io.File) = api.downloadAttachment(guid, dest)

    private companion object {
        const val FIRST_SWEEP = 1000
        const val PAGE = 50
        const val REFRESH = 100
        const val GAP_MAX = 2000
        const val MIN_SYNC_GAP_MS = 5_000L
        const val CONTACTS_TTL_MS = 6 * 60 * 60 * 1000L
    }
}
