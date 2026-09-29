package com.example.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.AppGraph
import com.example.platform.relativeTime
import com.example.data.model.ChatConversation
import com.example.data.model.RegistrationStatus
import com.example.ui.theme.CallActionRed
import com.example.ui.theme.LocalGlassColors
import com.example.ui.theme.glass
import kotlinx.coroutines.launch

/** Chat tab: list of 1-to-1 and group conversations. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(onOpenConversation: (String) -> Unit) {
    val chat = AppGraph.chatRepository
    val conversations by chat.conversations.collectAsStateWithLifecycle(initialValue = emptyList())
    val registration by AppGraph.sipManager.registrationState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var showNewChat by remember { mutableStateOf(false) }
    var showNewGroup by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<ChatConversation?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("chat_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Messages", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (registration != RegistrationStatus.REGISTERED) {
                Text(
                    "SIP account is offline. Messages can be sent when it is registered.",
                    color = CallActionRed,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { showNewChat = true }, modifier = Modifier.testTag("btn_new_chat")) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  New chat")
                }
                FilledTonalButton(onClick = { showNewGroup = true }, modifier = Modifier.testTag("btn_new_group")) {
                    Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  New group")
                }
            }
        }
        if (conversations.isEmpty()) {
            item {
                Text(
                    "No chats yet. Start a chat with another agent's SIP number, or create a group.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            }
        }
        items(conversations, key = { it.id }) { conversation ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(18.dp), LocalGlassColors.current)
                    .combinedClickable(
                        onClick = { onOpenConversation(conversation.id) },
                        onLongClick = { toDelete = conversation }
                    )
                    .padding(14.dp)
                    .testTag("chat_item_${conversation.id}"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(conversation)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        conversation.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        conversation.lastMessage.ifEmpty {
                            if (conversation.isGroup) "${conversation.memberList.size + 1} members" else conversation.members
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (conversation.lastMessage.isNotEmpty()) {
                        Text(
                            relativeTime(conversation.lastTimestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (conversation.unreadCount > 0) {
                        Text(
                            conversation.unreadCount.toString(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }

    if (showNewChat) {
        NewChatDialog(
            onDismiss = { showNewChat = false },
            onStart = { number ->
                showNewChat = false
                scope.launch { onOpenConversation(chat.openDirect(number)) }
            }
        )
    }
    if (showNewGroup) {
        NewGroupDialog(
            onDismiss = { showNewGroup = false },
            onCreate = { name, numbers ->
                showNewGroup = false
                scope.launch { onOpenConversation(chat.createGroup(name, numbers)) }
            }
        )
    }
    toDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            text = { Text("Delete the chat with ${conversation.title}? This removes its messages from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { chat.deleteConversation(conversation.id) }
                    toDelete = null
                }) { Text("Delete", color = CallActionRed) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Avatar(conversation: ChatConversation) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (conversation.isGroup) {
            Icon(Icons.Default.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        } else {
            Text(
                conversation.title.firstOrNull()?.uppercase() ?: "?",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun NewChatDialog(onDismiss: () -> Unit, onStart: (String) -> Unit) {
    var number by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New chat") },
        text = {
            OutlinedTextField(
                value = number,
                onValueChange = { number = it.trim() },
                label = { Text("SIP number / extension") },
                placeholder = { Text("e.g. 1002") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth().testTag("input_chat_number")
            )
        },
        confirmButton = {
            TextButton(onClick = { onStart(number) }, enabled = number.isNotBlank()) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NewGroupDialog(onDismiss: () -> Unit, onCreate: (String, List<String>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var members by remember { mutableStateOf("") }
    val numbers = members.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Group name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_group_name")
                )
                OutlinedTextField(
                    value = members,
                    onValueChange = { members = it },
                    label = { Text("Members' SIP numbers") },
                    placeholder = { Text("1002, 1003, 1004") },
                    supportingText = { Text("Separate with commas. ${numbers.size} member(s)") },
                    modifier = Modifier.fillMaxWidth().testTag("input_group_members")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, numbers) },
                enabled = name.isNotBlank() && numbers.isNotEmpty()
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
