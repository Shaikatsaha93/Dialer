package com.example.data.repository

import android.content.Context
import com.example.data.model.AppSettings
import com.example.data.model.AppThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("app_voip_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        return AppSettings(
            echoCancellation = prefs.getBoolean("echo_cancellation", true),
            adaptiveRateControl = prefs.getBoolean("adaptive_rate_control", true),
            micGainBoost = prefs.getBoolean("mic_gain_boost", false),
            earlyMediaEnabled = prefs.getBoolean("early_media_enabled", true),
            dtmfKeypadSound = prefs.getBoolean("dtmf_keypad_sound", true),
            dtmfHapticFeedback = prefs.getBoolean("dtmf_haptic_feedback", true),
            autoAnswer = prefs.getBoolean("auto_answer", false),
            autoAnswerDelaySeconds = prefs.getInt("auto_answer_delay", 3),
            keepAliveEnabled = prefs.getBoolean("keep_alive_enabled", true),
            keepAliveIntervalSeconds = prefs.getInt("keep_alive_interval", 30),
            stunEnabled = prefs.getBoolean("stun_enabled", false),
            stunServer = prefs.getString("stun_server", "stun.l.google.com:19302") ?: "stun.l.google.com:19302",
            ipv6Enabled = prefs.getBoolean("ipv6_enabled", false),
            backgroundKeepAlive = prefs.getBoolean("background_keep_alive", true),
            wakeLockEnabled = prefs.getBoolean("wake_lock_enabled", true),
            showOnLockScreen = prefs.getBoolean("show_on_lock_screen", true),
            fcmPushEnabled = prefs.getBoolean("fcm_push_enabled", true),
            fcmVoipWakeup = prefs.getBoolean("fcm_voip_wakeup", true),
            recordCallHistory = prefs.getBoolean("record_call_history", true),
            detailedDebugLogging = prefs.getBoolean("detailed_debug_logging", true),
            themeMode = try {
                AppThemeMode.valueOf(prefs.getString("theme_mode", AppThemeMode.SYSTEM.name) ?: AppThemeMode.SYSTEM.name)
            } catch (_: Exception) {
                AppThemeMode.SYSTEM
            }
        )
    }

    fun updateSettings(newSettings: AppSettings) {
        _settings.value = newSettings
        prefs.edit()
            .putBoolean("echo_cancellation", newSettings.echoCancellation)
            .putBoolean("adaptive_rate_control", newSettings.adaptiveRateControl)
            .putBoolean("mic_gain_boost", newSettings.micGainBoost)
            .putBoolean("early_media_enabled", newSettings.earlyMediaEnabled)
            .putBoolean("dtmf_keypad_sound", newSettings.dtmfKeypadSound)
            .putBoolean("dtmf_haptic_feedback", newSettings.dtmfHapticFeedback)
            .putBoolean("auto_answer", newSettings.autoAnswer)
            .putInt("auto_answer_delay", newSettings.autoAnswerDelaySeconds)
            .putBoolean("keep_alive_enabled", newSettings.keepAliveEnabled)
            .putInt("keep_alive_interval", newSettings.keepAliveIntervalSeconds)
            .putBoolean("stun_enabled", newSettings.stunEnabled)
            .putString("stun_server", newSettings.stunServer)
            .putBoolean("ipv6_enabled", newSettings.ipv6Enabled)
            .putBoolean("background_keep_alive", newSettings.backgroundKeepAlive)
            .putBoolean("wake_lock_enabled", newSettings.wakeLockEnabled)
            .putBoolean("show_on_lock_screen", newSettings.showOnLockScreen)
            .putBoolean("fcm_push_enabled", newSettings.fcmPushEnabled)
            .putBoolean("fcm_voip_wakeup", newSettings.fcmVoipWakeup)
            .putBoolean("record_call_history", newSettings.recordCallHistory)
            .putBoolean("detailed_debug_logging", newSettings.detailedDebugLogging)
            .putString("theme_mode", newSettings.themeMode.name)
            .apply()
    }

    fun setThemeMode(mode: AppThemeMode) {
        updateSettings(_settings.value.copy(themeMode = mode))
    }

    fun resetToDefaults() {
        updateSettings(AppSettings())
    }
}
