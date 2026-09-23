package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AccountBalance
import com.example.data.model.AppThemeMode
import com.example.data.model.CallState
import com.example.data.model.RegistrationStatus
import com.example.data.model.SipAccount
import com.example.ui.components.DtmfKeypad
import com.example.ui.components.ThemeModeSelector
import com.example.ui.theme.CallActionGreen
import com.example.ui.theme.CallActionRed
import com.example.ui.viewmodel.SoftphoneViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialerScreen(
    viewModel: SoftphoneViewModel,
    onNavigateToActiveCall: () -> Unit,
    onNavigateToAccounts: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dialerInput by viewModel.dialerInput.collectAsStateWithLifecycle()
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()
    val registrationState by viewModel.registrationState.collectAsStateWithLifecycle()
    val accountBalance by viewModel.accountBalance.collectAsStateWithLifecycle()
    val callState by viewModel.callState.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val isCallActive = callState !is CallState.Idle && callState !is CallState.Disconnected
    val matchedContactName = if (dialerInput.isNotBlank()) viewModel.getContactNameForUri(dialerInput) else null

    val infiniteTransition = rememberInfiniteTransition(label = "PulseAnimation")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWideLayout = maxWidth >= 600.dp
        val screenHeight = maxHeight
        val isCompactHeight = screenHeight < 680.dp

        if (isWideLayout) {
            // Foldables & Tablets Responsive Layout
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Column: Branding, Account Status, Display & Call Actions
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    DialerTopBar(
                        themeMode = themeMode,
                        onThemeSelect = { viewModel.setThemeMode(it) }
                    )

                    AccountStatusBar(
                        activeAccount = activeAccount,
                        registrationState = registrationState,
                        accountBalance = accountBalance,
                        pulseScale = pulseScale,
                        onClick = onNavigateToAccounts
                    )

                    OngoingCallBanner(
                        isCallActive = isCallActive,
                        onClick = onNavigateToActiveCall
                    )

                    NumberDisplayField(
                        dialerInput = dialerInput,
                        matchedContactName = matchedContactName,
                        isCompactHeight = false
                    )

                    CallActionsRow(
                        dialerInput = dialerInput,
                        activeAccount = activeAccount,
                        isCompactHeight = false,
                        onSimulateCall = { viewModel.simulateIncomingCall() },
                        onCall = {
                            if (dialerInput.isNotBlank()) {
                                viewModel.initiateCall()
                                onNavigateToActiveCall()
                            } else if (activeAccount != null) {
                                viewModel.setDialerInput("1002")
                            }
                        },
                        onBackspace = { viewModel.onBackspace() },
                        onClearAll = { viewModel.setDialerInput("") }
                    )
                }

                // Right Column: Centered Keypad
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                    ) {
                        Box(
                            modifier = Modifier.padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            DtmfKeypad(
                                onKeyPressed = { char -> viewModel.onDialerChar(char) },
                                hapticFeedbackEnabled = settings.dtmfHapticFeedback,
                                modifier = Modifier.fillMaxWidth(),
                                keySize = 72.dp,
                                spacing = 14.dp
                            )
                        }
                    }
                }
            }
        } else {
            // Standard Phones and Foldable Cover Screens
            val keypadKeySize = if (isCompactHeight) 58.dp else 68.dp
            val keypadSpacing = if (isCompactHeight) 8.dp else 12.dp
            val verticalPadding = if (isCompactHeight) 6.dp else 12.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = verticalPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                DialerTopBar(
                    themeMode = themeMode,
                    onThemeSelect = { viewModel.setThemeMode(it) }
                )

                Spacer(modifier = Modifier.height(6.dp))

                AccountStatusBar(
                    activeAccount = activeAccount,
                    registrationState = registrationState,
                    accountBalance = accountBalance,
                    pulseScale = pulseScale,
                    onClick = onNavigateToAccounts
                )

                OngoingCallBanner(
                    isCallActive = isCallActive,
                    onClick = onNavigateToActiveCall
                )

                Spacer(modifier = Modifier.weight(0.1f))

                NumberDisplayField(
                    dialerInput = dialerInput,
                    matchedContactName = matchedContactName,
                    isCompactHeight = isCompactHeight
                )

                Spacer(modifier = Modifier.height(if (isCompactHeight) 8.dp else 12.dp))

                DtmfKeypad(
                    onKeyPressed = { char -> viewModel.onDialerChar(char) },
                    hapticFeedbackEnabled = settings.dtmfHapticFeedback,
                    modifier = Modifier.fillMaxWidth(),
                    keySize = keypadKeySize,
                    spacing = keypadSpacing
                )

                Spacer(modifier = Modifier.height(if (isCompactHeight) 10.dp else 14.dp))

                CallActionsRow(
                    dialerInput = dialerInput,
                    activeAccount = activeAccount,
                    isCompactHeight = isCompactHeight,
                    onSimulateCall = { viewModel.simulateIncomingCall() },
                    onCall = {
                        if (dialerInput.isNotBlank()) {
                            viewModel.initiateCall()
                            onNavigateToActiveCall()
                        } else if (activeAccount != null) {
                            viewModel.setDialerInput("1002")
                        }
                    },
                    onBackspace = { viewModel.onBackspace() },
                    onClearAll = { viewModel.setDialerInput("") }
                )

                Spacer(modifier = Modifier.height(if (isCompactHeight) 6.dp else 10.dp))
            }
        }
    }
}

