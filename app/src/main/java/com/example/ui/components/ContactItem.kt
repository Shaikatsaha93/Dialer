package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.PhoneContact
import com.example.ui.theme.CallActionGreen

@Composable
fun ContactItem(
    contact: PhoneContact,
    onCallClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val initial = contact.name.trim().take(1).uppercase().ifBlank { "#" }

    // Consistent color derived from name
    val avatarColors = listOf(
        Color(0xFF3B82F6),
        Color(0xFF10B981),
        Color(0xFF8B5CF6),
        Color(0xFFF59E0B),
        Color(0xFFEC4899),
        Color(0xFF06B6D4)
    )
    val colorIndex = kotlin.math.abs(contact.name.hashCode()) % avatarColors.size
    val avatarColor = avatarColors[colorIndex]

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCallClick(contact.primaryNumber) }
            .testTag("contact_item_${contact.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar with initials
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = avatarColor.copy(alpha = 0.2f),
                border = BorderStroke(1.5.dp, avatarColor.copy(alpha = 0.5f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = initial,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = avatarColor
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = contact.primaryNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (contact.allNumbers.size > 1) {
                    Text(
                        text = "+${contact.allNumbers.size - 1} more numbers",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    )
                }
            }

            // Direct Call Button
            Surface(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable { onCallClick(contact.primaryNumber) }
                    .testTag("call_contact_${contact.id}"),
                shape = CircleShape,
                color = CallActionGreen.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Call ${contact.name}",
                        tint = CallActionGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
