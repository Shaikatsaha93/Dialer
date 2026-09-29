package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.data.model.AppSettings
import com.example.data.model.PushMessageItem

// Windows keeps running in the tray; there is no battery optimisation to switch off
@Composable
actual fun BackgroundReliabilityCard(modifier: Modifier) {}

// No FCM on Windows: the app stays registered while it runs
@Composable
actual fun FcmPushSection(
    fcmToken: String?,
    tokenStatus: String,
    receivedPushes: List<PushMessageItem>,
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    onRefreshToken: () -> Unit,
    onGenerateTestToken: () -> Unit,
    onClearPushHistory: () -> Unit,
    onSimulatePush: (title: String, body: String, isVoip: Boolean, callerUri: String, callerName: String) -> Unit,
    modifier: Modifier
) {}
