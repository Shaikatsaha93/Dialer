package com.example.data.repository

import com.example.data.model.AppSettings
import com.example.data.model.AppThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App settings, stored in [prefs] (on Android the "app_voip_settings" SharedPreferences). */
class SettingsRepository(private val prefs: KeyValueStore) {

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        migrate()
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
            stunEnabled = prefs.getBoolean("stun_enabled", true),
            stunServer = prefs.getString("stun_server", "stun.l.google.com:19302") ?: "stun.l.google.com:19302",
            ipv6Enabled = prefs.getBoolean("ipv6_enabled", false),
            backgroundKeepAlive = prefs.getBoolean("background_keep_alive", true),
            wakeLockEnabled = prefs.getBoolean("wake_lock_enabled", true),
            showOnLockScreen = prefs.getBoolean("show_on_lock_screen", true),
            androidCallIntegration = prefs.getBoolean("android_call_integration", true),
            fcmPushEnabled = prefs.getBoolean("fcm_push_enabled", true),
            fcmVoipWakeup = prefs.getBoolean("fcm_voip_wakeup", true),
            recordCallHistory = prefs.getBoolean("record_call_history", true),
            recordCalls = prefs.getBoolean("record_calls", false),
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
        prefs.putBoolean("echo_cancellation", newSettings.echoCancellation)
        prefs.putBoolean("adaptive_rate_control", newSettings.adaptiveRateControl)
        prefs.putBoolean("mic_gain_boost", newSettings.micGainBoost)
        prefs.putBoolean("early_media_enabled", newSettings.earlyMediaEnabled)
        prefs.putBoolean("dtmf_keypad_sound", newSettings.dtmfKeypadSound)
        prefs.putBoolean("dtmf_haptic_feedback", newSettings.dtmfHapticFeedback)
        prefs.putBoolean("auto_answer", newSettings.autoAnswer)
        prefs.putInt("auto_answer_delay", newSettings.autoAnswerDelaySeconds)
        prefs.putBoolean("keep_alive_enabled", newSettings.keepAliveEnabled)
        prefs.putInt("keep_alive_interval", newSettings.keepAliveIntervalSeconds)
        prefs.putBoolean("stun_enabled", newSettings.stunEnabled)
        prefs.putString("stun_server", newSettings.stunServer)
        prefs.putBoolean("ipv6_enabled", newSettings.ipv6Enabled)
        prefs.putBoolean("background_keep_alive", newSettings.backgroundKeepAlive)
        prefs.putBoolean("wake_lock_enabled", newSettings.wakeLockEnabled)
        prefs.putBoolean("show_on_lock_screen", newSettings.showOnLockScreen)
        prefs.putBoolean("android_call_integration", newSettings.androidCallIntegration)
        prefs.putBoolean("fcm_push_enabled", newSettings.fcmPushEnabled)
        prefs.putBoolean("fcm_voip_wakeup", newSettings.fcmVoipWakeup)
        prefs.putBoolean("record_call_history", newSettings.recordCallHistory)
        prefs.putBoolean("record_calls", newSettings.recordCalls)
        prefs.putBoolean("detailed_debug_logging", newSettings.detailedDebugLogging)
        prefs.putString("theme_mode", newSettings.themeMode.name)
    }

    /**
     * Version 2: STUN on for everyone. It used to be off (and the switch did not really work), so
     * calls from behind a Wi-Fi router sent a private IP and the PBX could not send the voice back.
     */
    private fun migrate() {
        if (prefs.getInt(KEY_VERSION, 1) < 2) {
            prefs.putBoolean("stun_enabled", true)
            if (prefs.getString("stun_server", null).isNullOrBlank()) prefs.putString("stun_server", DEFAULT_STUN)
            prefs.putInt(KEY_VERSION, 2)
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        updateSettings(_settings.value.copy(themeMode = mode))
    }

    fun resetToDefaults() {
        updateSettings(AppSettings())
    }

    companion object {
        private const val KEY_VERSION = "settings_version"
        const val DEFAULT_STUN = "stun.l.google.com:19302"
    }
}
