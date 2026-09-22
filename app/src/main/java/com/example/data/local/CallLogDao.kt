package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.CallLogEntry
import com.example.data.model.CallType
import kotlinx.coroutines.flow.Flow

@Dao
interface CallLogDao {
    @Query("SELECT * FROM call_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<CallLogEntry>>

    @Query("SELECT * FROM call_logs WHERE callType = :type ORDER BY timestamp DESC")
    fun getLogsByType(type: CallType): Flow<List<CallLogEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(entry: CallLogEntry): Long

    @Delete
    suspend fun deleteLog(entry: CallLogEntry)

    @Query("DELETE FROM call_logs")
    suspend fun clearAllLogs()
}
