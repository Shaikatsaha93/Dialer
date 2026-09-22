package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.CallState
import com.example.data.model.ConferenceParticipant
import com.example.ui.components.DtmfKeypad
import com.example.ui.theme.CallActionGreen
import com.example.ui.theme.CallActionRed
import com.example.ui.viewmodel.SoftphoneViewModel

fun formatCallerInfo(displayName: String?, remoteUri: String?): Pair<String, String> {
    val cleanUri = remoteUri.orEmpty().removePrefix("sip:").removePrefix("sips:")
    val userPart = if (cleanUri.contains("@")) cleanUri.substringBefore("@") else cleanUri
    val hostPart = if (cleanUri.contains("@")) cleanUri.substringAfter("@") else ""

    val title = when {
        !displayName.isNullOrBlank() && displayName != remoteUri && !displayName.startsWith("sip:") -> displayName
        userPart.isNotBlank() -> userPart
        cleanUri.isNotBlank() -> cleanUri
        else -> "Unknown Caller"
    }

    val subtitle = when {
        hostPart.isNotBlank() -> "sip:$userPart@$hostPart"
        cleanUri.isNotBlank() -> "sip:$cleanUri"
        else -> ""
    }
    return title to subtitle
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveCallScreen(
    viewModel: SoftphoneViewModel,
    onBackToDialer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val callState by viewModel.callState.collectAsStateWithLifecycle()
    val callDuration by viewModel.callDuration.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val isSpeakerOn by viewModel.isSpeakerOn.collectAsStateWithLifecycle()
    val isOnHold by viewModel.isOnHold.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var showKeypadOverlay by remember { mutableStateOf(false) }
    var showAddParticipantSheet by remember { mutableStateOf(false) }
    var pressedDtmfHistory by remember { mutableStateOf("") }
    var newParticipantInput by remember { mutableStateOf("") }

    val formattedDuration = remember(callDuration) {
        val min = callDuration / 60
        val sec = callDuration % 60
        "%02d:%02d".format(min, sec)
    }

    val isCallActive = callState is CallState.Connected || callState is CallState.Outgoing
    val isConference = (callState as? CallState.Connected)?.isConference == true
    val secondaryCall = (callState as? CallState.Connected)?.secondaryCall

    val infiniteTransition = rememberInfiniteTransition(label = "VoicePulse")
    val pulseRing1 by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseRing1"
    )
    val pulseRing2 by infiniteTransition.animateFloat(
        initialValue = 1.1f,
        targetValue = 1.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseRing2"
    )

    val (callerTitle, callerSubtitle, statusLabel) = when (val state = callState) {
        is CallState.Incoming -> {
            val (title, sub) = formatCallerInfo(state.displayName, state.remoteUri)
            Triple(title, sub, "Incoming Call...")
        }
        is CallState.Outgoing -> {
            val (title, sub) = formatCallerInfo(state.displayName, state.remoteUri)
            Triple(title, sub, if (state.isEarlyMediaOrRinging) "Ringing..." else "Calling...")
        }
        is CallState.Connected -> {
            if (state.isConference) {
                Triple("Conference Call", "${state.participants.size} Active Parties", formattedDuration)
            } else {
                val (title, sub) = formatCallerInfo(state.displayName, state.remoteUri)
                Triple(title, sub, if (state.isOnHold) "Call On Hold" else formattedDuration)
            }
        }
        is CallState.Disconnected -> {
            Triple("Call Ended", state.reason, "Disconnected")
        }
        is CallState.Idle -> {
            Triple("Ready", "No active call", "Idle")
        }
    }

    val isIncoming = callState is CallState.Incoming

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        val isCompact = screenHeight < 680.dp
        val avatarSize = if (isCompact) 84.dp else 108.dp

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = 560.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 20.dp, vertical = if (isCompact) 8.dp else 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Navigation Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBackToDialer,
                    modifier = Modifier.testTag("active_call_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Minimize Call",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isConference) Icons.Default.Group else Icons.Default.Lock,
                                contentDescription = "Security / Mode",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isConference) "CONFERENCE" else "HD SIP",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        text = "Developed By Shaikat",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.08f))

            // Avatar Visualizer (Conference group avatar or single caller avatar)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(avatarSize + 50.dp)
            ) {
                if (isCallActive) {
                    Box(
                        modifier = Modifier
                            .size(avatarSize)
                            .scale(pulseRing2)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    )
                    Box(
                        modifier = Modifier
                            .size(avatarSize)
                            .scale(pulseRing1)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                    )
                }

                Surface(
                    modifier = Modifier
                        .size(avatarSize)
                        .clip(CircleShape),
                    shape = CircleShape,
                    color = if (isConference) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    shadowElevation = 6.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isConference) Icons.Default.Group else Icons.Default.Call,
                            contentDescription = null,
                            tint = if (isConference) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(if (isCompact) 38.dp else 48.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Caller / Conference Details
            Text(
                text = callerTitle,
                style = if (isCompact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("active_call_title")
            )

            Spacer(modifier = Modifier.height(2.dp))

            if (callerSubtitle.isNotEmpty()) {
                Text(
                    text = callerSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Call status / duration timer badge
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isOnHold) Color(0xFFFFA000).copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = BorderStroke(
                    1.dp,
                    if (isOnHold) Color(0xFFFFA000).copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isOnHold) Color(0xFFFFA000) else CallActionGreen)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isOnHold) Color(0xFFFFA000) else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("active_call_timer_label")
                    )
                }
            }

            // Secondary Call & Merge Banner (When 2nd line is dialed)
            if (secondaryCall != null && !isConference) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Line 2: ${secondaryCall.displayName.ifBlank { secondaryCall.uri }}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = if (secondaryCall.isOnHold) "On Hold" else "Connected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(
                                onClick = { viewModel.swapActiveAndHeldCalls() },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.SwapHoriz, contentDescription = "Swap", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Swap", style = MaterialTheme.typography.labelSmall)
                            }

                            ElevatedButton(
                                onClick = { viewModel.mergeCallsIntoConference() },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.elevatedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(Icons.Default.CallMerge, contentDescription = "Merge", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Merge", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Conference Participant Roster (When in Conference)
            if (isConference && callState is CallState.Connected) {
                val participants = (callState as CallState.Connected).participants
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isCompact) 110.dp else 140.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Conference Members (${participants.size})",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            TextButton(
                                onClick = { showAddParticipantSheet = true },
                                modifier = Modifier.height(28.dp)
                            ) {
                                Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                        )

                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(participants, key = { it.id }) { participant ->
                                ConferenceParticipantRow(
                                    participant = participant,
                                    onToggleMute = { viewModel.toggleParticipantMute(participant.id) },
                                    onDrop = { viewModel.removeParticipantFromConference(participant.id) }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.15f))

            // Audio & Conference Controls Grid (Mute, Speaker, Hold, Add/Conference, DTMF Keypad)
            if (!isIncoming && callState !is CallState.Idle && callState !is CallState.Disconnected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CallControlButton(
                        icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (isMuted) "Muted" else "Mute",
                        isActive = isMuted,
                        activeColor = CallActionRed,
                        onClick = { viewModel.toggleMute() },
                        testTag = "control_mute_btn"
                    )

                    CallControlButton(
                        icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        label = if (isSpeakerOn) "Speaker" else "Earpiece",
                        isActive = isSpeakerOn,
                        activeColor = MaterialTheme.colorScheme.primary,
                        onClick = { viewModel.toggleSpeaker() },
                        testTag = "control_speaker_btn"
                    )

                    CallControlButton(
                        icon = if (isOnHold) Icons.Default.PlayArrow else Icons.Default.Pause,
                        label = if (isOnHold) "On Hold" else "Hold",
                        isActive = isOnHold,
                        activeColor = Color(0xFFFFA000),
                        onClick = { viewModel.toggleHold() },
                        testTag = "control_hold_btn"
                    )

                    CallControlButton(
                        icon = Icons.Default.GroupAdd,
                        label = if (isConference) "Add Conf" else "Conference",
                        isActive = isConference || secondaryCall != null,
                        activeColor = MaterialTheme.colorScheme.tertiary,
                        onClick = { showAddParticipantSheet = true },
                        testTag = "control_conference_btn"
                    )

                    CallControlButton(
                        icon = Icons.Default.Dialpad,
                        label = "Keypad",
                        isActive = showKeypadOverlay,
                        activeColor = MaterialTheme.colorScheme.secondary,
                        onClick = { showKeypadOverlay = !showKeypadOverlay },
                        testTag = "control_dtmf_overlay_btn"
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (isCompact) 14.dp else 24.dp))

            // Bottom Actions: Incoming (Accept + Decline) OR Active (Hang Up)
            if (isIncoming) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier
                            .size(if (isCompact) 62.dp else 70.dp)
                            .clip(CircleShape)
                            .clickable { viewModel.hangupCall() }
                            .testTag("decline_call_btn"),
                        shape = CircleShape,
                        color = CallActionRed,
                        border = BorderStroke(2.dp, Color.White.copy(alpha = 0.3f)),
                        shadowElevation = 8.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = "Decline Call",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .size(if (isCompact) 62.dp else 70.dp)
                            .clip(CircleShape)
                            .clickable { viewModel.acceptCall() }
                            .testTag("accept_call_btn"),
                        shape = CircleShape,
                        color = CallActionGreen,
                        border = BorderStroke(2.dp, Color.White.copy(alpha = 0.3f)),
                        shadowElevation = 8.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Accept Call",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier
                        .size(if (isCompact) 64.dp else 72.dp)
                        .clip(CircleShape)
                        .clickable {
                            viewModel.hangupCall()
                            onBackToDialer()
                        }
                        .testTag("end_call_button"),
                    shape = CircleShape,
                    color = CallActionRed,
                    border = BorderStroke(2.dp, Color.White.copy(alpha = 0.3f)),
                    shadowElevation = 10.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "End Call",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isCompact) 6.dp else 12.dp))
        }
    }

    // Add Participant to Conference Bottom Sheet
    if (showAddParticipantSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddParticipantSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isConference) "Add Participant to Conference" else "Start Conference Call",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Enter extension or SIP URI to add into conference session",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                )

                OutlinedTextField(
                    value = newParticipantInput,
                    onValueChange = { newParticipantInput = it },
                    label = { Text("Extension or SIP URI") },
                    placeholder = { Text("e.g. 1002 or sip:user@domain.com") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("conference_participant_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Quick Extension Suggestions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("1002", "1003", "Support", "Room 10").forEach { suggestion ->
                        FilledTonalButton(
                            onClick = { newParticipantInput = suggestion },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(suggestion, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = {
                            newParticipantInput = ""
                            showAddParticipantSheet = false
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }

                    ElevatedButton(
                        onClick = {
                            if (newParticipantInput.isNotBlank()) {
                                viewModel.addParticipantToCall(newParticipantInput)
                                newParticipantInput = ""
                                showAddParticipantSheet = false
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.elevatedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .weight(1.5f)
                            .testTag("conference_dial_add_btn")
                    ) {
                        Icon(Icons.Default.GroupAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Call & Add", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // In-call DTMF Keypad Bottom Sheet
    if (showKeypadOverlay) {
        ModalBottomSheet(
            onDismissRequest = { showKeypadOverlay = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "DTMF Keypad",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (pressedDtmfHistory.isNotEmpty()) {
                    Text(
                        text = pressedDtmfHistory,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                }

                DtmfKeypad(
                    onKeyPressed = { char ->
                        viewModel.sendDtmf(char)
                        pressedDtmfHistory += char
                    },
                    hapticFeedbackEnabled = settings.dtmfHapticFeedback,
                    modifier = Modifier.fillMaxWidth(),
                    keySize = 62.dp,
                    spacing = 10.dp
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ConferenceParticipantRow(
    participant: ConferenceParticipant,
    onToggleMute: () -> Unit,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Column {
                Text(
                    text = participant.displayName.ifBlank { participant.uri },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (participant.isMuted) "Muted" else "Speaking",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = if (participant.isMuted) CallActionRed else CallActionGreen
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(
                onClick = onToggleMute,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = if (participant.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Mute Participant",
                    tint = if (participant.isMuted) CallActionRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            IconButton(
                onClick = onDrop,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = "Drop Participant",
                    tint = CallActionRed,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (isActive) activeColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(
                1.dp,
                if (isActive) activeColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            ),
            modifier = Modifier
                .size(52.dp)
                .testTag(testTag),
            shadowElevation = if (isActive) 4.dp else 1.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
            color = if (isActive) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
