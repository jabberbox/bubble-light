package io.github.jabberbox.bubblelight.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    /** Every chat with at least one message. Merging sibling rooms into conversations happens in [Conversations]. */
    @Query("SELECT * FROM chats WHERE lastDate > 0")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats")
    suspend fun all(): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE guid = :guid")
    suspend fun get(guid: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE guid IN (:guids)")
    suspend fun getMany(guids: List<String>): List<ChatEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(chat: ChatEntity)

    @Query(
        "UPDATE chats SET displayName = CASE WHEN :displayName != '' THEN :displayName ELSE displayName END, " +
            "participants = CASE WHEN :participants != '' THEN :participants ELSE participants END, " +
            "isGroup = :isGroup WHERE guid = :guid",
    )
    suspend fun updateInfo(guid: String, displayName: String, participants: String, isGroup: Boolean)

    /**
     * Recomputes what is derived from stored messages: the newest message, the newest real message, and the
     * newest incoming message nobody has read. Group events never get a read date, so they are left out of
     * the unread rule or they would pin the dot forever.
     */
    @Query(
        "UPDATE chats SET " +
            "lastDate = COALESCE((SELECT MAX(dateCreated) FROM messages m WHERE m.chatGuid = chats.guid), 0), " +
            "lastRealDate = COALESCE((SELECT MAX(dateCreated) FROM messages m WHERE m.chatGuid = chats.guid AND m.reaction IS NULL), 0), " +
            "unreadTs = COALESCE((SELECT MAX(dateCreated) FROM messages m WHERE m.chatGuid = chats.guid AND m.fromMe = 0 " +
            "AND m.dateRead = 0 AND m.itemType = 0 AND m.reaction IS NULL AND (m.text != '' OR m.attachments IS NOT NULL)), 0) " +
            "WHERE guid IN (:guids)",
    )
    suspend fun recompute(guids: List<String>)

    /** Remembers that everything up to [ts] has been seen on this device, never moving backwards. */
    @Query("UPDATE chats SET localReadTs = MAX(localReadTs, :ts) WHERE guid IN (:guids)")
    suspend fun setLocalRead(guids: List<String>, ts: Long)

    @Query("UPDATE chats SET historyExhausted = :exhausted WHERE guid = :guid")
    suspend fun setExhausted(guid: String, exhausted: Boolean)
}

@Dao
interface MessageDao {
    /** Newest [limit] messages across [guids], newest first, without tapbacks. */
    @Query("SELECT * FROM messages WHERE chatGuid IN (:guids) AND reaction IS NULL ORDER BY dateCreated DESC LIMIT :limit")
    fun observeThread(guids: List<String>, limit: Int): Flow<List<MessageEntity>>

    /** Tapbacks from [since] on, which is as far back as the messages on screen. */
    @Query("SELECT * FROM messages WHERE chatGuid IN (:guids) AND reaction IS NOT NULL AND dateCreated >= :since")
    fun observeReactions(guids: List<String>, since: Long): Flow<List<MessageEntity>>

    @Query("SELECT MIN(dateCreated) FROM messages WHERE chatGuid = :guid AND state = 0")
    suspend fun oldest(guid: String): Long?

    @Query("SELECT COUNT(*) FROM messages WHERE chatGuid = :guid")
    suspend fun count(guid: String): Int

    @Query("SELECT * FROM messages WHERE guid = :guid")
    suspend fun get(guid: String): MessageEntity?

    @Query("SELECT guid FROM messages WHERE guid IN (:guids)")
    suspend fun existing(guids: List<String>): List<String>

    @Upsert
    suspend fun upsert(messages: List<MessageEntity>)

    /** Drops the local copy of a message I sent once the server's own copy is known. */
    @Query("DELETE FROM messages WHERE (guid = :tempGuid OR tempGuid = :tempGuid) AND guid != :guid")
    suspend fun dropEcho(tempGuid: String, guid: String)

    @Query("DELETE FROM messages WHERE guid = :guid")
    suspend fun delete(guid: String)

    @Query("UPDATE messages SET state = :state WHERE guid = :guid")
    suspend fun setState(guid: String, state: Int)

    /** Messages still marked sending from an earlier run can never complete, so they become failed. */
    @Query("UPDATE messages SET state = 3 WHERE state = 2")
    suspend fun failStaleSending()
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts")
    suspend fun all(): List<ContactEntity>

    @Query("DELETE FROM contacts")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contacts: List<ContactEntity>)
}

@Database(entities = [ChatEntity::class, MessageEntity::class, ContactEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chats(): ChatDao
    abstract fun messages(): MessageDao
    abstract fun contacts(): ContactDao
}
