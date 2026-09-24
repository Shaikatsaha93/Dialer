package com.example

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import java.text.DateFormat
import java.util.concurrent.TimeUnit
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AppThemeMode
import com.example.data.model.CallState
import com.example.data.repository.LicenseState
import com.example.service.BackgroundReliability
import com.example.service.SipForegroundService
import com.example.ui.screens.LicenseScreen
import com.example.ui.screens.SoftphoneMainScreen
import com.example.ui.theme.SoftphoneTheme
import com.example.ui.viewmodel.SoftphoneViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: SoftphoneViewModel by viewModels { SoftphoneViewModel.Factory }

    /** Set when opened from a "new access request" notification. */
    private var openAdminRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        configureLockScreenFlags()
        openAdminRequest = intent?.getBooleanExtra(EXTRA_OPEN_ADMIN, false) == true

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val callState by viewModel.callState.collectAsStateWithLifecycle()
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            // Keep screen on during active or incoming call if configured
            LaunchedEffect(callState is CallState.Connected || callState is CallState.Incoming || callState is CallState.Outgoing) {
                val isActive = callState is CallState.Connected || callState is CallState.Incoming || callState is CallState.Outgoing
                if (isActive) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            // Start background standby service if keep-alive is active
            LaunchedEffect(settings.backgroundKeepAlive) {
                if (settings.backgroundKeepAlive) {
                    SipForegroundService.startStandby(this@MainActivity)
                    askBatteryExemptionOnce()
                }
            }

            val isDarkTheme = when (themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }

            // Status/navigation bar icons follow the app theme, not the system one
            LaunchedEffect(isDarkTheme) {
                val style = if (isDarkTheme) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            val license by SoftphoneApp.instance.licenseManager.state.collectAsStateWithLifecycle()

            // Warn once per launch when the subscription ends within 3 days
            LaunchedEffect((license as? LicenseState.Approved)?.expiresAt) {
                val expiresAt = (license as? LicenseState.Approved)?.expiresAt ?: return@LaunchedEffect
                val daysLeft = TimeUnit.MILLISECONDS.toDays(expiresAt.time - System.currentTimeMillis())
                if (daysLeft < 3) {
                    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(expiresAt)
                    Toast.makeText(this@MainActivity, "Subscription ends on $date. Contact the admin to renew.", Toast.LENGTH_LONG).show()
                }
            }

            SoftphoneTheme(darkTheme = isDarkTheme) {
                if (license is LicenseState.Approved) {
                    SoftphoneMainScreen(
                        viewModel = viewModel,
                        openAdminRequest = openAdminRequest,
                        onAdminOpened = { openAdminRequest = false }
                    )
                } else {
                    val licenseManager = SoftphoneApp.instance.licenseManager
                    LicenseScreen(
                        state = license,
                        installId = licenseManager.installId,
                        onSubmit = licenseManager::submitRequest,
                        onRetry = licenseManager::refresh
                    )
                }
            }
        }
    }

    /**
     * Without the battery-optimization exemption Doze cuts the network and ignores our wake
     * lock, so incoming calls are missed in power saving. Asked once; Settings shows the status.
     */
    private fun askBatteryExemptionOnce() {
        if (BackgroundReliability.isIgnoringBatteryOptimizations(this)) return
        val prefs = getSharedPreferences("background_reliability", MODE_PRIVATE)
        if (prefs.getBoolean("battery_exemption_asked", false)) return
        prefs.edit().putBoolean("battery_exemption_asked", true).apply()
        try {
            startActivity(BackgroundReliability.batteryOptimizationIntent(this))
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_ADMIN, false)) openAdminRequest = true
    }

    override fun onStart() {
        super.onStart()
        // A blocked/offline install re-checks each time the app is opened
        val licenseManager = SoftphoneApp.instance.licenseManager
        val state = licenseManager.state.value
        if (state is LicenseState.Denied || state is LicenseState.Error) licenseManager.refresh()
    }

    private fun configureLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    companion object {
        const val EXTRA_OPEN_ADMIN = "open_admin"
    }
}
