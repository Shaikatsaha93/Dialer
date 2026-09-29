package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.platform.currentTimeMillis

enum class CallType {
    INCOMING,
    OUTGOING,
    MISSED
}

@Entity(tableName = "call_logs")
data class CallLogEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val remoteUri: String,
    val displayName: String,
    val callType: CallType,
    val timestamp: Long = currentTimeMillis(),
    val durationSeconds: Long = 0L
)
