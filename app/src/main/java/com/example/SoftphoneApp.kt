package com.example

import android.app.Application
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.model.CallType
import com.example.data.repository.CallLogRepository
import com.example.data.repository.FcmTokenManager
import com.example.data.repository.SettingsRepository
import com.example.data.repository.SipAccountRepository
import com.example.service.SipForegroundService
import com.example.sip.CallEvent
import com.example.sip.LinphoneSipManager
import com.example.sip.SipManager
import com.example.telecom.TelecomHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SoftphoneApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var database: AppDatabase
        private set
    lateinit var accountRepository: SipAccountRepository
        private set
    lateinit var callLogRepository: CallLogRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var sipManager: SipManager
        private set
    lateinit var telecomHelper: TelecomHelper
        private set
    lateinit var fcmTokenManager: FcmTokenManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AppDatabase.getInstance(this)
        accountRepository = SipAccountRepository(database.sipAccountDao())
        callLogRepository = CallLogRepository(database.callLogDao())
        settingsRepository = SettingsRepository(this)
        fcmTokenManager = FcmTokenManager(this)
        sipManager = LinphoneSipManager(this)
        telecomHelper = TelecomHelper(this)

        telecomHelper.registerPhoneAccount()
        sipManager.initializeSdk()
        fcmTokenManager.initialize()

        observeActiveAccount()
        observeCallEvents()
    }

    private fun observeActiveAccount() {
        applicationScope.launch {
            accountRepository.activeAccount.collectLatest { activeAcc ->
                if (activeAcc != null) {
                    Log.d("SoftphoneApp", "Auto-registering active account: ${activeAcc.username}@${activeAcc.domain}")
                    sipManager.registerAccount(activeAcc)
                } else {
                    sipManager.unregisterCurrentAccount()
                }
            }
        }
    }

    private fun observeCallEvents() {
        applicationScope.launch {
            sipManager.callEvents.collect { event ->
                when (event) {
                    is CallEvent.CallStarted -> {
                        SipForegroundService.startService(this@SoftphoneApp)
                        if (event.isIncoming) {
                            telecomHelper.reportIncomingCall(event.remoteUri, event.displayName)
                        }
                    }
                    is CallEvent.CallEnded -> {
                        SipForegroundService.stopService(this@SoftphoneApp)
                        if (settingsRepository.settings.value.recordCallHistory) {
                            val callType = if (event.wasMissed) {
                                CallType.MISSED
                            } else {
                                CallType.OUTGOING
                            }
                            callLogRepository.addLog(
                                remoteUri = event.remoteUri,
                                displayName = event.displayName,
                                callType = callType,
                                durationSeconds = event.durationSeconds
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        lateinit var instance: SoftphoneApp
            private set
    }
}
