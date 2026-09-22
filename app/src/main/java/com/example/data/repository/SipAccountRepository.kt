package com.example.data.repository

import com.example.data.local.SipAccountDao
import com.example.data.model.RegistrationStatus
import com.example.data.model.SipAccount
import kotlinx.coroutines.flow.Flow

class SipAccountRepository(private val dao: SipAccountDao) {
    val allAccounts: Flow<List<SipAccount>> = dao.getAllAccounts()
    val activeAccount: Flow<SipAccount?> = dao.getActiveAccount()

    suspend fun getActiveAccountOneShot(): SipAccount? = dao.getActiveAccountOneShot()

    suspend fun saveAccount(account: SipAccount, makeActive: Boolean = true): Long {
        if (makeActive) {
            dao.deactivateAllAccounts()
        }
        val accountToSave = if (makeActive) account.copy(isActive = true) else account
        val id = dao.insertAccount(accountToSave)
        if (makeActive && account.id == 0L) {
            dao.activateAccount(id)
        }
        return id
    }

    suspend fun setActive(id: Long) {
        dao.deactivateAllAccounts()
        dao.activateAccount(id)
    }

    suspend fun deleteAccount(account: SipAccount) {
        dao.deleteAccount(account)
    }

    suspend fun updateRegistrationStatus(id: Long, status: RegistrationStatus, message: String) {
        dao.updateStatus(id, status, message)
    }
}
