package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.SoftphoneApp
import com.example.data.model.AppSettings
import com.example.data.model.AppThemeMode
import com.example.data.model.CallLogEntry
import com.example.data.model.CallState
import com.example.data.model.CallType
import com.example.data.model.PushMessageItem
import com.example.data.model.RegistrationStatus
import com.example.data.model.SipAccount
import com.example.data.repository.CallLogRepository
import com.example.data.repository.FcmTokenManager
import com.example.data.repository.SettingsRepository
import com.example.data.repository.SipAccountRepository
import com.example.sip.SipManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SoftphoneViewModel(
    private val sipManager: SipManager = SoftphoneApp.instance.sipManager,
    private val accountRepository: SipAccountRepository = SoftphoneApp.instance.accountRepository,
    private val callLogRepository: CallLogRepository = SoftphoneApp.instance.callLogRepository,
    private val settingsRepository: SettingsRepository = SoftphoneApp.instance.settingsRepository,
    private val fcmTokenManager: FcmTokenManager = SoftphoneApp.instance.fcmTokenManager,
    private val contactsRepository: com.example.data.repository.ContactsRepository = SoftphoneApp.instance.contactsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    val fcmToken: StateFlow<String?> = fcmTokenManager.fcmToken
    val fcmTokenStatus: StateFlow<String> = fcmTokenManager.tokenStatus
    val receivedPushes: StateFlow<List<PushMessageItem>> = fcmTokenManager.receivedPushes

    // Device Contacts Management
    val deviceContacts: StateFlow<List<com.example.data.model.PhoneContact>> = contactsRepository.contacts
    val isContactsLoading: StateFlow<Boolean> = contactsRepository.isLoading
    private val _contactsSearchQuery = MutableStateFlow("")
    val contactsSearchQuery: StateFlow<String> = _contactsSearchQuery.asStateFlow()

    val filteredContacts: StateFlow<List<com.example.data.model.PhoneContact>> = combine(
        contactsRepository.contacts,
        _contactsSearchQuery
    ) { contacts, query ->
        if (query.isBlank()) {
            contacts
        } else {
            val q = query.trim().lowercase()
            contacts.filter { contact ->
                contact.name.lowercase().contains(q) ||
                        contact.allNumbers.any { it.contains(q) }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun searchContacts(query: String) {
        _contactsSearchQuery.value = query
    }

    fun loadDeviceContacts() {
        viewModelScope.launch {
            contactsRepository.loadContacts()
        }
    }

    fun hasContactsPermission(): Boolean {
        return contactsRepository.hasContactsPermission()
    }

    fun getContactNameForUri(uriOrNumber: String): String? {
        return contactsRepository.findContactName(uriOrNumber)
    }

    fun refreshFcmToken() {
        fcmTokenManager.refreshToken()
    }

    fun generateTestFcmToken() {
        fcmTokenManager.generateTestToken()
    }

    fun clearPushHistory() {
        fcmTokenManager.clearPushHistory()
    }

    fun simulatePushNotification(
        title: String,
        body: String,
        isVoipCall: Boolean = false,
        callerUri: String = "sip:1003@sip.domain.com",
        callerName: String = "Bob Johnson"
    ) {
        val pushItem = PushMessageItem(
            title = title,
            body = body,
            isVoipCallPush = isVoipCall,
            callerUri = if (isVoipCall) callerUri else null,
            callerName = if (isVoipCall) callerName else null,
            dataPayload = mapOf("type" to if (isVoipCall) "call" else "alert", "caller_uri" to callerUri, "caller_name" to callerName)
        )
        fcmTokenManager.recordPushMessage(pushItem)
        sipManager.addDiagnosticLog("[FCM Test Push] Simulating push: $title - $body (isVoip=$isVoipCall)")
        if (isVoipCall && settings.value.fcmVoipWakeup) {
            sipManager.simulateIncomingCall(callerUri, callerName)
        }
    }

    val themeMode: StateFlow<AppThemeMode> = settings
        .map { it.themeMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settings.value.themeMode)

    fun setThemeMode(mode: AppThemeMode) {
        settingsRepository.setThemeMode(mode)
    }

    fun updateSettings(newSettings: AppSettings) {
        settingsRepository.updateSettings(newSettings)
        sipManager.applySettings(newSettings)
    }

    fun resetSettingsToDefaults() {
        val defaultSettings = AppSettings()
        updateSettings(defaultSettings)
    }

    val registrationState: StateFlow<RegistrationStatus> = sipManager.registrationState
    val registrationMessage: StateFlow<String> = sipManager.registrationMessage
    val diagnosticLogs: StateFlow<List<String>> = sipManager.diagnosticLogs
    val callState: StateFlow<CallState> = sipManager.callState
    val callDuration: StateFlow<Long> = sipManager.callDuration
    val isMuted: StateFlow<Boolean> = sipManager.isMuted
    val isSpeakerOn: StateFlow<Boolean> = sipManager.isSpeakerOn
    val isOnHold: StateFlow<Boolean> = sipManager.isOnHold

    private val _dialerInput = MutableStateFlow("")
    val dialerInput: StateFlow<String> = _dialerInput.asStateFlow()

    private val _historyFilter = MutableStateFlow<CallType?>(null)
    val historyFilter: StateFlow<CallType?> = _historyFilter.asStateFlow()

    val allAccounts: StateFlow<List<SipAccount>> = accountRepository.allAccounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeAccount: StateFlow<SipAccount?> = accountRepository.activeAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val filteredCallLogs: StateFlow<List<CallLogEntry>> = combine(
        callLogRepository.allLogs,
        _historyFilter
    ) { logs, filter ->
        if (filter == null) logs else logs.filter { it.callType == filter }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            accountRepository.activeAccount.collect { account ->
                if (account != null && account.isActive) {
                    sipManager.registerAccount(account)
                }
            }
        }
        viewModelScope.launch {
            settings.collect { currentSettings ->
                sipManager.applySettings(currentSettings)
            }
        }
    }

    fun retryRegistration() {
        activeAccount.value?.let { account ->
            sipManager.registerAccount(account)
        }
    }

    fun clearLogs() {
        sipManager.clearLogs()
    }

    fun onDialerChar(char: Char) {
        if (settings.value.dtmfKeypadSound) {
            sipManager.sendDtmf(char)
        }
        _dialerInput.value += char
    }

    fun onBackspace() {
        if (_dialerInput.value.isNotEmpty()) {
            _dialerInput.value = _dialerInput.value.dropLast(1)
        }
    }

    fun onClearDialer() {
        _dialerInput.value = ""
    }

    fun setDialerInput(value: String) {
        _dialerInput.value = value
    }

    fun initiateCall(destination: String? = null) {
        val target = destination ?: _dialerInput.value
        if (target.isNotBlank()) {
            sipManager.makeCall(target)
        }
    }

    fun acceptCall() {
        sipManager.acceptCall()
    }

    fun hangupCall() {
        sipManager.hangupCall()
    }

    fun toggleMute() {
        sipManager.toggleMute()
    }

    fun toggleSpeaker() {
        sipManager.toggleSpeaker()
    }

    fun toggleHold() {
        sipManager.toggleHold()
    }

    fun sendDtmf(char: Char) {
        sipManager.sendDtmf(char)
    }

    // Conference Call Operations
    fun addParticipantToCall(destinationUri: String, displayName: String = "") {
        sipManager.addParticipantToCall(destinationUri, displayName)
    }

    fun mergeCallsIntoConference() {
        sipManager.mergeCallsIntoConference()
    }

    fun removeParticipantFromConference(participantId: String) {
        sipManager.removeParticipantFromConference(participantId)
    }

    fun toggleParticipantMute(participantId: String) {
        sipManager.toggleParticipantMute(participantId)
    }

    fun swapActiveAndHeldCalls() {
        sipManager.swapActiveAndHeldCalls()
    }

    fun hangupSecondaryCall() {
        sipManager.hangupSecondaryCall()
    }

    fun saveAccount(account: SipAccount, makeActive: Boolean = true) {
        viewModelScope.launch {
            accountRepository.saveAccount(account, makeActive)
            if (makeActive) {
                sipManager.registerAccount(account)
            }
        }
    }

    fun setActiveAccount(id: Long) {
        viewModelScope.launch {
            accountRepository.setActive(id)
        }
    }

    fun deleteAccount(account: SipAccount) {
        viewModelScope.launch {
            accountRepository.deleteAccount(account)
        }
    }

    fun setHistoryFilter(type: CallType?) {
        _historyFilter.value = type
    }

    fun clearCallHistory() {
        viewModelScope.launch {
            callLogRepository.clearHistory()
        }
    }

    fun deleteCallLog(log: CallLogEntry) {
        viewModelScope.launch {
            callLogRepository.deleteLog(log)
        }
    }

    fun simulateIncomingCall(callerUri: String = "sip:1002@sip.domain.com", callerName: String = "Alice Smith") {
        sipManager.simulateIncomingCall(callerUri, callerName)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return SoftphoneViewModel() as T
            }
        }
    }
}
