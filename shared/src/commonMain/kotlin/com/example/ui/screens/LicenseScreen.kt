package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.repository.LicenseState
import com.example.ui.components.AdminSignInDialog
import com.example.ui.theme.CallActionRed
import com.example.ui.theme.GlassBackground
import com.example.ui.theme.LocalGlassColors
import com.example.ui.theme.glass

/** Shown instead of the app until the admin approves this install. */
@Composable
fun LicenseScreen(
    state: LicenseState,
    installId: String?,
    onSubmit: (name: String, phone: String, onResult: (String?) -> Unit) -> Unit,
    onRetry: () -> Unit
) {
    var showAdminSignIn by rememberSaveable { mutableStateOf(false) }
    if (showAdminSignIn) AdminSignInDialog(onDismiss = { showAdminSignIn = false })

    GlassBackground {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(24.dp), LocalGlassColors.current)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (state) {
                    LicenseState.Checking, is LicenseState.Approved -> {
                        CircularProgressIndicator()
                        Text("Checking access...", style = MaterialTheme.typography.bodyMedium)
                    }
                    LicenseState.NeedsRequest -> RequestForm(onSubmit)
                    is LicenseState.Pending -> {
                        StatusHeader(Icons.Default.HourglassTop, MaterialTheme.colorScheme.primary, "Waiting for approval")
                        Body(
                            "Your request has been sent" +
                                (if (state.name.isNotBlank()) " as ${state.name}" else "") +
                                ". The app opens by itself as soon as the admin approves it."
                        )
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                    LicenseState.Denied -> {
                        StatusHeader(Icons.Default.Block, CallActionRed, "Access disabled")
                        Body("This device is blocked or its subscription has expired. Contact the admin to renew it.")
                        OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("btn_license_retry")) {
                            Text("Check again")
                        }
                    }
                    is LicenseState.Error -> {
                        StatusHeader(Icons.Default.CloudOff, CallActionRed, "Cannot check access")
                        Body(state.message)
                        Button(onClick = onRetry, modifier = Modifier.testTag("btn_license_retry")) {
                            Text("Try again")
                        }
                    }
                }
                if (installId != null) {
                    Text(
                        text = "Device ID: ${installId.take(8)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { showAdminSignIn = true }, modifier = Modifier.testTag("btn_license_admin")) {
                    Text("Admin sign in", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun RequestForm(onSubmit: (String, String, (String?) -> Unit) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var sending by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    StatusHeader(Icons.Default.VerifiedUser, MaterialTheme.colorScheme.primary, "Request access")
    Body("This app needs the admin's approval. Enter your name and phone number and send the request.")

    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(60) },
        label = { Text("Your name") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("input_license_name")
    )
    OutlinedTextField(
        value = phone,
        onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' }.take(20) },
        label = { Text("Phone number") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("input_license_phone")
    )
    error?.let {
        Text(it, color = CallActionRed, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
    Button(
        onClick = {
            sending = true
            error = null
            onSubmit(name, phone) { result ->
                sending = false
                error = result
            }
        },
        enabled = !sending && name.isNotBlank() && phone.length >= 6,
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("btn_license_request")
    ) {
        if (sending) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
            Text("Send request")
        }
    }
}

@Composable
private fun StatusHeader(icon: ImageVector, tint: Color, title: String) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(48.dp))
    Spacer(Modifier.height(4.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}
