package io.github.jabberbox.bubblelight.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * On-device settings: the server URL, the server password (encrypted by [SecureStore]) and a few
 * preferences. The tool talks to one self-hosted BlueBubbles server.
 *
 * DataStore is asynchronous, so [load] reads everything once at startup into plain fields, and every setter
 * updates its field at once and writes through in the background. Nothing may read a value before [load] ends.
 */
class Store(private val dataStore: DataStore<Preferences>, private val scope: CoroutineScope) {
    @Volatile
    var baseUrl: String? = null
        private set

    @Volatile
    private var encryptedPassword: String? = null

    @Volatile
    var lastSeenDate: Long = 0L
        private set

    @Volatile
    var privateApi: Boolean = false
        private set

    @Volatile
    var sendReadReceipts: Boolean = false
        private set

    @Volatile
    var lastContactsSync: Long = 0L
        private set

    /** Whether every chat the server has was imported, rather than only those active in the newest messages. */
    @Volatile
    var chatListImported: Boolean = false
        private set

    /** The Apple account the server was signed in to when this phone connected, or null when the server did not say. */
    @Volatile
    var serverAccount: String? = null
        private set

    private val _pinned = MutableStateFlow<Set<String>>(emptySet())

    /** The chats pinned to the top of the list, by room guid. Pinning a conversation pins every room it is made of. */
    val pinned: StateFlow<Set<String>> = _pinned.asStateFlow()

    private val _archived = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * Archived chats, by room guid, each with the date of its newest message when it was archived. A chat stays out of
     * the way until something newer than that arrives, which brings it back by itself.
     */
    val archived: StateFlow<Map<String, Long>> = _archived.asStateFlow()

    /** Unsent text per chat, by the room guid new messages go to. */
    private val drafts = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The draft saved for [guid], or an empty string. */
    fun draft(guid: String): String = drafts[guid].orEmpty()

    /** Saves the unsent text of a chat. Blank text removes the draft. */
    fun setDraft(guid: String, text: String) {
        val key = draftKey(guid)
        if (text.isBlank()) {
            if (drafts.remove(guid) != null) persist { it.remove(key) }
        } else if (drafts.put(guid, text) != text) {
            persist { it[key] = text }
        }
    }

    val password: String?
        get() = encryptedPassword?.let { runCatching { SecureStore.decrypt(it) }.getOrNull() }

    val signedIn: Boolean get() = baseUrl != null && !password.isNullOrBlank()

    suspend fun load() {
        val prefs = dataStore.data.first()
        baseUrl = prefs[KEY_URL]?.takeIf { it.isNotBlank() }
        encryptedPassword = prefs[KEY_PASSWORD]
        lastSeenDate = prefs[KEY_LAST_SEEN] ?: 0L
        privateApi = prefs[KEY_PRIVATE_API] ?: false
        sendReadReceipts = prefs[KEY_READ_RECEIPTS] ?: false
        lastContactsSync = prefs[KEY_CONTACTS_SYNC] ?: 0L
        serverAccount = prefs[KEY_ACCOUNT]
        chatListImported = prefs[KEY_CHAT_LIST_IMPORTED] ?: false
        _pinned.value = prefs[KEY_PINNED] ?: emptySet()
        _archived.value = (prefs[KEY_ARCHIVED] ?: emptySet()).mapNotNull(::parseArchived).toMap()
        drafts.clear()
        for ((key, value) in prefs.asMap()) {
            if (key.name.startsWith(DRAFT_PREFIX) && value is String) drafts[key.name.removePrefix(DRAFT_PREFIX)] = value
        }
    }

    /** Archives every room in [guids], remembering [lastDate], the newest message at this moment. */
    fun setArchived(guids: Collection<String>, lastDate: Long) {
        val next = _archived.value + guids.associateWith { lastDate }
        _archived.value = next
        persist { it[KEY_ARCHIVED] = next.map { (guid, date) -> "$guid$ARCHIVE_SEPARATOR$date" }.toSet() }
    }

    /** Brings back every room in [guids]. */
    fun unarchive(guids: Collection<String>) {
        val next = _archived.value - guids.toSet()
        _archived.value = next
        persist { if (next.isEmpty()) it.remove(KEY_ARCHIVED) else it[KEY_ARCHIVED] = next.map { (guid, date) -> "$guid$ARCHIVE_SEPARATOR$date" }.toSet() }
    }

    /** Pins or unpins every room in [guids]. */
    fun setPinned(guids: Collection<String>, pinned: Boolean) {
        val next = if (pinned) _pinned.value + guids else _pinned.value - guids.toSet()
        _pinned.value = next
        persist { if (next.isEmpty()) it.remove(KEY_PINNED) else it[KEY_PINNED] = next }
    }

    /** Forgets every pin, archive and draft, for when the chats they pointed at are gone. */
    fun clearChatMarks() {
        _pinned.value = emptySet()
        _archived.value = emptyMap()
        val drafted = drafts.keys.toList()
        drafts.clear()
        persist { prefs ->
            prefs.remove(KEY_PINNED)
            prefs.remove(KEY_ARCHIVED)
            drafted.forEach { prefs.remove(draftKey(it)) }
        }
    }

    fun saveServer(url: String, password: String) {
        val blob = SecureStore.encrypt(password)
        baseUrl = url
        encryptedPassword = blob
        persist { it[KEY_URL] = url; it[KEY_PASSWORD] = blob }
    }

