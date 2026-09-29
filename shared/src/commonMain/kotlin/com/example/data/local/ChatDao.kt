package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.ChatConversation
import com.example.data.model.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_conversations ORDER BY lastTimestamp DESC")
    fun conversations(): Flow<List<ChatConversation>>

    @Query("SELECT * FROM chat_conversations WHERE id = :id LIMIT 1")
    fun conversationFlow(id: String): Flow<ChatConversation?>

    @Query("SELECT * FROM chat_conversations WHERE id = :id LIMIT 1")
    suspend fun conversation(id: String): ChatConversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ChatConversation)

    @Query("UPDATE chat_conversations SET unreadCount = 0 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("SELECT COALESCE(SUM(unreadCount), 0) FROM chat_conversations")
    fun totalUnread(): Flow<Int>

    @Query("DELETE FROM chat_conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY timestamp ASC, id ASC")
    fun messages(conversationId: String): Flow<List<ChatMessageEntity>>

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("UPDATE chat_messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun deleteMessages(conversationId: String)
}
