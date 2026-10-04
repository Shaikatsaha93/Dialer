package com.example.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.AppGraph
import com.example.data.model.AppThemeMode
import com.example.data.model.CallState
import com.example.data.model.CallType
import com.example.data.repository.CallLogRepository
import com.example.data.repository.ChatRepository
import com.example.data.repository.LicenseState
import com.example.data.repository.SettingsRepository
import com.example.data.repository.SipAccountRepository
import com.example.desktop.firebase.DesktopAdminManager
import com.example.desktop.firebase.DesktopFirebaseConfig
import com.example.desktop.firebase.DesktopLicenseManager
import com.example.desktop.firebase.FirebaseRest
import com.example.desktop.sip.DesktopSipManager
import com.example.platform.formatMediumDate
import com.example.sip.CallEvent
import com.example.ui.platform.DesktopToast
import com.example.ui.screens.LicenseScreen
import com.example.ui.screens.SoftphoneMainScreen
import com.example.ui.theme.SoftphoneTheme
import com.example.ui.viewmodel.SoftphoneViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Same as packageVersion in desktopApp/build.gradle.kts */
private const val DESKTOP_VERSION = "1.0.0"

/** Events the window reacts to (tray notifications, bringing the window up for a call). */
private object DesktopEvents {
    var notification by mutableStateOf<Notification?>(null)
}

fun main() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Services (the Windows counterpart of SoftphoneApp.onCreate on Android)
    val database = openDesktopDatabase()
    val accountRepository = SipAccountRepository(database.sipAccountDao())
    val callLogRepository = CallLogRepository(database.callLogDao())
    val settingsRepository = SettingsRepository(PreferencesStore("settings"))
    val sipManager = DesktopSipManager()
    val contacts = NoContacts()
    val firebase = FirebaseRest(DesktopFirebaseConfig.API_KEY, DesktopFirebaseConfig.PROJECT_ID)
    val licensePrefs = PreferencesStore("license")
    val license = DesktopLicenseManager(firebase, licensePrefs, accountRepository, appScope)
    val admin = DesktopAdminManager(firebase, license, licensePrefs, appScope) { title, text ->
        DesktopEvents.notification = Notification(title, text, Notification.Type.Info)
    }
    val chat = ChatRepository(database.chatDao(), sipManager, contacts, { _, title, text ->
        DesktopEvents.notification = Notification(title, text, Notification.Type.Info)
    }, appScope)

    AppGraph.sipManager = sipManager
    AppGraph.accountRepository = accountRepository
    AppGraph.callLogRepository = callLogRepository
    AppGraph.settingsRepository = settingsRepository
    AppGraph.contacts = contacts
    AppGraph.push = NoPush()
    AppGraph.license = license
    AppGraph.admin = admin
    AppGraph.chatRepository = chat
    AppGraph.appVersion = DESKTOP_VERSION

    sipManager.applySettings(settingsRepository.settings.value)
    sipManager.initializeSdk()
    license.start()
    chat.start()

    // Register only while the admin has approved this install
    appScope.launch {
        combine(accountRepository.activeAccount, license.state) { account, state ->
            account.takeIf { state is LicenseState.Approved }
        }.distinctUntilChanged().collectLatest { account ->
            if (account != null) sipManager.registerAccount(account) else sipManager.unregisterCurrentAccount()
        }
    }
    appScope.launch {
        license.isAdmin.collect { isAdmin -> if (isAdmin) admin.startWatching() else admin.stopWatching() }
    }
    appScope.launch {
        sipManager.callEvents.collect { event ->
            if (event is CallEvent.CallEnded && settingsRepository.settings.value.recordCallHistory) {
                callLogRepository.addLog(
                    remoteUri = event.remoteUri,
                    displayName = event.displayName.takeIf { it.isNotBlank() && !it.startsWith("sip:") } ?: "",
                    callType = when {
                        event.wasMissed -> CallType.MISSED
                        event.isIncoming -> CallType.INCOMING
                        else -> CallType.OUTGOING
                    },
                    durationSeconds = event.durationSeconds
                )
            }
        }
    }

    application(exitProcessOnExit = true) {
        var windowVisible by remember { mutableStateOf(true) }
        val trayState = rememberTrayState()
        val windowState = rememberWindowState(size = DpSize(1100.dp, 760.dp), position = WindowPosition(Alignment.Center))
        val callState by sipManager.callState.collectAsState()
        val appIcon = remember { BitmapPainter(useResource("dialer_icon.png", ::loadImageBitmap)) }

        fun quit() {
            sipManager.shutdown()
            exitApplication()
        }

        // Closing the window keeps the app in the tray, so incoming calls still ring
        Tray(
            icon = appIcon,
            state = trayState,
            tooltip = "Dialer",
            onAction = { windowVisible = true },
            menu = {
                Item("Open Dialer", onClick = { windowVisible = true })
                Item("Quit", onClick = { quit() })
            }
        )

        LaunchedEffect(DesktopEvents.notification) {
            DesktopEvents.notification?.let {
                trayState.sendNotification(it)
                DesktopEvents.notification = null
            }
        }

        // An incoming call brings the window to the front
        LaunchedEffect(callState is CallState.Incoming) {
            if (callState is CallState.Incoming) {
                windowVisible = true
                windowState.isMinimized = false
                val incoming = callState as CallState.Incoming
                trayState.sendNotification(
                    Notification("Incoming call", incoming.displayName.ifBlank { incoming.remoteUri }, Notification.Type.Info)
                )
            }
        }

        Window(
            onCloseRequest = { windowVisible = false },
            visible = windowVisible,
            state = windowState,
            title = "Dialer",
            icon = appIcon,
            alwaysOnTop = callState is CallState.Incoming
        ) {
            DesktopApp()
        }
    }
}

@Composable
private fun DesktopApp() {
    val viewModel: SoftphoneViewModel = viewModel(factory = SoftphoneViewModel.Factory)
    val themeMode by viewModel.themeMode.collectAsState()
    val license by AppGraph.license.state.collectAsState()
    val dark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    // Warn when the subscription ends within 3 days
    LaunchedEffect((license as? LicenseState.Approved)?.expiresAt) {
        val expiresAt = (license as? LicenseState.Approved)?.expiresAt ?: return@LaunchedEffect
        if (expiresAt - System.currentTimeMillis() < 3 * 24 * 60 * 60 * 1000L) {
            DesktopToast.show("Subscription ends on ${formatMediumDate(expiresAt)}. Contact the admin to renew.")
        }
    }

    SoftphoneTheme(darkTheme = dark) {
        Box(Modifier.fillMaxSize()) {
            if (license is LicenseState.Approved) {
                SoftphoneMainScreen(viewModel = viewModel)
            } else {
                LicenseScreen(
                    state = license,
                    installId = AppGraph.license.installId,
                    onSubmit = AppGraph.license::submitRequest,
                    onRetry = AppGraph.license::refresh
                )
            }
            DesktopToastHost(Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Shows [DesktopToast] messages for a few seconds, like a Toast on Android. */
@Composable
private fun DesktopToastHost(modifier: Modifier = Modifier) {
    val message = DesktopToast.message
    LaunchedEffect(message) {
        if (message != null) {
            delay(3500)
            DesktopToast.dismiss()
        }
    }
    AnimatedVisibility(visible = message != null, modifier = modifier.padding(24.dp)) {
        Snackbar { Text(message.orEmpty()) }
    }
}
