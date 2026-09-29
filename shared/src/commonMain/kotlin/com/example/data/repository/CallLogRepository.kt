package com.example.data.repository

import com.example.data.local.CallLogDao
import com.example.data.model.CallLogEntry
import com.example.data.model.CallType
import com.example.platform.currentTimeMillis
import kotlinx.coroutines.flow.Flow

class CallLogRepository(private val dao: CallLogDao) {
    val allLogs: Flow<List<CallLogEntry>> = dao.getAllLogs()

    fun getLogsByType(type: CallType): Flow<List<CallLogEntry>> = dao.getLogsByType(type)

    suspend fun addLog(
        remoteUri: String,
        displayName: String,
        callType: CallType,
        durationSeconds: Long = 0L
    ): Long {
        val entry = CallLogEntry(
            remoteUri = remoteUri,
            displayName = displayName.ifBlank { remoteUri },
            callType = callType,
            timestamp = currentTimeMillis(),
            durationSeconds = durationSeconds
        )
        return dao.insertLog(entry)
    }

    suspend fun deleteLog(entry: CallLogEntry) {
        dao.deleteLog(entry)
    }

    suspend fun clearHistory() {
        dao.clearAllLogs()
    }
}
