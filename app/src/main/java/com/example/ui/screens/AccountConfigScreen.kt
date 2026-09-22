package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AppSettings
import com.example.data.model.AppThemeMode
import com.example.data.model.RegistrationStatus
import com.example.data.model.SipAccount
import com.example.data.model.SipTransport
import com.example.ui.components.AccountCard
import com.example.ui.components.ThemeModeSelector
import com.example.ui.components.VoipSettingsSection
import com.example.ui.theme.CallActionGreen
import com.example.ui.theme.CallActionRed
import com.example.ui.viewmodel.SoftphoneViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountConfigScreen(
    viewModel: SoftphoneViewModel,
    modifier: Modifier = Modifier
) {
    val allAccounts by viewModel.allAccounts.collectAsStateWithLifecycle()
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()
    val registrationState by viewModel.registrationState.collectAsStateWithLifecycle()
    val registrationMessage by viewModel.registrationMessage.collectAsStateWithLifecycle()
    val diagnosticLogs by viewModel.diagnosticLogs.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    var showDiagnostics by remember { mutableStateOf(false) }

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("sip.linphone.org") }
    var port by remember { mutableStateOf("5060") }
    var displayName by remember { mutableStateOf("") }
    var selectedTransport by remember { mutableStateOf(SipTransport.UDP) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(allAccounts.isEmpty()) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isExpandedWidth = maxWidth >= 700.dp
        val horizontalPadding = if (isExpandedWidth) 24.dp else 16.dp

        if (isExpandedWidth) {
            // Dual-Pane Responsive Layout for Foldables / Tablets
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Left Column: Accounts and Add Account Form
                val leftScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxSize()
                        .verticalScroll(leftScrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ScreenHeaderSection(
                        showForm = showForm,
                        onToggleForm = { showForm = !showForm }
                    )

                    // Add / Edit Account Form
                    AnimatedVisibility(
                        visible = showForm || allAccounts.isEmpty(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        AddAccountFormCard(
                            username = username,
                            onUsernameChange = { username = it },
                            password = password,
                            onPasswordChange = { password = it },
                            domain = domain,
                            onDomainChange = { domain = it },
                            port = port,
                            onPortChange = { port = it },
                            displayName = displayName,
                            onDisplayNameChange = { displayName = it },
                            selectedTransport = selectedTransport,
                            onTransportChange = { selectedTransport = it },
                            passwordVisible = passwordVisible,
                            onTogglePasswordVisibility = { passwordVisible = !passwordVisible },
                            onFillDemo = {
                                username = "1001"
                                password = "secretPassword123"
                                domain = "sip.linphone.org"
                                port = "5060"
                                displayName = "Desk Extension"
                                selectedTransport = SipTransport.UDP
                            },
                            onClose = { showForm = false },
                            onSave = {
                                if (username.isNotBlank() && domain.isNotBlank()) {
                                    val newAccount = SipAccount(
                                        username = username.trim(),
                                        password = password,
                                        domain = domain.trim(),
                                        port = port.toIntOrNull() ?: 5060,
                                        transport = selectedTransport,
                                        displayName = displayName.trim(),
                                        isActive = true
                                    )
                                    viewModel.saveAccount(newAccount, makeActive = true)
                                    showForm = false
                                }
                            }
                        )
                    }

                    // Configured Accounts
                    AccountsListSection(
                        allAccounts = allAccounts,
                        activeAccount = activeAccount,
                        registrationState = registrationState,
                        onSelectActive = { viewModel.setActiveAccount(it) },
                        onDelete = { viewModel.deleteAccount(it) }
                    )
                }

                // Right Column: Settings, Theme & Diagnostics
                val rightScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .verticalScroll(rightScrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ThemeSelectorCard(
                        themeMode = themeMode,
                        onSelectMode = { viewModel.setThemeMode(it) }
                    )

                    RegistrationStatusCard(
                        registrationState = registrationState,
                        registrationMessage = registrationMessage,
                        activeAccount = activeAccount,
                        onRetry = { viewModel.retryRegistration() },
                        onSwitchTransport = { acc, alt ->
                            viewModel.saveAccount(acc.copy(transport = alt), makeActive = true)
                        }
                    )

                    DiagnosticsCard(
                        diagnosticLogs = diagnosticLogs,
                        showDiagnostics = showDiagnostics,
                        onToggleShow = { showDiagnostics = !showDiagnostics },
                        onCopyLogs = { clipboardManager.setText(AnnotatedString(diagnosticLogs.joinToString("\n"))) },
                        onClearLogs = { viewModel.clearLogs() },
                        isRegistrationFailed = registrationState == RegistrationStatus.FAILED
                    )

                    VoipSettingsSection(
                        settings = settings,
                        onSettingsChanged = { viewModel.updateSettings(it) }
                    )
                }
            }
        } else {
            // Standard Single-Column Layout for Phones and Foldable Cover Screens
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ScreenHeaderSection(
                    showForm = showForm,
                    onToggleForm = { showForm = !showForm }
                )

                ThemeSelectorCard(
                    themeMode = themeMode,
                    onSelectMode = { viewModel.setThemeMode(it) }
                )

                RegistrationStatusCard(
                    registrationState = registrationState,
                    registrationMessage = registrationMessage,
                    activeAccount = activeAccount,
                    onRetry = { viewModel.retryRegistration() },
                    onSwitchTransport = { acc, alt ->
                        viewModel.saveAccount(acc.copy(transport = alt), makeActive = true)
                    }
                )

                DiagnosticsCard(
                    diagnosticLogs = diagnosticLogs,
                    showDiagnostics = showDiagnostics,
                    onToggleShow = { showDiagnostics = !showDiagnostics },
                    onCopyLogs = { clipboardManager.setText(AnnotatedString(diagnosticLogs.joinToString("\n"))) },
                    onClearLogs = { viewModel.clearLogs() },
                    isRegistrationFailed = registrationState == RegistrationStatus.FAILED
                )

                // Add / Edit Account Form
                AnimatedVisibility(
                    visible = showForm || allAccounts.isEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    AddAccountFormCard(
                        username = username,
                        onUsernameChange = { username = it },
                        password = password,
                        onPasswordChange = { password = it },
                        domain = domain,
                        onDomainChange = { domain = it },
                        port = port,
                        onPortChange = { port = it },
                        displayName = displayName,
                        onDisplayNameChange = { displayName = it },
                        selectedTransport = selectedTransport,
                        onTransportChange = { selectedTransport = it },
                        passwordVisible = passwordVisible,
                        onTogglePasswordVisibility = { passwordVisible = !passwordVisible },
                        onFillDemo = {
                            username = "1001"
                            password = "secretPassword123"
                            domain = "sip.linphone.org"
                            port = "5060"
                            displayName = "Desk Extension"
                            selectedTransport = SipTransport.UDP
                        },
                        onClose = { showForm = false },
                        onSave = {
                            if (username.isNotBlank() && domain.isNotBlank()) {
                                val newAccount = SipAccount(
                                    username = username.trim(),
                                    password = password,
                                    domain = domain.trim(),
                                    port = port.toIntOrNull() ?: 5060,
                                    transport = selectedTransport,
                                    displayName = displayName.trim(),
                                    isActive = true
                                )
                                viewModel.saveAccount(newAccount, makeActive = true)
                                showForm = false
                            }
                        }
                    )
                }

                AccountsListSection(
                    allAccounts = allAccounts,
                    activeAccount = activeAccount,
                    registrationState = registrationState,
                    onSelectActive = { viewModel.setActiveAccount(it) },
                    onDelete = { viewModel.deleteAccount(it) }
                )

                VoipSettingsSection(
                    settings = settings,
                    onSettingsChanged = { viewModel.updateSettings(it) }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ScreenHeaderSection(
    showForm: Boolean,
    onToggleForm: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
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
                text = "Settings & Accounts",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Configure SIP credentials, VoIP codecs & app features",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (showForm) {
            FilledTonalButton(
                onClick = onToggleForm,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.testTag("toggle_add_account_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Form",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Close", fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = onToggleForm,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.testTag("toggle_add_account_btn")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Account",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ThemeSelectorCard(
    themeMode: AppThemeMode,
    onSelectMode: (AppThemeMode) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("theme_selector_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Appearance & Theme",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "System, Light, or Dark mode",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            ThemeModeSelector(
                currentMode = themeMode,
                onModeSelected = onSelectMode
            )
        }
    }
}

@Composable
private fun RegistrationStatusCard(
    registrationState: RegistrationStatus,
    registrationMessage: String,
    activeAccount: SipAccount?,
    onRetry: () -> Unit,
    onSwitchTransport: (SipAccount, SipTransport) -> Unit
) {
    val statusColor = when (registrationState) {
        RegistrationStatus.REGISTERED -> CallActionGreen
        RegistrationStatus.REGISTERING -> Color(0xFFFFA000)
        RegistrationStatus.FAILED -> CallActionRed
        RegistrationStatus.UNREGISTERED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("registration_status_banner"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = statusColor.copy(alpha = 0.12f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Registration: ${registrationState.name}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                    Text(
                        text = registrationMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (activeAccount != null && registrationState != RegistrationStatus.REGISTERING) {
                    IconButton(
                        onClick = onRetry,
                        modifier = Modifier.testTag("retry_registration_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Retry Registration",
                            tint = statusColor
                        )
                    }
                }
            }

            if (registrationState == RegistrationStatus.FAILED) {
                Text(
                    text = "💡 Tip: If credentials are correct, check if your provider requires TCP instead of UDP, or check if domain needs port (e.g. 5060).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )

                if (activeAccount != null) {
                    val currentTransport = activeAccount.transport
                    val alternateTransport = if (currentTransport == SipTransport.UDP) SipTransport.TCP else SipTransport.UDP
                    OutlinedButton(
                        onClick = { onSwitchTransport(activeAccount, alternateTransport) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("switch_transport_btn")
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Switch to ${alternateTransport.name} Transport & Retry", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(
    diagnosticLogs: List<String>,
    showDiagnostics: Boolean,
    onToggleShow: () -> Unit,
    onCopyLogs: () -> Unit,
    onClearLogs: () -> Unit,
    isRegistrationFailed: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("diagnostic_logs_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Connection Diagnostics (${diagnosticLogs.size} logs)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row {
                    if (diagnosticLogs.isNotEmpty()) {
                        IconButton(
                            onClick = onCopyLogs,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("copy_logs_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Logs",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onClearLogs,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("clear_logs_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear Logs",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = onToggleShow,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("toggle_logs_btn")
                    ) {
                        Icon(
                            imageVector = if (showDiagnostics) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (showDiagnostics) "Hide Logs" else "Show Logs",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (showDiagnostics || isRegistrationFailed) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E1E1E),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    val logsScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                            .verticalScroll(logsScrollState)
                    ) {
                        if (diagnosticLogs.isEmpty()) {
                            Text(
                                text = "No diagnostic events yet.\nTap 'Retry' to trigger registration and capture SIP logs.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF9E9E9E)
                                )
                            )
                        } else {
                            diagnosticLogs.forEach { logLine ->
                                val textColor = when {
                                    logLine.contains("Error", ignoreCase = true) || logLine.contains("Failed", ignoreCase = true) || logLine.contains("40", ignoreCase = true) || logLine.contains("50", ignoreCase = true) -> Color(0xFFFF6B6B)
                                    logLine.contains("Ok", ignoreCase = true) || logLine.contains("200", ignoreCase = true) || logLine.contains("successfully", ignoreCase = true) -> Color(0xFF81C784)
                                    logLine.contains("SIP", ignoreCase = true) -> Color(0xFF64B5F6)
                                    else -> Color(0xFFE0E0E0)
                                }
                                Text(
                                    text = logLine,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = textColor
                                    ),
                                    modifier = Modifier.padding(vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddAccountFormCard(
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    domain: String,
    onDomainChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    selectedTransport: SipTransport,
    onTransportChange: (SipTransport) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVisibility: () -> Unit,
    onFillDemo: () -> Unit,
    onClose: () -> Unit,
    onSave: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("account_form_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "New SIP Credentials",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = onFillDemo,
                        modifier = Modifier.testTag("fill_demo_credentials_btn")
                    ) {
                        Text("Fill Demo", fontSize = 12.sp)
                    }

                    // Direct Close Button in Form Header
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("form_close_header_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close form",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text("Username / Extension") },
                placeholder = { Text("e.g. 1001 or alice") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_username")
            )

            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onTogglePasswordVisibility) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle password visibility"
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_password")
            )

            OutlinedTextField(
                value = domain,
                onValueChange = onDomainChange,
                label = { Text("Domain / Proxy Server") },
                placeholder = { Text("e.g. sip.linphone.org") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_domain")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = port,
                    onValueChange = onPortChange,
                    label = { Text("Port") },
                    placeholder = { Text("5060") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("input_port")
                )

                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text("Display Name") },
                    placeholder = { Text("Optional") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1.5f)
                        .testTag("input_display_name")
                )
            }

            Text(
                text = "Transport Protocol",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SipTransport.values().forEach { transport ->
                    FilterChip(
                        selected = selectedTransport == transport,
                        onClick = { onTransportChange(transport) },
                        label = { Text(transport.name) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("transport_chip_${transport.name}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Action Buttons: Cancel and Save & Register
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onClose,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("cancel_account_button")
                ) {
                    Text("Cancel", fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = onSave,
                    enabled = username.isNotBlank() && domain.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1.6f)
                        .height(48.dp)
                        .testTag("save_account_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Save & Register", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun AccountsListSection(
    allAccounts: List<SipAccount>,
    activeAccount: SipAccount?,
    registrationState: RegistrationStatus,
    onSelectActive: (Long) -> Unit,
    onDelete: (SipAccount) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Configured Accounts (${allAccounts.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (allAccounts.isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            ) {
                Text(
                    text = "No accounts configured yet. Fill out the form above to add your SIP extension.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            allAccounts.forEach { account ->
                val isActive = (account.id == activeAccount?.id)
                val status = if (isActive) registrationState else RegistrationStatus.UNREGISTERED

                AccountCard(
                    account = account,
                    isActive = isActive,
                    currentStatus = status,
                    onSelectActive = { onSelectActive(account.id) },
                    onDelete = { onDelete(account) }
                )
            }
        }
    }
}
