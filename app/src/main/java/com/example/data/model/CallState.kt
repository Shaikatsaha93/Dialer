package com.example.data.model

data class ConferenceParticipant(
    val id: String,
    val uri: String,
    val displayName: String,
    val isMuted: Boolean = false,
    val isOnHold: Boolean = false,
    val isSpeaking: Boolean = false,
    val joinedAtSeconds: Long = 0L
)

data class SecondaryCallInfo(
    val uri: String,
    val displayName: String = "",
    val isOnHold: Boolean = true,
    val durationSeconds: Long = 0L,
    /** True once the 2nd line has answered; only then can it be merged. */
    val isConnected: Boolean = false
)

sealed class CallState {
    object Idle : CallState()

    data class Incoming(
        val remoteUri: String,
        val displayName: String = ""
    ) : CallState()

    data class Outgoing(
        val remoteUri: String,
        val displayName: String = "",
        val isEarlyMediaOrRinging: Boolean = false
    ) : CallState()

    data class Connected(
        val remoteUri: String,
        val displayName: String = "",
        val durationSeconds: Long = 0L,
        val isMuted: Boolean = false,
        val isSpeakerOn: Boolean = false,
        val isOnHold: Boolean = false,
        val isConference: Boolean = false,
        val participants: List<ConferenceParticipant> = emptyList(),
        val secondaryCall: SecondaryCallInfo? = null
    ) : CallState()

    data class Disconnected(
        val reason: String = "Call Ended"
    ) : CallState()
}