@Composable
private fun DialerTopBar(
    themeMode: AppThemeMode,
    onThemeSelect: (AppThemeMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Sensors,
                        contentDescription = "Dialer Logo",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "DIALER",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Developed By Shaikat",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 0.5.sp
                )
            }
        }

        ThemeModeSelector(
            currentMode = themeMode,
            onModeSelected = onThemeSelect
        )
    }
}

@Composable
private fun AccountStatusBar(
    activeAccount: SipAccount?,
    registrationState: RegistrationStatus,
    accountBalance: AccountBalance?,
    pulseScale: Float,
    onClick: () -> Unit
) {
    val statusDotColor = when (registrationState) {
        RegistrationStatus.REGISTERED -> CallActionGreen
        RegistrationStatus.REGISTERING -> Color(0xFFFFA000)
        RegistrationStatus.FAILED -> CallActionRed
        RegistrationStatus.UNREGISTERED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("dialer_account_status_bar"),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .scale(if (registrationState == RegistrationStatus.REGISTERING) pulseScale else 1f)
                    .clip(CircleShape)
                    .background(statusDotColor)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (activeAccount != null) {
                        "${activeAccount.username}@${activeAccount.domain}"
                    } else {
                        "No SIP Account (Tap to setup)"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (registrationState) {
                        RegistrationStatus.REGISTERED -> "SIP Online • Ready to Call"
                        RegistrationStatus.REGISTERING -> "Connecting to SIP Server..."
                        RegistrationStatus.FAILED -> "Registration Failed • Check Settings"
                        RegistrationStatus.UNREGISTERED -> "Offline • Unregistered"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = statusDotColor
                )
            }

            if (accountBalance != null && registrationState == RegistrationStatus.REGISTERED) {
                val low = accountBalance.amount < 5.0
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .testTag("dialer_balance")
                ) {
                    Text(
                        text = "Balance",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = accountBalance.formatted,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (low) CallActionRed else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "SIP Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun OngoingCallBanner(
    isCallActive: Boolean,
    onClick: () -> Unit
) {
    AnimatedVisibility(
        visible = isCallActive,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clickable(onClick = onClick)
                .testTag("ongoing_call_banner"),
            colors = CardDefaults.cardColors(containerColor = CallActionGreen.copy(alpha = 0.15f)),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, CallActionGreen.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneInTalk,
                    contentDescription = "Active Call",
                    tint = CallActionGreen,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Call in progress - Tap to return",
                    style = MaterialTheme.typography.labelMedium,
                    color = CallActionGreen,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun NumberDisplayField(
    dialerInput: String,
    matchedContactName: String? = null,
    isCompactHeight: Boolean
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = if (isCompactHeight) 8.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (dialerInput.isEmpty()) "Enter number or SIP URI" else dialerInput,
                fontSize = if (dialerInput.length > 15) 20.sp else if (dialerInput.length > 10) 24.sp else 28.sp,
                fontWeight = FontWeight.Bold,
                color = if (dialerInput.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("dialer_display_text")
            )
            if (!matchedContactName.isNullOrBlank()) {
                Text(
                    text = "👤 $matchedContactName",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallActionsRow(
    dialerInput: String,
    activeAccount: SipAccount?,
    isCompactHeight: Boolean,
    onSimulateCall: () -> Unit,
    onCall: () -> Unit,
    onBackspace: () -> Unit,
    onClearAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Quick Test Simulation Button
        Surface(
            onClick = onSimulateCall,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
            modifier = Modifier
                .size(if (isCompactHeight) 48.dp else 52.dp)
                .testTag("simulate_call_btn")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Test Incoming Call",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Big Glowing Emerald Call Button
        Surface(
            modifier = Modifier
                .size(if (isCompactHeight) 64.dp else 70.dp)
                .clip(CircleShape)
                .clickable(onClick = onCall)
                .testTag("dialer_call_button"),
            shape = CircleShape,
            color = CallActionGreen,
            border = BorderStroke(2.dp, Color.White.copy(alpha = 0.25f)),
            shadowElevation = 6.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Initiate Call",
                    tint = Color.White,
                    modifier = Modifier.size(if (isCompactHeight) 30.dp else 34.dp)
                )
            }
        }

        // Backspace Button
        Surface(
            modifier = Modifier
                .size(if (isCompactHeight) 48.dp else 52.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onBackspace,
                    onLongClick = onClearAll
                )
                .testTag("dialer_backspace_button"),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Backspace",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
