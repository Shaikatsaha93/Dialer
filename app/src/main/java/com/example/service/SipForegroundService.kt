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
        val notification = buildNotification(title, text, isOngoingCall = false)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting foreground service: ${e.message}")
            try {
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, text, isOngoingCall, isIncoming, isMuted, isSpeakerOn))
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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_CALL)

        if (isIncoming) {
            val answerIntent = Intent(this, SipForegroundService::class.java).apply { action = ACTION_ANSWER }
            val answerPendingIntent = PendingIntent.getService(this, 10, answerIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val declineIntent = Intent(this, SipForegroundService::class.java).apply { action = ACTION_HANGUP }
            val declinePendingIntent = PendingIntent.getService(this, 11, declineIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

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
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
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

        fun stopService(context: Context) {
            val intent = Intent(context, SipForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.stopService(intent)
        }
    }
}
