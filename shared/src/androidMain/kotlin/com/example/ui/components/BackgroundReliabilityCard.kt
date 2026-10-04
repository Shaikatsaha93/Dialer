package com.example.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.service.BackgroundReliability
import com.example.ui.theme.CallActionGreen
import com.example.ui.theme.CallActionRed

/**
 * Shows whether the phone lets the app ring in the background (Doze, battery saver, lock
 * screen) and opens the system screen that fixes each missing permission.
 */
@Composable
actual fun BackgroundReliabilityCard(modifier: Modifier) {
    val context = LocalContext.current
    // Bumped on resume so the status re-reads after returning from system settings
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val batteryOk = remember(refresh) { BackgroundReliability.isIgnoringBatteryOptimizations(context) }
    val fullScreenOk = remember(refresh) { BackgroundReliability.canUseFullScreenIntent(context) }

    fun open(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            context.startActivity(
                BackgroundReliability.appDetailsSettings(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReliabilityRow(
            title = "Battery optimization",
            okText = "Unrestricted • calls ring in power saving / Doze",
            badText = "Restricted • phone may stop the app and calls will be missed",
            ok = batteryOk,
            buttonText = "Allow",
            testTag = "btn_battery_optimization",
            onFix = { open(BackgroundReliability.batteryOptimizationIntent(context)) }
        )
        ReliabilityRow(
            title = "Full-screen incoming call",
            okText = "Allowed • call screen opens over the lock screen",
            badText = "Not allowed • only a small banner shows when locked",
            ok = fullScreenOk,
            buttonText = "Allow",
            testTag = "btn_full_screen_intent",
            onFix = { open(BackgroundReliability.fullScreenIntentSettings(context)) }
        )
        val autostart = remember { BackgroundReliability.oemAutostartIntent(context) }
        if (autostart != null) {
            FilledTonalButton(
                onClick = { open(autostart) },
                modifier = Modifier.testTag("btn_oem_autostart")
            ) {
                Text("Open Auto-launch settings", fontSize = 12.sp)
            }
        }
        Text(
            text = "Xiaomi, Oppo, Vivo, Realme, Samsung: also turn on Autostart / \"Allow background activity\" for this app in its App info.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            lineHeight = 16.sp
        )
        FilledTonalButton(
            onClick = { open(BackgroundReliability.appDetailsSettings(context)) },
            modifier = Modifier.testTag("btn_app_info")
        ) {
            Text("Open App info", fontSize = 12.sp)
        }
    }
}

@Composable
private fun ReliabilityRow(
    title: String,
    okText: String,
    badText: String,
    ok: Boolean,
    buttonText: String,
    testTag: String,
    onFix: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (ok) CallActionGreen else CallActionRed,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (ok) okText else badText,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f) else CallActionRed,
                lineHeight = 16.sp
            )
        }
        if (!ok) {
            FilledTonalButton(onClick = onFix, modifier = Modifier.testTag(testTag)) {
                Text(buttonText, fontSize = 12.sp)
            }
        }
    }
}
