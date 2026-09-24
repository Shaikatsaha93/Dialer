package com.example

import android.app.Application
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.model.CallType
import com.example.data.repository.CallLogRepository
import com.example.data.repository.FcmTokenManager
import com.example.data.repository.LicenseManager
import com.example.data.repository.LicenseState
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    lateinit var contactsRepository: com.example.data.repository.ContactsRepository
        private set
    lateinit var licenseManager: LicenseManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AppDatabase.getInstance(this)
        accountRepository = SipAccountRepository(database.sipAccountDao())
        callLogRepository = CallLogRepository(database.callLogDao())
        settingsRepository = SettingsRepository(this)
        fcmTokenManager = FcmTokenManager(this)
        contactsRepository = com.example.data.repository.ContactsRepository(this)
        sipManager = LinphoneSipManager(this)
        telecomHelper = TelecomHelper(this)

        telecomHelper.registerPhoneAccount()
        sipManager.initializeSdk()
        fcmTokenManager.initialize()
        // After FcmTokenManager, which initializes FirebaseApp
        licenseManager = LicenseManager(this, accountRepository)
        licenseManager.start()
        if (contactsRepository.hasContactsPermission()) {
            applicationScope.launch {
                contactsRepository.loadContacts()
            }
        }

        observeActiveAccount()
        observeCallEvents()
    }

    private fun observeActiveAccount() {
        applicationScope.launch {
            // Register only while the admin has approved this install
            combine(accountRepository.activeAccount, licenseManager.state) { account, license ->
                account.takeIf { license is LicenseState.Approved }
            }.distinctUntilChanged().collectLatest { activeAcc ->
                if (activeAcc != null) {
                    Log.d("SoftphoneApp", "Auto-registering active account: ${activeAcc.username}@${activeAcc.domain}")
                    sipManager.registerAccount(activeAcc)
                    // Also when Android restarts the process (START_STICKY, push, boot):
                    // without the standby service, Doze stops the registration.
                    if (settingsRepository.settings.value.backgroundKeepAlive) {
                        startServiceSafely { SipForegroundService.startStandby(this@SoftphoneApp) }
                    }
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
                        startServiceSafely { SipForegroundService.startService(this@SoftphoneApp) }
                        if (event.isIncoming) {
                            telecomHelper.reportIncomingCall(event.remoteUri, event.displayName)
                        }
                    }
                    is CallEvent.CallEnded -> {
                        SipForegroundService.stopService(this@SoftphoneApp)
                        if (settingsRepository.settings.value.recordCallHistory) {
                            val callType = when {
                                event.wasMissed -> CallType.MISSED
                                event.isIncoming -> CallType.INCOMING
                                else -> CallType.OUTGOING
                            }
                            val resolvedDisplayName = if (event.displayName.isNotBlank() && !event.displayName.startsWith("sip:")) {
                                event.displayName
                            } else {
                                contactsRepository.findContactName(event.remoteUri) ?: ""
                            }
                            callLogRepository.addLog(
                                remoteUri = event.remoteUri,
                                displayName = resolvedDisplayName,
                                callType = callType,
                                durationSeconds = event.durationSeconds
                            )
                        }
                    }
                }
            }
        }
    }

    // Android 12+ refuses to start a foreground service from the background unless the app is
    // exempt (battery-optimization allowlist, push, boot). Log instead of crashing.
    private inline fun startServiceSafely(start: () -> Unit) {
        try {
            start()
        } catch (e: Exception) {
            Log.w("SoftphoneApp", "Foreground service not started: ${e.message}")
        }
    }

    companion object {
        lateinit var instance: SoftphoneApp
            private set
    }
}
