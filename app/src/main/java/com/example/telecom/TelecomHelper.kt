package com.example.telecom

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

class TelecomHelper(private val context: Context) {
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    private val componentName = ComponentName(context, SoftphoneConnectionService::class.java)
    val phoneAccountHandle = PhoneAccountHandle(componentName, "sip_softphone_account")

    fun registerPhoneAccount() {
        val tm = telecomManager ?: return
        try {
            val account = PhoneAccount.builder(phoneAccountHandle, "SIP Softphone")
                .setCapabilities(
                    PhoneAccount.CAPABILITY_SELF_MANAGED or
                            PhoneAccount.CAPABILITY_CALL_PROVIDER
                )
                .setIcon(android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.sym_call_outgoing))
                .setShortDescription("SIP VoIP Softphone")
                .addSupportedUriScheme("sip")
                .addSupportedUriScheme("tel")
                .build()

            tm.registerPhoneAccount(account)
            Log.i("TelecomHelper", "PhoneAccount successfully registered with TelecomManager.")
        } catch (e: SecurityException) {
            Log.w("TelecomHelper", "SecurityException registering PhoneAccount: ${e.message}")
        } catch (e: Exception) {
            Log.w("TelecomHelper", "Exception registering PhoneAccount: ${e.message}")
        }
    }

    fun reportIncomingCall(remoteUri: String, displayName: String) {
        val tm = telecomManager ?: return
        try {
            val extras = Bundle().apply {
                putParcelable(
                    TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                    Uri.parse("sip:$remoteUri")
                )
                putString("CALLER_NAME", displayName)
            }
            tm.addNewIncomingCall(phoneAccountHandle, extras)
        } catch (e: SecurityException) {
            Log.w("TelecomHelper", "SecurityException on addNewIncomingCall: ${e.message}")
        } catch (e: Exception) {
            Log.w("TelecomHelper", "Error in addNewIncomingCall: ${e.message}")
        }
    }
}
