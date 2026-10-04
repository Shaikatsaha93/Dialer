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
    /** On by default: puts the public IP in the call's SDP, so the PBX can send the voice back. */
    val stunEnabled: Boolean = true,
    val stunServer: String = "stun.l.google.com:19302",
    val ipv6Enabled: Boolean = false,

    // Background & Lock Screen Operation
    val backgroundKeepAlive: Boolean = true,
    val wakeLockEnabled: Boolean = true,
    val showOnLockScreen: Boolean = true,
    /**
     * Hand incoming calls to Android's call system (Telecom). Some phones mute the microphone or
     * speaker of a VoIP call while Telecom holds it; turning this off lets the app own the audio.
     */
    val androidCallIntegration: Boolean = true,

    // Firebase Cloud Messaging (FCM)
    val fcmPushEnabled: Boolean = true,
    val fcmVoipWakeup: Boolean = true,

    // Diagnostics & History
    val recordCallHistory: Boolean = true,
    /** Record the audio of every call to a file on this phone. */
    val recordCalls: Boolean = false,
    val detailedDebugLogging: Boolean = true,

    // Appearance
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM
)
