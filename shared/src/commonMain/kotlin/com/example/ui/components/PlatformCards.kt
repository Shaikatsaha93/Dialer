package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.data.model.AppSettings
import com.example.data.model.PushMessageItem

/** Battery / full-screen permission status (Android only; nothing on Windows). */
@Composable
expect fun BackgroundReliabilityCard(modifier: Modifier = Modifier)

/** Turn call recording on/off and play, share or delete the recordings. */
@Composable
expect fun CallRecordingCard(
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    modifier: Modifier = Modifier
)

/** FCM push token and test pushes (Android only). */
@Composable
expect fun FcmPushSection(
    fcmToken: String?,
    tokenStatus: String,
    receivedPushes: List<PushMessageItem>,
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    onRefreshToken: () -> Unit,
    onGenerateTestToken: () -> Unit = {},
    onClearPushHistory: () -> Unit,
    onSimulatePush: (title: String, body: String, isVoip: Boolean, callerUri: String, callerName: String) -> Unit,
    modifier: Modifier = Modifier
)
