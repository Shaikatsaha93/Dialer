package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.RegistrationStatus
import com.example.data.model.SipAccount
import kotlinx.coroutines.flow.Flow

@Dao
interface SipAccountDao {
    @Query("SELECT * FROM sip_accounts ORDER BY id DESC")
    fun getAllAccounts(): Flow<List<SipAccount>>

    @Query("SELECT * FROM sip_accounts WHERE isActive = 1 LIMIT 1")
    fun getActiveAccount(): Flow<SipAccount?>

    @Query("SELECT * FROM sip_accounts WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveAccountOneShot(): SipAccount?

    @Query("SELECT * FROM sip_accounts WHERE id = :id LIMIT 1")
    suspend fun getAccountById(id: Long): SipAccount?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: SipAccount): Long

    @Update
    suspend fun updateAccount(account: SipAccount)

    @Delete
    suspend fun deleteAccount(account: SipAccount)

    @Query("UPDATE sip_accounts SET isActive = 0")
    suspend fun deactivateAllAccounts()

    @Query("UPDATE sip_accounts SET isActive = 1 WHERE id = :id")
    suspend fun activateAccount(id: Long)

    @Query("UPDATE sip_accounts SET lastRegistrationStatus = :status, lastStatusMessage = :message WHERE id = :id")
    suspend fun updateStatus(id: Long, status: RegistrationStatus, message: String)
}
