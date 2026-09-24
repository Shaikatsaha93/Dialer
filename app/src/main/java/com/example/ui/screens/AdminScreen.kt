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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.example.SoftphoneApp
import com.example.data.repository.DeviceRecord
import com.example.data.repository.DeviceStatus
import com.example.ui.theme.CallActionGreen
import com.example.ui.theme.CallActionRed
import com.example.ui.theme.LocalGlassColors
import com.example.ui.theme.glass
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

private enum class AdminFilter(val label: String) { ALL("All"), PENDING("Pending"), ACTIVE("Active"), OFF("Off") }

/** Admin tab: approve, renew, block and delete access requests. */
@Composable
fun AdminScreen() {
    val admin = SoftphoneApp.instance.adminManager
    val license = SoftphoneApp.instance.licenseManager
    val devices by admin.devices.collectAsStateWithLifecycle()
    val error by admin.error.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(AdminFilter.ALL) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    val pendingCount = devices.count { it.status == DeviceStatus.PENDING }
    val shown = devices.filter {
        when (filter) {
            AdminFilter.ALL -> true
            AdminFilter.PENDING -> it.status == DeviceStatus.PENDING
            AdminFilter.ACTIVE -> it.status == DeviceStatus.ACTIVE
            AdminFilter.OFF -> it.status == DeviceStatus.EXPIRED || it.status == DeviceStatus.DISABLED
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("admin_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Access requests", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "$pendingCount pending · ${devices.count { it.status == DeviceStatus.ACTIVE }} active · ${devices.size} total",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { confirm = "Sign out of the admin account? This phone will need a new access request." to license::signOutAdmin },
                    modifier = Modifier.testTag("btn_admin_sign_out")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out")
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AdminFilter.entries) { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(if (f == AdminFilter.PENDING && pendingCount > 0) "${f.label} ($pendingCount)" else f.label) }
                    )
                }
            }
        }
        error?.let { message ->
            item { Text(message, color = CallActionRed, style = MaterialTheme.typography.bodySmall) }
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    "No devices here.",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(shown, key = { it.id }) { record ->
            DeviceCard(
                record = record,
                onApprove = { admin.approve(record.id) },
                onExtend = { days -> admin.extend(record, days) },
                onNoLimit = { admin.approveWithoutLimit(record.id) },
                onBlock = { confirm = "Block ${record.name.ifBlank { "this device" }}? The app stops working right away." to { admin.block(record.id) } },
                onDelete = { confirm = "Delete the request from ${record.name.ifBlank { "this device" }}?" to { admin.delete(record.id) } }
            )
        }
    }

    confirm?.let { (message, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { action(); confirm = null }) { Text("Yes", color = CallActionRed) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DeviceCard(
    record: DeviceRecord,
    onApprove: () -> Unit,
    onExtend: (Int) -> Unit,
    onNoLimit: () -> Unit,
    onBlock: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(18.dp), LocalGlassColors.current)
            .padding(16.dp)
            .testTag("admin_device_${record.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(record.name.ifBlank { "(no name)" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${record.phone} · ${record.device}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusBadge(record.status)
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("+90 days") }, onClick = { menuOpen = false; onExtend(90) })
                    DropdownMenuItem(text = { Text("+1 year") }, onClick = { menuOpen = false; onExtend(365) })
                    DropdownMenuItem(text = { Text("No time limit") }, onClick = { menuOpen = false; onNoLimit() })
                    if (record.status == DeviceStatus.ACTIVE) {
                        DropdownMenuItem(text = { Text("Block", color = CallActionRed) }, onClick = { menuOpen = false; onBlock() })
                    }
                    DropdownMenuItem(text = { Text("Delete", color = CallActionRed) }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }

        Text(
            text = expiryText(record),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Device ID: ${record.id.take(8)}" + if (record.hasSipAccount) " · SIP account from admin" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            when (record.status) {
                DeviceStatus.PENDING, DeviceStatus.EXPIRED, DeviceStatus.DISABLED -> {
                    Button(onClick = onApprove, modifier = Modifier.testTag("btn_approve_${record.id}")) {
                        Text("Approve 30 days")
                    }
                }
                DeviceStatus.ACTIVE -> {
                    Button(onClick = { onExtend(30) }, modifier = Modifier.testTag("btn_extend_${record.id}")) {
                        Text("+30 days")
                    }
                    OutlinedButton(onClick = onBlock, modifier = Modifier.testTag("btn_block_${record.id}")) {
                        Text("Block", color = CallActionRed)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: DeviceStatus) {
    val (label, color) = when (status) {
        DeviceStatus.PENDING -> "Pending" to MaterialTheme.colorScheme.primary
        DeviceStatus.ACTIVE -> "Active" to CallActionGreen
        DeviceStatus.EXPIRED -> "Expired" to CallActionRed
        DeviceStatus.DISABLED -> "Off" to CallActionRed
    }
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

private fun expiryText(record: DeviceRecord): String {
    val format = DateFormat.getDateInstance(DateFormat.MEDIUM)
    val expiresAt = record.expiresAt
    return when (record.status) {
        DeviceStatus.PENDING -> "Requested " + (record.createdAt?.let { format.format(it) } ?: "")
        DeviceStatus.ACTIVE -> when {
            expiresAt == null -> "Active · starts 30 days on the device's next check"
            expiresAt.after(Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(365 * 20L))) -> "Active · no time limit"
            else -> {
                val days = TimeUnit.MILLISECONDS.toDays(expiresAt.time - System.currentTimeMillis())
                "Valid until ${format.format(expiresAt)} ($days days left)"
            }
        }
        DeviceStatus.EXPIRED -> "Expired on " + (expiresAt?.let { format.format(it) } ?: "")
        DeviceStatus.DISABLED -> "Switched off or expired · approve again to give 30 more days"
    }
}
