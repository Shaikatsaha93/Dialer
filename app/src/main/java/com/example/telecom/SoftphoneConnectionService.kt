package com.example.telecom

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import com.example.SoftphoneApp

class SoftphoneConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.i("SoftphoneConnService", "onCreateIncomingConnection called")
        val addressUri = request?.extras?.getParcelable<Uri>(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS)
            ?: request?.address ?: Uri.parse("sip:unknown")
        val displayName = request?.extras?.getString("CALLER_NAME") ?: addressUri.schemeSpecificPart

        val connection = SoftphoneConnection(addressUri.schemeSpecificPart, displayName, isIncoming = true)
        connection.connectionCapabilities = Connection.CAPABILITY_SUPPORT_HOLD or Connection.CAPABILITY_HOLD or Connection.CAPABILITY_MUTE
        connection.setAddress(addressUri, TelecomManager.PRESENTATION_ALLOWED)
        connection.setCallerDisplayName(displayName, TelecomManager.PRESENTATION_ALLOWED)
        connection.setRinging()
        return connection
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.i("SoftphoneConnService", "onCreateOutgoingConnection called")
        val addressUri = request?.address ?: Uri.parse("sip:unknown")
        val connection = SoftphoneConnection(addressUri.schemeSpecificPart, addressUri.schemeSpecificPart, isIncoming = false)
        connection.connectionCapabilities = Connection.CAPABILITY_SUPPORT_HOLD or Connection.CAPABILITY_HOLD or Connection.CAPABILITY_MUTE
        connection.setAddress(addressUri, TelecomManager.PRESENTATION_ALLOWED)
        connection.setDialing()
        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request)
        Log.w("SoftphoneConnService", "onCreateIncomingConnectionFailed")
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        super.onCreateOutgoingConnectionFailed(connectionManagerPhoneAccount, request)
        Log.w("SoftphoneConnService", "onCreateOutgoingConnectionFailed")
    }
}

class SoftphoneConnection(
    private val remoteUri: String,
    private val callerDisplayName: String,
    private val isIncoming: Boolean
) : Connection() {

    private val sipManager get() = SoftphoneApp.instance.sipManager

    init {
        audioModeIsVoip = true
    }

    override fun onAnswer() {
        Log.i("SoftphoneConnection", "onAnswer called by Telecom")
        setActive()
        sipManager.acceptCall()
    }

    override fun onReject() {
        Log.i("SoftphoneConnection", "onReject called by Telecom")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        sipManager.hangupCall()
    }

    override fun onDisconnect() {
        Log.i("SoftphoneConnection", "onDisconnect called by Telecom")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        sipManager.hangupCall()
    }

    override fun onHold() {
        Log.i("SoftphoneConnection", "onHold called by Telecom")
        setOnHold()
        sipManager.toggleHold()
    }

    override fun onUnhold() {
        Log.i("SoftphoneConnection", "onUnhold called by Telecom")
        setActive()
        sipManager.toggleHold()
    }

    override fun onPlayDtmfTone(c: Char) {
        sipManager.sendDtmf(c)
    }

    override fun onStopDtmfTone() {
        // Handled by tone generator
    }
}
