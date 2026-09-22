package com.example.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneCallback
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppSettings

@Composable
fun VoipSettingsSection(
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    onResetDefaults: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header with title and quick Reset button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(6.dp)) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "VoIP Settings",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "VoIP & Call Features",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Customize audio codecs, automation & network",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Quick Reset to Default Button in Header
                FilledTonalButton(
                    onClick = { showResetDialog = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("header_reset_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Reset",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

            // Section 1: Audio & DSP Processing
            SettingsCategoryHeader(title = "Audio & Processing", icon = Icons.Default.GraphicEq)

            SettingToggleItem(
                title = "Echo Cancellation (AEC)",
                description = "Filters out microphone feedback and speaker echo during calls",
                checked = settings.echoCancellation,
                testTag = "setting_echo_cancellation",
                onCheckedChange = { onSettingsChanged(settings.copy(echoCancellation = it)) }
            )

            SettingToggleItem(
                title = "Adaptive Rate Control",
                description = "Dynamically adjusts audio bitrate to handle jitter and packet loss",
                checked = settings.adaptiveRateControl,
                testTag = "setting_adaptive_rate",
                onCheckedChange = { onSettingsChanged(settings.copy(adaptiveRateControl = it)) }
            )

            SettingToggleItem(
                title = "Microphone Gain Boost (+6 dB)",
                description = "Amplifies microphone input for clearer voice pickup in quiet environments",
                checked = settings.micGainBoost,
                testTag = "setting_mic_boost",
                onCheckedChange = { onSettingsChanged(settings.copy(micGainBoost = it)) }
            )

            SettingToggleItem(
                title = "Early Media (Ringback Audio)",
                description = "Plays carrier or PBX custom ringback tones before call is answered",
                checked = settings.earlyMediaEnabled,
                testTag = "setting_early_media",
                onCheckedChange = { onSettingsChanged(settings.copy(earlyMediaEnabled = it)) }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

            // Section 2: Dialpad & Feedback
            SettingsCategoryHeader(title = "Keypad & Feedback", icon = Icons.Default.VolumeUp)

            SettingToggleItem(
                title = "DTMF Keypad Sounds",
                description = "Plays audible tones when pressing dialpad numbers",
                checked = settings.dtmfKeypadSound,
                testTag = "setting_dtmf_sound",
                onCheckedChange = { onSettingsChanged(settings.copy(dtmfKeypadSound = it)) }
            )

            SettingToggleItem(
                title = "Keypad Haptic Vibration",
                description = "Provides tactile vibration feedback upon tapping keypad buttons",
                checked = settings.dtmfHapticFeedback,
                testTag = "setting_dtmf_haptic",
                onCheckedChange = { onSettingsChanged(settings.copy(dtmfHapticFeedback = it)) }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

            // Section 3: Call Automation
            SettingsCategoryHeader(title = "Call Automation", icon = Icons.Default.PhoneCallback)

            SettingToggleItem(
                title = "Auto-Answer Incoming Calls",
                description = "Automatically accept incoming calls after a specified delay",
                checked = settings.autoAnswer,
                testTag = "setting_auto_answer",
                onCheckedChange = { onSettingsChanged(settings.copy(autoAnswer = it)) }
            )

            AnimatedVisibility(
                visible = settings.autoAnswer,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Auto-Answer Delay: ${settings.autoAnswerDelaySeconds} seconds",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(1, 2, 3, 5, 10).forEach { delaySec ->
                            FilterChip(
                                selected = settings.autoAnswerDelaySeconds == delaySec,
                                onClick = { onSettingsChanged(settings.copy(autoAnswerDelaySeconds = delaySec)) },
                                label = { Text("${delaySec}s") },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

            // Section 4: Network & NAT Traversal
            SettingsCategoryHeader(title = "Network & NAT Traversal", icon = Icons.Default.NetworkCheck)

            SettingToggleItem(
                title = "SIP Keep-Alive Packets",
                description = "Sends background keep-alive ping packets to keep NAT ports open",
                checked = settings.keepAliveEnabled,
                testTag = "setting_keep_alive",
                onCheckedChange = { onSettingsChanged(settings.copy(keepAliveEnabled = it)) }
            )

            SettingToggleItem(
                title = "STUN Server (NAT Traversal)",
                description = "Resolves public IP address behind strict firewalls and router NATs",
                checked = settings.stunEnabled,
                testTag = "setting_stun_enabled",
                onCheckedChange = { onSettingsChanged(settings.copy(stunEnabled = it)) }
            )

            AnimatedVisibility(
                visible = settings.stunEnabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                OutlinedTextField(
                    value = settings.stunServer,
                    onValueChange = { onSettingsChanged(settings.copy(stunServer = it)) },
                    label = { Text("STUN Server Address") },
                    placeholder = { Text("stun.l.google.com:19302") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .testTag("input_stun_server")
                )
            }

            SettingToggleItem(
                title = "IPv6 Protocol Support",
                description = "Enables dual-stack IPv6 routing alongside standard IPv4",
                checked = settings.ipv6Enabled,
                testTag = "setting_ipv6",
                onCheckedChange = { onSettingsChanged(settings.copy(ipv6Enabled = it)) }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

            // Section 5: Background & Lock Screen Operation
            SettingsCategoryHeader(title = "Background & Lock Screen", icon = Icons.Default.ScreenLockPortrait)

            SettingToggleItem(
                title = "Keep App Running in Background",
                description = "Keeps SIP service online and ready for incoming calls even when screen is locked or turned off",
                checked = settings.backgroundKeepAlive,
                testTag = "setting_background_keepalive",
                onCheckedChange = { onSettingsChanged(settings.copy(backgroundKeepAlive = it)) }
            )

            SettingToggleItem(
                title = "WakeLock & Wi-Fi Protection",
                description = "Prevents CPU sleep and Wi-Fi throttling during active VoIP calls when the screen turns off",
                checked = settings.wakeLockEnabled,
                testTag = "setting_wakelock_enabled",
                onCheckedChange = { onSettingsChanged(settings.copy(wakeLockEnabled = it)) }
            )

            SettingToggleItem(
                title = "Show Incoming Calls on Lock Screen",
                description = "Wakes up screen and presents incoming SIP caller screen directly over the device lock screen",
                checked = settings.showOnLockScreen,
                testTag = "setting_show_on_lockscreen",
                onCheckedChange = { onSettingsChanged(settings.copy(showOnLockScreen = it)) }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

            // Section 6: Firebase Cloud Messaging (FCM)
            SettingsCategoryHeader(title = "Push Notifications (FCM)", icon = Icons.Default.Notifications)

            SettingToggleItem(
                title = "FCM Background Push",
                description = "Receive SIP notifications and keep device alert even when app is closed",
                checked = settings.fcmPushEnabled,
                testTag = "setting_fcm_push_enabled",
                onCheckedChange = { onSettingsChanged(settings.copy(fcmPushEnabled = it)) }
            )

            SettingToggleItem(
                title = "VoIP Push Wake-up",
                description = "Instantly ring and display incoming call screen upon receiving VoIP push notification",
                checked = settings.fcmVoipWakeup,
                testTag = "setting_fcm_voip_wakeup",
                onCheckedChange = { onSettingsChanged(settings.copy(fcmVoipWakeup = it)) }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

            // Section 7: History & Logging
            SettingsCategoryHeader(title = "History & Logging", icon = Icons.Default.Settings)

            SettingToggleItem(
                title = "Save Call Logs",
                description = "Record outgoing, incoming, and missed calls in the History tab",
                checked = settings.recordCallHistory,
                testTag = "setting_record_history",
                onCheckedChange = { onSettingsChanged(settings.copy(recordCallHistory = it)) }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))

            // Dedicated Reset to Default Card at bottom
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Text(
                            text = "Restore Factory Defaults",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Reset audio processing, keypad tones, automation & network options",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { showResetDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("bottom_reset_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Reset All",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Reset All Settings to Default?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will restore all VoIP, Audio Codecs, Keypad Tones, Call Automation, Network, and Background preferences back to their recommended default values."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        if (onResetDefaults != null) {
                            onResetDefaults()
                        } else {
                            onSettingsChanged(AppSettings())
                        }
                        Toast.makeText(context, "All settings restored to default", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("confirm_reset_settings_button")
                ) {
                    Text("Reset to Default")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showResetDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SettingsCategoryHeader(
    title: String,
    icon: ImageVector
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SettingToggleItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                lineHeight = 16.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }
}
