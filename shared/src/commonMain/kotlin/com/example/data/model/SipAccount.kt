package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SipTransport {
    UDP, TCP, TLS
}

enum class RegistrationStatus {
    UNREGISTERED,
    REGISTERING,
    REGISTERED,
    FAILED
}

@Entity(tableName = "sip_accounts")
data class SipAccount(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val username: String,
    val password: String,
    val domain: String,
    val port: Int = 5060,
    val transport: SipTransport = SipTransport.UDP,
    val displayName: String = "",
    val isActive: Boolean = true,
    val lastRegistrationStatus: RegistrationStatus = RegistrationStatus.UNREGISTERED,
    val lastStatusMessage: String = ""
) {
    val sipAddress: String
        get() = "sip:$username@$domain"

    val serverUri: String
        get() = "sip:$domain:$port;transport=${transport.name.lowercase()}"
}
