package io.github.jabberbox.bubblelight

import android.util.Log
import io.github.jabberbox.bubblelight.api.BlueBubblesApi
import io.github.jabberbox.bubblelight.api.BlueBubblesJson
import io.github.jabberbox.bubblelight.api.LiveSocket
import io.github.jabberbox.bubblelight.data.AppDatabase
import io.github.jabberbox.bubblelight.data.ChatRepository
import io.github.jabberbox.bubblelight.data.Store
import io.github.jabberbox.bubblelight.media.MediaCache
import io.github.jabberbox.bubblelight.media.MediaLoader
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Everything that exists only while someone is signed in to a server. */
class Session(val repo: ChatRepository, val loader: MediaLoader, val live: LiveSocket)

/**
 * Process-wide wiring, shared by every screen. Holds what must outlive any one screen: the settings, the
 * database, the sync engine and the live connection. The live connection runs only while the tool is on
 * screen: a tool cannot keep a service alive in the background, so messages that arrive while it is closed
 * are picked up by the catch-up sync the next time it opens.
 */
class BubbleGraph private constructor(private val lightContext: SealedLightContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = Store(lightContext.dataStore, scope)
    private val mediaCache = MediaCache(lightContext.filesDir)

    private val db: AppDatabase by lazy { lightContext.buildDatabase(AppDatabase::class.java, "bubblelight.db") }

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /** False only for the moment it takes to read the saved settings at startup. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    @Volatile
    private var foreground = false

    init {
        scope.launch {
            store.load()
            if (store.signedIn) buildSession()
            _ready.value = true
        }
    }

    private fun log(line: String) {
        Log.i("BubbleLight", line)
    }

    private fun buildSession() {
        val url = store.baseUrl ?: return
        val password = store.password ?: return
        val repo = ChatRepository(db, BlueBubblesApi(url, password), store, scope, ::log)
        val live = LiveSocket(
            baseUrl = url,
            password = password,
            scope = scope,
            onState = { up ->
                repo.setSocketState(up)
                // The server only pushes what happens while the socket is open, so every connect catches up.
                if (up == true) scope.launch { repo.sync(force = true) }
            },
            onEvent = { name, data -> onEvent(repo, name, data) },
            log = ::log,
        )
        val session = Session(repo, MediaLoader(mediaCache, repo::download, ::log), live)
        _session.value = session
        if (foreground) begin(session)
    }

    private fun begin(session: Session) {
        session.live.start()
        scope.launch {
            session.repo.sync()
            session.repo.refreshContacts()
        }
    }

    /** Called as the tool comes to the front and as it leaves it. */
    fun setForeground(on: Boolean) {
        foreground = on
        val session = _session.value ?: return
        if (on) begin(session) else session.live.stop()
    }

    private fun onEvent(repo: ChatRepository, name: String, data: JSONObject) {
        when (name) {
            "new-message", "updated-message", "message-send-error",
            "group-name-change", "participant-added", "participant-removed", "participant-left",
            -> {
                val parsed = runCatching { BlueBubblesJson.parseMessage(data) }.getOrNull() ?: return
                scope.launch { repo.ingest(listOf(parsed)) }
            }
            "chat-read-status-changed" -> {
                val guid = data.optString("chatGuid").takeIf { it.isNotBlank() } ?: return
                if (data.optBoolean("read", false)) scope.launch { repo.onReadElsewhere(guid) }
            }
        }
    }

    /**
     * Checks the server with the given URL and password and, if it accepts them, signs in. Throws
     * [io.github.jabberbox.bubblelight.api.ApiException] for a rejected password and an IOException for an
     * unreachable server.
     */
    suspend fun signIn(url: String, password: String) {
        val info = withContext(Dispatchers.IO) { BlueBubblesApi(url, password).serverInfo() }
        store.saveServer(url, password)
        store.updatePrivateApi(info.privateApiReady)
        store.updateServerAccount(info.account)
        buildSession()
    }

    /**
     * Points an already signed-in tool at a different address or password. The new details are checked first, so a
     * wrong one changes nothing. When the server reports the same Apple account as before it is the same server
     * under a new address, and the chats stay; otherwise everything cached from the old server is dropped. A server
     * that does not report an account counts as different, since keeping another server's chats would be worse.
     */
    suspend fun changeServer(url: String, password: String) {
        val info = withContext(Dispatchers.IO) { BlueBubblesApi(url, password).serverInfo() }
        val sameServer = info.account != null && info.account == store.serverAccount
        _session.value?.live?.stop()
        if (!sameServer) {
            _session.value = null
            withContext(Dispatchers.IO) {
                mediaCache.clear()
                db.clearAllTables()
            }
            store.resetSyncState()
        }
        store.saveServer(url, password)
        store.updatePrivateApi(info.privateApiReady)
        store.updateServerAccount(info.account)
        buildSession()
    }

    /** Forgets the server and everything cached from it. */
    suspend fun signOut() {
        _session.value?.live?.stop()
        _session.value = null
        withContext(Dispatchers.IO) {
            mediaCache.clear()
            db.clearAllTables()
        }
        store.clear()
    }

    companion object {
        @Volatile
        private var instance: BubbleGraph? = null

        fun get(lightContext: SealedLightContext): BubbleGraph =
            instance ?: synchronized(this) { instance ?: BubbleGraph(lightContext).also { instance = it } }
    }
}
