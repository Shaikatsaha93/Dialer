package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.example.ui.theme.LocalGlassColors
import com.example.ui.theme.glassEdgeBrush
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CallLogEntry
import com.example.data.model.CallType
import com.example.ui.theme.CallActionGreen
import com.example.ui.theme.CallActionRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CallLogItem(
    entry: CallLogEntry,
    onCallBack: (String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (icon, iconColor, typeLabel) = when (entry.callType) {
        CallType.INCOMING -> Triple(
            Icons.AutoMirrored.Filled.CallReceived,
            CallActionGreen,
            "Incoming"
        )
        CallType.OUTGOING -> Triple(
            Icons.AutoMirrored.Filled.CallMade,
            MaterialTheme.colorScheme.primary,
            "Outgoing"
        )
        CallType.MISSED -> Triple(
            Icons.AutoMirrored.Filled.CallMissed,
            CallActionRed,
            "Missed"
        )
    }

    val timeFormatter = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    val formattedTime = timeFormatter.format(Date(entry.timestamp))

    val durationText = if (entry.callType == CallType.MISSED) {
        "Missed"
    } else {
        val min = entry.durationSeconds / 60
        val sec = entry.durationSeconds % 60
        "%02d:%02d".format(min, sec)
    }

    // Clean number extraction (remove sip:, sips:, and @domain.com)
    val cleanNumber = entry.remoteUri
        .removePrefix("sip:")
        .removePrefix("sips:")
        .substringBefore("@")
        .trim()

    val rawDisplayName = entry.displayName.trim()
    val cleanDisplayName = if (rawDisplayName.startsWith("sip:", ignoreCase = true) || rawDisplayName.startsWith("sips:", ignoreCase = true)) {
        rawDisplayName.removePrefix("sip:").removePrefix("sips:").substringBefore("@").trim()
    } else {
        rawDisplayName
    }

    val hasDistinctContactName = cleanDisplayName.isNotBlank() && cleanDisplayName != cleanNumber

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("call_log_item_${entry.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = LocalGlassColors.current.fill
        ),
        border = BorderStroke(1.dp, glassEdgeBrush(LocalGlassColors.current))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = iconColor.copy(alpha = 0.15f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = typeLabel,
                    tint = iconColor,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                if (hasDistinctContactName) {
                    Text(
                        text = cleanDisplayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = cleanNumber,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = cleanNumber.ifBlank { "Unknown" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = formattedTime,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = durationText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (entry.callType == CallType.MISSED) CallActionRed else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = { onCallBack(entry.remoteUri) },
                modifier = Modifier.testTag("call_back_btn_${entry.id}")
            ) {
                Surface(
                    shape = CircleShape,
                    color = CallActionGreen.copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Call Back",
                        tint = CallActionGreen,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(18.dp)
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.testTag("delete_log_btn_${entry.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Log",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
