package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A chat thread. [id] is "u:<number>" for a 1-to-1 chat or "g:<groupId>" for a group.
 * [members] holds the other participants' numbers, comma-separated.
 */
@Entity(tableName = "chat_conversations")
data class ChatConversation(
    @PrimaryKey val id: String,
    val title: String,
    val isGroup: Boolean,
    val members: String,
    val lastMessage: String,
    val lastTimestamp: Long,
    val unreadCount: Int
) {
    val memberList: List<String> get() = members.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        fun directId(number: String) = "u:$number"
        fun groupId(groupId: String) = "g:$groupId"
    }
}

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val conversationId: String,
    /** Sender's number; empty for our own messages. */
    val sender: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    /** One of [ChatStatus]. */
    val status: String
)

object ChatStatus {
    const val SENDING = "sending"
    const val SENT = "sent"
    const val FAILED = "failed"
    const val RECEIVED = "received"
}
