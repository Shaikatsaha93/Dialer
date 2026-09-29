package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.CallType
import com.example.ui.components.CallLogItem
import com.example.ui.viewmodel.SoftphoneViewModel

@Composable
fun CallHistoryScreen(
    viewModel: SoftphoneViewModel,
    onNavigateToActiveCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val callLogs by viewModel.filteredCallLogs.collectAsStateWithLifecycle()
    val activeFilter by viewModel.historyFilter.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Screen Header with Clear All Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "DIALER",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "• Developed By Shaikat",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Call History",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${callLogs.size} recorded VoIP calls",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (callLogs.isNotEmpty()) {
                IconButton(
                    onClick = { showClearDialog = true },
                    modifier = Modifier.testTag("clear_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear Call History",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Filter Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = activeFilter == null,
                onClick = { viewModel.setHistoryFilter(null) },
                label = { Text("All") },
                shape = RoundedCornerShape(20.dp),
                colors = FilterChipDefaults.filterChipColors(),
                modifier = Modifier.testTag("filter_chip_all")
            )
            FilterChip(
                selected = activeFilter == CallType.MISSED,
                onClick = { viewModel.setHistoryFilter(CallType.MISSED) },
                label = { Text("Missed") },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("filter_chip_missed")
            )
            FilterChip(
                selected = activeFilter == CallType.INCOMING,
                onClick = { viewModel.setHistoryFilter(CallType.INCOMING) },
                label = { Text("Incoming") },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("filter_chip_incoming")
            )
            FilterChip(
                selected = activeFilter == CallType.OUTGOING,
                onClick = { viewModel.setHistoryFilter(CallType.OUTGOING) },
                label = { Text("Outgoing") },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("filter_chip_outgoing")
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Logs List or Empty State
        if (callLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No call logs yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Placed and received VoIP calls will appear here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(callLogs, key = { it.id }) { log ->
                    val resolvedName = if (log.displayName.isNotBlank() && !log.displayName.startsWith("sip:")) {
                        log.displayName
                    } else {
                        viewModel.getContactNameForUri(log.remoteUri) ?: log.displayName
                    }
                    val displayEntry = if (resolvedName != log.displayName) {
                        log.copy(displayName = resolvedName)
                    } else {
                        log
                    }
                    CallLogItem(
                        entry = displayEntry,
                        onCallBack = { uri ->
                            viewModel.initiateCall(uri)
                            onNavigateToActiveCall()
                        },
                        onDelete = { viewModel.deleteCallLog(log) }
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Call History?") },
            text = { Text("This will permanently delete all call records from local storage.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearCallHistory()
                        showClearDialog = false
                    },
                    modifier = Modifier.testTag("confirm_clear_history_btn")
                ) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
