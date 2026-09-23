package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.SoftphoneApp
import com.example.data.model.PushMessageItem
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class SoftphoneFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Token received: $token")
        try {
            SoftphoneApp.instance.fcmTokenManager.saveToken(token)
            SoftphoneApp.instance.sipManager.addDiagnosticLog("[FCM] Device Push Token refreshed: ${token.take(16)}...")
        } catch (e: Exception) {
            Log.w(TAG, "Error in onNewToken: ${e.message}")
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM Message received from: ${remoteMessage.from}")

        val data = remoteMessage.data
        val notification = remoteMessage.notification

        val title = notification?.title ?: data["title"] ?: "Softphone Notification"
        val body = notification?.body ?: data["body"] ?: data["message"] ?: "Incoming background notification"

        val isVoipCallPush = data["type"] == "call" ||
                data["type"] == "incoming_call" ||
                data["action"] == "call" ||
                data.containsKey("caller_uri") ||
                data.containsKey("caller") ||
                data.containsKey("pn_sip_call_id")

        val callerUri = data["caller_uri"] ?: data["caller"] ?: "sip:push-caller@sip.domain.com"
        val callerName = data["caller_name"] ?: data["caller_display_name"] ?: title

        // Record received push item
        val pushItem = PushMessageItem(
            id = remoteMessage.messageId ?: System.currentTimeMillis().toString(),
            title = title,
            body = body,
            dataPayload = data,
            receivedAtTimestamp = System.currentTimeMillis(),
            isVoipCallPush = isVoipCallPush,
            callerUri = if (isVoipCallPush) callerUri else null,
            callerName = if (isVoipCallPush) callerName else null
        )

        try {
            SoftphoneApp.instance.fcmTokenManager.recordPushMessage(pushItem)
            SoftphoneApp.instance.sipManager.addDiagnosticLog(
                if (isVoipCallPush) {
                    "[FCM Push] Received VoIP Call Push from $callerName ($callerUri)"
                } else {
                    "[FCM Push] Notification: $title - $body"
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error logging push item: ${e.message}")
        }

        val settings = try {
            SoftphoneApp.instance.settingsRepository.settings.value
        } catch (_: Exception) {
            null
        }

        if (settings?.fcmPushEnabled == false) {
            Log.d(TAG, "FCM Push notifications disabled in settings. Skipping alert.")
            return
        }

        if (isVoipCallPush && settings?.fcmVoipWakeup != false) {
            // The push only wakes us up. The call itself is the SIP INVITE the switch sends once
            // we are registered again; it rings through the normal incoming-call path.
            // A high-priority FCM message lets us start the foreground service from the background.
            try {
                SipForegroundService.startStandby(this)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start standby service from push: ${e.message}")
            }
            try {
                SoftphoneApp.instance.sipManager.onPushWakeup()
            } catch (e: Exception) {
                Log.e(TAG, "Error waking softphone for VoIP call push: ${e.message}")
                showSystemNotification(title, body, isVoipCall = true)
            }
        } else {
            showSystemNotification(title, body, isVoipCall = false)
        }
    }

    private fun showSystemNotification(title: String, body: String, isVoipCall: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel(notificationManager)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(
            if (isVoipCall) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION
        )

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_FCM_PUSH)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)

        if (isVoipCall) {
            notificationBuilder.setCategory(NotificationCompat.CATEGORY_CALL)
        }

        val notificationId = (System.currentTimeMillis() % 100000).toInt()
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    private fun createNotificationChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_FCM_PUSH,
                "FCM Push & VoIP Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming background push messages and VoIP wake-up alerts"
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "SoftphoneFcmService"
        const val CHANNEL_FCM_PUSH = "softphone_fcm_push_channel"
    }
}
