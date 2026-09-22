package com.example.data.model

data class AppSettings(
    // Audio & Processing
    val echoCancellation: Boolean = true,
    val adaptiveRateControl: Boolean = true,
    val micGainBoost: Boolean = false,
    val earlyMediaEnabled: Boolean = true,

    // Dialpad Feedback
    val dtmfKeypadSound: Boolean = true,
    val dtmfHapticFeedback: Boolean = true,

    // Call Automation
    val autoAnswer: Boolean = false,
    val autoAnswerDelaySeconds: Int = 3,

    // Network & SIP Protocol
    val keepAliveEnabled: Boolean = true,
    val keepAliveIntervalSeconds: Int = 30,
    val stunEnabled: Boolean = false,
    val stunServer: String = "stun.l.google.com:19302",
    val ipv6Enabled: Boolean = false,

    // Background & Lock Screen Operation
    val backgroundKeepAlive: Boolean = true,
    val wakeLockEnabled: Boolean = true,
    val showOnLockScreen: Boolean = true,

    // Diagnostics & History
    val recordCallHistory: Boolean = true,
    val detailedDebugLogging: Boolean = true,

    // Appearance
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM
)
