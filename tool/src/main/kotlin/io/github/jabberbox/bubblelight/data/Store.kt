package io.github.jabberbox.bubblelight.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
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

    suspend fun clear() {
        baseUrl = null
        encryptedPassword = null
        lastSeenDate = 0L
        privateApi = false
        sendReadReceipts = false
        lastContactsSync = 0L
        // The SDK keeps its own keys in this same DataStore, so only this tool's keys are removed.
        dataStore.edit { prefs ->
            listOf(KEY_URL, KEY_PASSWORD).forEach { prefs.remove(it) }
            listOf(KEY_LAST_SEEN, KEY_CONTACTS_SYNC).forEach { prefs.remove(it) }
            listOf(KEY_PRIVATE_API, KEY_READ_RECEIPTS).forEach { prefs.remove(it) }
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

        /** Turns what was typed into a server URL: https unless another scheme is given, no trailing slash. Null if unusable. */
        fun normalizeUrl(input: String): String? {
            var url = input.trim().trimEnd('/')
            if (url.isEmpty() || url.any { it.isWhitespace() }) return null
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
            return if (host.isEmpty() || !host.contains('.') && host != "localhost") null else url
        }
    }
}
