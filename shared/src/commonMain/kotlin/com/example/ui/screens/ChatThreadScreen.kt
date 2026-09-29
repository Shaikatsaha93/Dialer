package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.AppGraph
import com.example.platform.formatMediumDate
import com.example.platform.formatShortTime
import com.example.platform.isToday
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ChatStatus
import com.example.ui.theme.CallActionRed
import com.example.ui.theme.LocalGlassColors
import com.example.ui.theme.glass

/** One conversation: message bubbles and the input bar. */
@Composable
fun ChatThreadScreen(
    conversationId: String,
    onBack: () -> Unit,
    onCall: (String) -> Unit
) {
    val chat = AppGraph.chatRepository
    val conversation by chat.conversation(conversationId).collectAsStateWithLifecycle(initialValue = null)
    val messages by chat.messages(conversationId).collectAsStateWithLifecycle(initialValue = emptyList())
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // While this screen is open: no notifications or unread count for it
    DisposableEffect(conversationId) {
        chat.activeConversationId.value = conversationId
        onDispose {
            if (chat.activeConversationId.value == conversationId) chat.activeConversationId.value = null
        }
    }
    LaunchedEffect(conversationId, messages.size) {
        chat.markRead(conversationId)
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize().testTag("chat_thread")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .glass(RoundedCornerShape(20.dp), LocalGlassColors.current)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(conversation?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    conversation?.let {
                        if (it.isGroup) "You, " + it.memberList.joinToString(", ") else it.members
                    }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            val direct = conversation?.takeIf { !it.isGroup }
            if (direct != null) {
                IconButton(onClick = { onCall(direct.members) }, modifier = Modifier.testTag("btn_chat_call")) {
                    Icon(Icons.Default.Call, contentDescription = "Call", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "No messages yet. Say hello!",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(24.dp)
                    )
                }
            }
            items(messages, key = { it.id }) { message ->
                MessageBubble(message, showSender = conversation?.isGroup == true)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .glass(RoundedCornerShape(24.dp), LocalGlassColors.current)
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message") },
                maxLines = 4,
                modifier = Modifier.weight(1f).testTag("input_chat_message")
            )
            IconButton(
                onClick = {
                    chat.send(conversationId, draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
                modifier = Modifier.testTag("btn_chat_send")
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessageEntity, showSender: Boolean) {
    val outgoing = message.isOutgoing
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .then(
                    if (outgoing) {
                        Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    } else {
                        Modifier.glass(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp), LocalGlassColors.current)
                    }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (showSender && !outgoing) {
                Text(
                    message.senderName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(message.text, color = if (outgoing) Color.White else MaterialTheme.colorScheme.onSurface)
            val time = if (isToday(message.timestamp)) {
                formatShortTime(message.timestamp)
            } else {
                formatMediumDate(message.timestamp) + " " + formatShortTime(message.timestamp)
            }
            val status = when (message.status) {
                ChatStatus.SENDING -> " · Sending"
                ChatStatus.SENT -> " · Sent ✓"
                ChatStatus.FAILED -> " · Not sent"
                else -> ""
            }
            Text(
                time + status,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    message.status == ChatStatus.FAILED -> if (outgoing) Color(0xFFFFD6D3) else CallActionRed
                    outgoing -> Color.White.copy(alpha = 0.8f)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}
