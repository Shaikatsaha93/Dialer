package com.example.sip

import com.example.data.model.AccountBalance
import com.example.data.model.AppSettings
import com.example.data.model.CallState
import com.example.data.model.RegistrationStatus
import com.example.data.model.SipAccount
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface SipManager {
    val registrationState: StateFlow<RegistrationStatus>
    val registrationMessage: StateFlow<String>
    val accountBalance: StateFlow<AccountBalance?>
    val diagnosticLogs: StateFlow<List<String>>
    val callState: StateFlow<CallState>
    val callDuration: StateFlow<Long>
    val isMuted: StateFlow<Boolean>
    val isSpeakerOn: StateFlow<Boolean>
    val isOnHold: StateFlow<Boolean>
    val callEvents: SharedFlow<CallEvent>
    /** True while the current call is being recorded to a file. */
    val isRecording: StateFlow<Boolean>

    fun initializeSdk()
    fun applySettings(settings: AppSettings)
    fun registerAccount(account: SipAccount)
    fun unregisterCurrentAccount()
    fun clearLogs()
    fun makeCall(destinationUri: String, displayName: String = "")
    fun acceptCall()
    fun hangupCall()
    fun toggleMute()
    fun toggleSpeaker()
    fun toggleHold()
    fun sendDtmf(dtmfChar: Char)
    fun simulateIncomingCall(callerUri: String, callerName: String)
    /** A call push arrived: make sure the account is registered so the real INVITE can reach us. */
    fun onPushWakeup()
    fun simulateAutoAnswer()

    // Conference Call Features
    fun addParticipantToCall(destinationUri: String, displayName: String = "")
    fun mergeCallsIntoConference()
    fun removeParticipantFromConference(participantId: String)
    fun toggleParticipantMute(participantId: String)
    fun swapActiveAndHeldCalls()
    fun hangupSecondaryCall()

    fun addDiagnosticLog(log: String)

    // Chat (SIP MESSAGE, e.g. through Asterisk)
    val incomingChatMessages: SharedFlow<IncomingChatMessage>
    /** User part (number) of the registered account, or null when not registered. */
    val ownNumber: String?
    /**
     * Sends a text as a SIP MESSAGE to [to] (a number, or a full SIP URI). [headers] are added as
     * custom SIP headers. [onResult] gets true when the server accepted it, false when it failed.
     */
    fun sendChatMessage(to: String, text: String, headers: Map<String, String>, onResult: (Boolean) -> Unit)
}

/**
 * Custom SIP headers that turn a 1-to-1 SIP MESSAGE into a group message: the sender sends one
 * MESSAGE per member, and the receiving app files it under the group. The PBX must forward them
 * (see README, Asterisk messaging).
 */
object ChatHeaders {
    const val GROUP_ID = "X-Chat-Group"
    const val GROUP_NAME = "X-Chat-Group-Name"
    const val GROUP_MEMBERS = "X-Chat-Group-Members"
}

/** A SIP MESSAGE received from [from] (the sender's number). */
data class IncomingChatMessage(
    val from: String,
    val fromDisplayName: String,
    val text: String,
    val timestamp: Long,
    val headers: Map<String, String>
)

sealed class CallEvent {
    data class CallStarted(val remoteUri: String, val displayName: String, val isIncoming: Boolean) : CallEvent()
    data class CallEnded(
        val remoteUri: String,
        val displayName: String,
        val durationSeconds: Long,
        val wasMissed: Boolean,
        val isIncoming: Boolean = false
    ) : CallEvent()
}
