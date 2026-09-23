package com.example

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AppThemeMode
import com.example.data.model.CallState
import com.example.service.SipForegroundService
import com.example.ui.screens.SoftphoneMainScreen
import com.example.ui.theme.SoftphoneTheme
import com.example.ui.viewmodel.SoftphoneViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: SoftphoneViewModel by viewModels { SoftphoneViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        configureLockScreenFlags()

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

            SoftphoneTheme(darkTheme = isDarkTheme) {
                SoftphoneMainScreen(viewModel = viewModel)
            }
        }
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
}