    /** Remembers the newest message date seen by any path, so catch-up asks the server only for what came after. */
    fun advanceLastSeen(date: Long) {
        if (date <= lastSeenDate) return
        lastSeenDate = date
        persist { it[KEY_LAST_SEEN] = date }
    }

    /** Whether the server's Private API was live when last checked, which enables read receipts. */
    fun updatePrivateApi(value: Boolean) {
        privateApi = value
        persist { it[KEY_PRIVATE_API] = value }
    }

    /** Off by default: telling people you read their message is something to opt into. */
    fun updateSendReadReceipts(value: Boolean) {
        sendReadReceipts = value
        persist { it[KEY_READ_RECEIPTS] = value }
    }

    fun updateLastContactsSync(value: Long) {
        lastContactsSync = value
        persist { it[KEY_CONTACTS_SYNC] = value }
    }

    fun updateServerAccount(value: String?) {
        serverAccount = value
        persist { if (value == null) it.remove(KEY_ACCOUNT) else it[KEY_ACCOUNT] = value }
    }

    fun markChatListImported() {
        chatListImported = true
        persist { it[KEY_CHAT_LIST_IMPORTED] = true }
    }

    /** Forgets how far this phone has synced, so the next sync starts from the server's newest messages again. */
    fun resetSyncState() {
        lastSeenDate = 0L
        lastContactsSync = 0L
        chatListImported = false
        persist {
            it.remove(KEY_LAST_SEEN)
            it.remove(KEY_CONTACTS_SYNC)
            it.remove(KEY_CHAT_LIST_IMPORTED)
        }
    }

    suspend fun clear() {
        baseUrl = null
        encryptedPassword = null
        lastSeenDate = 0L
        privateApi = false
        sendReadReceipts = false
        lastContactsSync = 0L
        serverAccount = null
        chatListImported = false
        _pinned.value = emptySet()
        _archived.value = emptyMap()
        val drafted = drafts.keys.toList()
        drafts.clear()
        // The SDK keeps its own keys in this same DataStore, so only this tool's keys are removed.
        dataStore.edit { prefs ->
            prefs.remove(KEY_PINNED)
            prefs.remove(KEY_ARCHIVED)
            drafted.forEach { prefs.remove(draftKey(it)) }
            listOf(KEY_URL, KEY_PASSWORD, KEY_ACCOUNT).forEach { prefs.remove(it) }
            listOf(KEY_LAST_SEEN, KEY_CONTACTS_SYNC).forEach { prefs.remove(it) }
            listOf(KEY_PRIVATE_API, KEY_READ_RECEIPTS, KEY_CHAT_LIST_IMPORTED).forEach { prefs.remove(it) }
        }
    }

    private fun persist(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        scope.launch { dataStore.edit { block(it) } }
    }

    companion object {
        private val KEY_URL = stringPreferencesKey("bb_server_url")
        private val KEY_PASSWORD = stringPreferencesKey("bb_server_password")
        private val KEY_LAST_SEEN = longPreferencesKey("bb_last_seen_date")
        private val KEY_PRIVATE_API = booleanPreferencesKey("bb_private_api")
        private val KEY_READ_RECEIPTS = booleanPreferencesKey("bb_read_receipts")
        private val KEY_CONTACTS_SYNC = longPreferencesKey("bb_contacts_sync")
        private val KEY_ACCOUNT = stringPreferencesKey("bb_server_account")
        private val KEY_CHAT_LIST_IMPORTED = booleanPreferencesKey("bb_chat_list_imported")
        private val KEY_PINNED = stringSetPreferencesKey("bb_pinned_chats")
        private val KEY_ARCHIVED = stringSetPreferencesKey("bb_archived_chats")
        private const val DRAFT_PREFIX = "bb_draft_"
        private const val ARCHIVE_SEPARATOR = '\u0001'

        private fun draftKey(guid: String) = stringPreferencesKey(DRAFT_PREFIX + guid)

        private fun parseArchived(entry: String): Pair<String, Long>? {
            val guid = entry.substringBefore(ARCHIVE_SEPARATOR)
            val date = entry.substringAfter(ARCHIVE_SEPARATOR, "").toLongOrNull() ?: return null
            return if (guid.isEmpty()) null else guid to date
        }

        /**
         * Turns what was typed or scanned into a server URL, with no trailing slash. A scheme that was given is kept.
         * Without one, an address on the home network (a private IP, `.local` or `localhost`) gets http, since such a
         * server has no certificate, and anything else gets https. Null if unusable.
         */
        fun normalizeUrl(input: String): String? {
            var url = input.trim().trimEnd('/')
            if (url.isEmpty() || url.any { it.isWhitespace() }) return null
            val hasScheme = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
            if (hasScheme) {
                url = url.substringBefore("://").lowercase() + "://" + url.substringAfter("://")
            } else {
                val bareHost = url.substringBefore('/').substringBefore(':')
                url = (if (isHomeNetworkHost(bareHost)) "http://" else "https://") + url
            }
            val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
            return if (host.isEmpty() || !host.contains('.') && host != "localhost") null else url
        }

        private fun isHomeNetworkHost(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || h.endsWith(".local")) return true
            val parts = h.split('.').map { it.toIntOrNull() ?: return false }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return false
            return parts[0] == 10 || parts[0] == 127 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31)
        }
    }
}
