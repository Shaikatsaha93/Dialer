package com.example.data.repository

import com.example.data.local.ChatDao
import com.example.data.model.ChatConversation
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ChatStatus
import com.example.sip.ChatHeaders
import com.example.sip.IncomingChatMessage
import com.example.sip.SipManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import com.example.platform.currentTimeMillis
import com.example.platform.randomId
import com.example.platform.urlDecode
import com.example.platform.urlEncode

/**
 * Chat over SIP MESSAGE through the registered PBX (e.g. Asterisk).
 *
 * 1-to-1: one MESSAGE to the other number. Group: the sender sends one MESSAGE per member with
 * the group id, name and member list in custom headers; each receiving app files it under the
 * same group. Messages are stored locally, so history survives restarts.
 */
class ChatRepository(
    private val dao: ChatDao,
    private val sipManager: SipManager,
    private val contactsRepository: ContactsSource,
    private val notifier: ChatNotifier,
    private val scope: CoroutineScope
) {
    val conversations: Flow<List<ChatConversation>> = dao.conversations()
    val totalUnread: Flow<Int> = dao.totalUnread()

    /** Conversation currently on screen: no notification and no unread count for it. */
    val activeConversationId = MutableStateFlow<String?>(null)

    fun conversation(id: String): Flow<ChatConversation?> = dao.conversationFlow(id)
    fun messages(id: String): Flow<List<ChatMessageEntity>> = dao.messages(id)

    fun start() {
        scope.launch {
            sipManager.incomingChatMessages.collect { onIncoming(it) }
        }
    }

    /** Opens (or creates) the 1-to-1 conversation with [number]; returns its id. */
    suspend fun openDirect(number: String): String {
        val clean = normalize(number)
        val id = ChatConversation.directId(clean)
        if (dao.conversation(id) == null) {
            dao.upsertConversation(
                ChatConversation(
                    id = id,
                    title = contactsRepository.findContactName(clean) ?: clean,
                    isGroup = false,
                    members = clean,
                    lastMessage = "",
                    lastTimestamp = currentTimeMillis(),
                    unreadCount = 0
                )
            )
        }
        return id
    }

    /** Creates a group with [numbers] (the other members); returns its id. */
    suspend fun createGroup(name: String, numbers: List<String>): String {
        val members = numbers.map { normalize(it) }.filter { it.isNotEmpty() && it != sipManager.ownNumber }.distinct()
        val id = ChatConversation.groupId(randomId().take(12))
        dao.upsertConversation(
            ChatConversation(
                id = id,
                title = name.trim().ifEmpty { "Group" },
                isGroup = true,
                members = members.joinToString(","),
                lastMessage = "",
                lastTimestamp = currentTimeMillis(),
                unreadCount = 0
            )
        )
        return id
    }

    suspend fun markRead(id: String) = dao.markRead(id)

    suspend fun deleteConversation(id: String) {
        dao.deleteMessages(id)
        dao.deleteConversation(id)
    }

    fun send(conversationId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        scope.launch {
            val conversation = dao.conversation(conversationId) ?: return@launch
            val now = currentTimeMillis()
            val messageId = dao.insertMessage(
                ChatMessageEntity(
                    conversationId = conversationId,
                    sender = "",
                    senderName = "",
                    text = body,
                    timestamp = now,
                    isOutgoing = true,
                    status = ChatStatus.SENDING
                )
            )
            dao.upsertConversation(conversation.copy(lastMessage = "You: $body", lastTimestamp = now))

            val recipients = conversation.memberList
            if (recipients.isEmpty()) {
                dao.updateStatus(messageId, ChatStatus.FAILED)
                return@launch
            }
            val headers = if (conversation.isGroup) {
                val everyone = (recipients + listOfNotNull(sipManager.ownNumber)).distinct()
                mapOf(
                    ChatHeaders.GROUP_ID to conversation.id.removePrefix("g:"),
                    ChatHeaders.GROUP_NAME to urlEncode(conversation.title),
                    ChatHeaders.GROUP_MEMBERS to everyone.joinToString(",")
                )
            } else {
                emptyMap()
            }

            // Group: one MESSAGE per member; the message counts as sent only if all went out
            val remaining = MutableStateFlow(recipients.size)
            val failed = MutableStateFlow(0)
            recipients.forEach { number ->
                sipManager.sendChatMessage(number, body, headers) { ok ->
                    if (!ok) failed.updateAndGet { it + 1 }
                    if (remaining.updateAndGet { it - 1 } == 0) {
                        scope.launch {
                            dao.updateStatus(messageId, if (failed.value == 0) ChatStatus.SENT else ChatStatus.FAILED)
                        }
                    }
                }
            }
        }
    }

    private suspend fun onIncoming(message: IncomingChatMessage) {
        val from = normalize(message.from)
        val groupId = message.headers[ChatHeaders.GROUP_ID]
        val senderName = contactsRepository.findContactName(from)
            ?: message.fromDisplayName.takeIf { it.isNotBlank() && it != from }
            ?: from

        val conversationId: String
        val existing: ChatConversation?
        val base: ChatConversation
        if (groupId != null) {
            conversationId = ChatConversation.groupId(groupId)
            existing = dao.conversation(conversationId)
            val members = message.headers[ChatHeaders.GROUP_MEMBERS].orEmpty()
                .split(',').map { it.trim() }
                .plus(from)
                .filter { it.isNotEmpty() && it != sipManager.ownNumber }
                .distinct()
            val name = message.headers[ChatHeaders.GROUP_NAME]
                ?.let { runCatching { urlDecode(it) }.getOrNull() }
                ?: "Group"
            base = existing?.copy(
                // Pick up members added by whoever created the group
                members = (existing.memberList + members).distinct().joinToString(",")
            ) ?: ChatConversation(conversationId, name, true, members.joinToString(","), "", 0L, 0)
        } else {
            conversationId = ChatConversation.directId(from)
            existing = dao.conversation(conversationId)
            base = existing ?: ChatConversation(conversationId, senderName, false, from, "", 0L, 0)
        }

        val isOpen = activeConversationId.value == conversationId
        val timestamp = message.timestamp.takeIf { it > 0 } ?: currentTimeMillis()
        dao.insertMessage(
            ChatMessageEntity(
                conversationId = conversationId,
                sender = from,
                senderName = senderName,
                text = message.text,
                timestamp = timestamp,
                isOutgoing = false,
                status = ChatStatus.RECEIVED
            )
        )
        val preview = if (base.isGroup) "$senderName: ${message.text}" else message.text
        dao.upsertConversation(
            base.copy(
                lastMessage = preview,
                lastTimestamp = timestamp,
                unreadCount = if (isOpen) 0 else base.unreadCount + 1
            )
        )
        if (!isOpen) notifier.notify(conversationId, if (base.isGroup) base.title else senderName, preview)
    }

    private fun normalize(number: String): String =
        number.trim().removePrefix("sip:").removePrefix("sips:").substringBefore("@").substringBefore(";")

    companion object {
        const val CHANNEL_ID = "chat_messages"
    }
}
