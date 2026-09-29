package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.SoftphoneApp
import com.example.data.model.CallState
import com.example.data.model.RegistrationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SipForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var stateCollectJob: Job? = null
    private var registrationCollectJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    // Foreground service type currently in effect (-1 = not in the foreground yet)
    private var foregroundType = -1
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                val callState = SoftphoneApp.instance.sipManager.callState.value
                val keepAlive = SoftphoneApp.instance.settingsRepository.settings.value.backgroundKeepAlive
                if (callState is CallState.Idle && !keepAlive) {
                    releaseLocks()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_HANGUP -> {
                SoftphoneApp.instance.sipManager.hangupCall()
                val keepAlive = SoftphoneApp.instance.settingsRepository.settings.value.backgroundKeepAlive
                if (!keepAlive) {
                    releaseLocks()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_ANSWER -> {
                SoftphoneApp.instance.sipManager.acceptCall()
            }
            ACTION_TOGGLE_MUTE -> {
                SoftphoneApp.instance.sipManager.toggleMute()
            }
            ACTION_TOGGLE_SPEAKER -> {
                SoftphoneApp.instance.sipManager.toggleSpeaker()
            }
            ACTION_START_SERVICE, ACTION_START_STANDBY -> {
                startForegroundWithNotification("SIP Softphone Service", "Background service running")
                observeCallState()
                observeRegistration()
            }
            else -> {
                startForegroundWithNotification("SIP Softphone Service", "Background service active")
                observeCallState()
                observeRegistration()
            }
        }
        return START_STICKY
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SoftphoneApp::VoipKeepAliveWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.d(TAG, "Partial WakeLock acquired for screen-off / lock-screen VoIP reliability")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire WakeLock: ${e.message}")
        }

        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager.createWifiLock(lockType, "SoftphoneApp::VoipWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.d(TAG, "Wi-Fi lock acquired for uninterrupted VoIP media stream")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire WifiLock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock: ${e.message}")
        }

        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WifiLock: ${e.message}")
        }
    }

    private fun observeCallState() {
        stateCollectJob?.cancel()
        stateCollectJob = scope.launch {
            SoftphoneApp.instance.sipManager.callState.collectLatest { state ->
                val keepAlive = SoftphoneApp.instance.settingsRepository.settings.value.backgroundKeepAlive
                when (state) {
                    is CallState.Idle -> {
                        if (keepAlive) {
                            val regStatus = SoftphoneApp.instance.sipManager.registrationState.value
                            val sub = if (regStatus == RegistrationStatus.REGISTERED) {
                                "SIP Online • Background & Lock Screen Active"
                            } else {
                                "SIP Standby • Ready for incoming calls"
                            }
                            updateNotification("SIP Softphone Standby", sub, isOngoingCall = false)
                        } else {
                            releaseLocks()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                    is CallState.Incoming -> {
                        acquireLocks()
                        updateNotification("Incoming SIP Call", state.displayName.ifBlank { state.remoteUri }, isOngoingCall = true, isIncoming = true)
                    }
                    is CallState.Outgoing -> {
                        acquireLocks()
                        updateNotification("Calling...", state.displayName.ifBlank { state.remoteUri }, isOngoingCall = true)
                    }
                    is CallState.Connected -> {
                        acquireLocks()
                        val durationText = formatSeconds(state.durationSeconds)
                        val title = if (state.isConference) {
                            "👥 Conference Call (${state.participants.size + 1} parties) [$durationText]"
                        } else {
                            "Active SIP Call [$durationText]"
                        }
                        val muteText = if (state.isMuted) " [Muted]" else ""
                        val speakerText = if (state.isSpeakerOn) " [Speaker]" else ""
                        val holdText = if (state.isOnHold) " [On Hold]" else ""
                        val callerDesc = if (state.isConference) {
                            "Conference: ${state.participants.joinToString(", ") { it.displayName.ifBlank { it.uri } }}"
                        } else {
                            state.displayName.ifBlank { state.remoteUri }
                        }
                        updateNotification(title, "$callerDesc$muteText$speakerText$holdText", isOngoingCall = true, isMuted = state.isMuted, isSpeakerOn = state.isSpeakerOn)
                    }
                    is CallState.Disconnected -> {
                        updateNotification("Call Ended", state.reason, isOngoingCall = false)
                    }
                }
            }
        }
    }

    private fun observeRegistration() {
        registrationCollectJob?.cancel()
        registrationCollectJob = scope.launch {
            SoftphoneApp.instance.sipManager.registrationState.collectLatest { regState ->
                val callState = SoftphoneApp.instance.sipManager.callState.value
                val keepAlive = SoftphoneApp.instance.settingsRepository.settings.value.backgroundKeepAlive
                if (callState is CallState.Idle && keepAlive) {
                    val message = when (regState) {
                        RegistrationStatus.REGISTERED -> "SIP Online • Active in Background & Lock Screen"
                        RegistrationStatus.REGISTERING -> "Registering with SIP Server..."
                        RegistrationStatus.FAILED -> "Registration Failed • Retrying in background"
                        RegistrationStatus.UNREGISTERED -> "SIP Standby • Unregistered"
                    }
                    updateNotification("SIP Softphone Standby", message, isOngoingCall = false)
                }
            }
        }
    }

    private fun startForegroundWithNotification(title: String, text: String) {
        val inCall = SoftphoneApp.instance.sipManager.callState.value !is CallState.Idle
        foregroundType = -1
        showForeground(buildNotification(title, text, isOngoingCall = false), inCall)
    }

    /**
     * Idle standby runs as "specialUse" (Android 14+): Android 15 refuses to start a "phoneCall"
     * service from BOOT_COMPLETED, and an idle registration is not a call. While a call is
     * ringing or active the service is switched to "phoneCall".
     */
    private fun serviceType(inCall: Boolean): Int = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> 0
        inCall -> ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or microphoneType()
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    }

    /**
     * During a call also "microphone": Android 11+ only lets a background app record while a
     * foreground service of that type runs, otherwise the other side hears silence once the
     * screen locks. Only with the permission granted, or startForeground throws.
     */
    private fun microphoneType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0

    private fun showForeground(notification: Notification, inCall: Boolean) {
        val type = serviceType(inCall)
        if (type == foregroundType) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundType = type
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting foreground service: ${e.message}")
            try {
                // Microphone type refused (started from the background): keep the call service at least
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL && inCall) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                    foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    return
                }
                startForeground(NOTIFICATION_ID, notification)
            } catch (fallbackError: Throwable) {
                Log.e(TAG, "Fallback startForeground error: ${fallbackError.message}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
        }
    }

    private fun updateNotification(
        title: String,
        text: String,
        isOngoingCall: Boolean,
        isIncoming: Boolean = false,
        isMuted: Boolean = false,
        isSpeakerOn: Boolean = false
    ) {
        showForeground(buildNotification(title, text, isOngoingCall, isIncoming, isMuted, isSpeakerOn), isOngoingCall)
    }

    private fun buildNotification(
        title: String,
        text: String,
        isOngoingCall: Boolean,
        isIncoming: Boolean = false,
        isMuted: Boolean = false,
        isSpeakerOn: Boolean = false
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Idle standby goes to a silent channel so it does not pop up on every app start;
        // calls stay on the high-importance channel (heads-up / full screen)
        val isCall = isOngoingCall || isIncoming
        val builder = NotificationCompat.Builder(this, if (isCall) CHANNEL_ID else CHANNEL_STANDBY_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(if (isCall) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_CALL)

        if (isIncoming) {
            val answerIntent = Intent(this, SipForegroundService::class.java).apply { action = ACTION_ANSWER }
            val answerPendingIntent = PendingIntent.getService(this, 10, answerIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val declineIntent = Intent(this, SipForegroundService::class.java).apply { action = ACTION_HANGUP }
            val declinePendingIntent = PendingIntent.getService(this, 11, declineIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            // Opens the call screen over the lock screen (like WhatsApp) instead of only a banner
            val fullScreenPendingIntent = PendingIntent.getActivity(
                this,
                12,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
            builder.addAction(android.R.drawable.ic_menu_call, "Answer", answerPendingIntent)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePendingIntent)
        } else if (isOngoingCall) {
            val hangupIntent = Intent(this, SipForegroundService::class.java).apply { action = ACTION_HANGUP }
            val hangupPendingIntent = PendingIntent.getService(this, 1, hangupIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val muteIntent = Intent(this, SipForegroundService::class.java).apply { action = ACTION_TOGGLE_MUTE }
            val mutePendingIntent = PendingIntent.getService(this, 2, muteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val speakerIntent = Intent(this, SipForegroundService::class.java).apply { action = ACTION_TOGGLE_SPEAKER }
            val speakerPendingIntent = PendingIntent.getService(this, 3, speakerIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hang Up", hangupPendingIntent)
            builder.addAction(android.R.drawable.stat_notify_chat, if (isMuted) "Unmute" else "Mute", mutePendingIntent)
            builder.addAction(android.R.drawable.stat_sys_speakerphone, if (isSpeakerOn) "Earpiece" else "Speaker", speakerPendingIntent)
        }

        return builder.build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active Softphone Calls & Background VoIP",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Ongoing SIP VoIP active calls, multi-party conference and background keepalive"
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val standbyChannel = NotificationChannel(
                CHANNEL_STANDBY_ID,
                "SIP Standby (background)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Silent notification while the app waits for incoming calls"
                setSound(null, null)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(standbyChannel)
        }
    }

    private fun formatSeconds(totalSecs: Long): String {
        val minutes = totalSecs / 60
        val seconds = totalSecs % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    override fun onDestroy() {
        stateCollectJob?.cancel()
        registrationCollectJob?.cancel()
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SipForegroundService"
        const val CHANNEL_ID = "softphone_active_call_channel"
        const val CHANNEL_STANDBY_ID = "softphone_standby_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START_SERVICE = "com.example.softphone.START_CALL_SERVICE"
        const val ACTION_START_STANDBY = "com.example.softphone.START_STANDBY_SERVICE"
        const val ACTION_STOP_SERVICE = "com.example.softphone.STOP_CALL_SERVICE"
        const val ACTION_HANGUP = "com.example.softphone.ACTION_HANGUP"
        const val ACTION_ANSWER = "com.example.softphone.ACTION_ANSWER"
        const val ACTION_TOGGLE_MUTE = "com.example.softphone.ACTION_TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.example.softphone.ACTION_TOGGLE_SPEAKER"

        fun startService(context: Context) {
            val intent = Intent(context, SipForegroundService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startStandby(context: Context) {
            val intent = Intent(context, SipForegroundService::class.java).apply {
                action = ACTION_START_STANDBY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Called when a call ends: keeps the standby service alive while keep-alive is on. */
        fun stopService(context: Context) {
            if (SoftphoneApp.instance.settingsRepository.settings.value.backgroundKeepAlive) return
            val intent = Intent(context, SipForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.stopService(intent)
        }
    }
}
