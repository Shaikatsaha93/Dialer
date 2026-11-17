package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.AppGraph
import com.example.data.repository.DutyState
import com.example.platform.currentTimeMillis
import com.example.ui.platform.rememberLocationPermissionRequest
import com.example.ui.platform.rememberToast
import com.example.ui.theme.CallActionGreen
import com.example.ui.theme.CallActionRed
import com.example.ui.theme.LocalGlassColors
import com.example.ui.theme.glass
import kotlinx.coroutines.delay

/**
 * "On duty" switch for delivery riders: while on, the location is shared with the company's
 * dispatcher. Shown only to approved riders of a company (see DutyService).
 */
@Composable
fun DutyCard(modifier: Modifier = Modifier) {
    val duty = AppGraph.duty ?: return
    val state by duty.state.collectAsState()
    if (state is DutyState.Unavailable) return

    val toast = rememberToast()
    val requestPermission = rememberLocationPermissionRequest { granted ->
        if (granted) duty.setOnDuty(true) else toast("Allow location to go on duty")
    }
    val on = state !is DutyState.Off

    // Keeps "updated 20 s ago" current
    var now by remember { mutableLongStateOf(currentTimeMillis()) }
    LaunchedEffect(on) {
        while (on) {
            now = currentTimeMillis()
            delay(5_000)
        }
    }

    val (text, color) = when (val s = state) {
        is DutyState.On -> {
            val seconds = s.lastSentAt?.let { ((now - it) / 1000).coerceAtLeast(0) }
            when {
                seconds == null -> "Sharing your location… waiting for GPS"
                seconds < 60 -> "Sharing your location · updated ${seconds}s ago"
                else -> "Sharing your location · updated ${seconds / 60} min ago"
            } to CallActionGreen
        }
        is DutyState.Problem -> s.message to CallActionRed
        else -> "Turn on to share your location with your company" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(20.dp), LocalGlassColors.current)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("duty_card"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (on) Icons.Default.LocationOn else Icons.Default.LocationOff,
            contentDescription = null,
            tint = if (on) CallActionGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (on) "On duty" else "Off duty",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
        }
        Switch(
            checked = on,
            onCheckedChange = { checked -> if (checked) requestPermission() else duty.setOnDuty(false) },
            modifier = Modifier.testTag("duty_switch")
        )
    }
}
