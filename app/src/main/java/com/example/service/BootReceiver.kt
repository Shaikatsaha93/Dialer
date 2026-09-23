package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.SoftphoneApp

/**
 * Brings the SIP standby service back after a reboot or an app update, so incoming calls
 * ring without the user opening the app first. SoftphoneApp.onCreate (which runs before
 * this) re-registers the active account.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val keepAlive = SoftphoneApp.instance.settingsRepository.settings.value.backgroundKeepAlive
                Log.i(TAG, "${intent.action}: keepAlive=$keepAlive")
                if (keepAlive) {
                    try {
                        SipForegroundService.startStandby(context)
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not start standby service: ${e.message}")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
