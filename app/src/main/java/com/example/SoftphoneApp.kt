package com.example

import android.app.Application
import android.util.Log
import com.example.data.local.AndroidDatabase
import com.example.data.local.AppDatabase
import com.example.data.local.SharedPrefsStore
import com.example.data.model.CallState
import com.example.data.model.CallType
import com.example.data.repository.CallLogRepository
import com.example.data.repository.AdminManager
import com.example.data.repository.ChatRepository
import com.example.data.repository.FcmTokenManager
import com.example.data.repository.LicenseManager
import com.example.data.repository.LicenseState
import com.example.data.repository.SettingsRepository
import com.example.data.repository.SipAccountRepository
import com.example.service.AndroidChatNotifier
import com.example.service.SipForegroundService
import com.example.sip.CallEvent
import com.example.sip.LinphoneSipManager
import com.example.sip.SipManager
import com.example.telecom.SoftphoneConnection
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
    val appUpdater by lazy { com.example.update.AppUpdater(this) }
    lateinit var fcmTokenManager: FcmTokenManager
        private set
    lateinit var contactsRepository: com.example.data.repository.ContactsRepository
        private set
    lateinit var licenseManager: LicenseManager
        private set
    lateinit var adminManager: AdminManager
        private set
    lateinit var chatRepository: ChatRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = AndroidDatabase.getInstance(this)
        accountRepository = SipAccountRepository(database.sipAccountDao())
        callLogRepository = CallLogRepository(database.callLogDao())
        settingsRepository = SettingsRepository(SharedPrefsStore(this, "app_voip_settings"))
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
        adminManager = AdminManager(this)
        chatRepository = ChatRepository(database.chatDao(), sipManager, contactsRepository, AndroidChatNotifier(this), applicationScope)
        chatRepository.start()

        // Hand the services to the shared screens
        AppGraph.sipManager = sipManager
        AppGraph.accountRepository = accountRepository
        AppGraph.callLogRepository = callLogRepository
        AppGraph.settingsRepository = settingsRepository
        AppGraph.contacts = contactsRepository
        AppGraph.push = fcmTokenManager
        AppGraph.license = licenseManager
        AppGraph.admin = adminManager
        AppGraph.chatRepository = chatRepository
        AppGraph.appVersion = BuildConfig.VERSION_NAME
        AppGraph.checkForUpdates = {
            applicationScope.launch(Dispatchers.Main) {
                val reached = appUpdater.check(force = true)
                val message = when {
                    !reached -> "Could not check for updates. Check the internet connection."
                    appUpdater.state.value is com.example.update.UpdateState.None -> "You have the latest version (${BuildConfig.VERSION_NAME})"
                    else -> null // the update dialog opens
                }
                if (message != null) android.widget.Toast.makeText(this@SoftphoneApp, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        if (contactsRepository.hasContactsPermission()) {
            applicationScope.launch {
                contactsRepository.loadContacts()
            }
        }

        observeActiveAccount()
        observeAdmin()
        observeCallEvents()
        observeTelecomState()
    }

    /** Keeps Android Telecom's copy of the call in step with the SIP call (see SoftphoneConnection). */
    private fun observeTelecomState() {
        applicationScope.launch(Dispatchers.Main) {
            sipManager.callState.collect { state ->
                if (state is CallState.Connected) {
                    if (state.isOnHold) SoftphoneConnection.onCallHeld(true) else SoftphoneConnection.onCallActive()
                }
            }
        }
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

    /** The admin's phone watches access requests (and notifies about new ones) while signed in. */
    private fun observeAdmin() {
        applicationScope.launch(Dispatchers.Main) {
            licenseManager.isAdmin.collect { admin ->
                if (admin) adminManager.startWatching() else adminManager.stopWatching()
            }
        }
    }

    private fun observeCallEvents() {
        applicationScope.launch {
            sipManager.callEvents.collect { event ->
                when (event) {
                    is CallEvent.CallStarted -> {
                        startServiceSafely { SipForegroundService.startService(this@SoftphoneApp) }
                        if (event.isIncoming && settingsRepository.settings.value.androidCallIntegration) {
                            telecomHelper.reportIncomingCall(event.remoteUri, event.displayName)
                        }
                    }
                    is CallEvent.CallEnded -> {
                        SoftphoneConnection.onCallEnded(event.wasMissed)
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
