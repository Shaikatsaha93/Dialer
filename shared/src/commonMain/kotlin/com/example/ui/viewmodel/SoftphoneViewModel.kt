package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import com.example.AppGraph
import com.example.data.model.AccountBalance
import com.example.data.model.AppSettings
import com.example.data.model.AppThemeMode
import com.example.data.model.CallLogEntry
import com.example.data.model.CallState
import com.example.data.model.CallType
import com.example.data.model.PushMessageItem
import com.example.data.model.RegistrationStatus
import com.example.data.model.SipAccount
import com.example.data.repository.CallLogRepository
import com.example.data.repository.ContactsSource
import com.example.data.repository.PushSource
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
    private val sipManager: SipManager = AppGraph.sipManager,
    private val accountRepository: SipAccountRepository = AppGraph.accountRepository,
    private val callLogRepository: CallLogRepository = AppGraph.callLogRepository,
    private val settingsRepository: SettingsRepository = AppGraph.settingsRepository,
    private val fcmTokenManager: PushSource = AppGraph.push,
    private val contactsRepository: ContactsSource = AppGraph.contacts
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
        fcmTokenManager.refreshToken()
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
    val accountBalance: StateFlow<AccountBalance?> = sipManager.accountBalance
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

    // Registration of the active account is done once, app-wide (SoftphoneApp on Android,
    // Main.kt on Windows), and only while the install is approved; not again per screen.
    init {
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

    /**
     * The list above the dial pad, as on a phone's own dialer. Nothing typed: every recent call,
     * newest first, with back-to-back calls of one number folded into one row ("(3)"). While
     * typing: all recent numbers and phone contacts whose number contains the typed digits.
     */
    val dialSuggestions: StateFlow<List<DialSuggestion>> = combine(
        _dialerInput,
        callLogRepository.allLogs,
        contactsRepository.contacts
    ) { input, logs, contacts ->
        val typed = input.filter { it.isDigit() }
        val calls = logs.asSequence()
            .map { DialSuggestion(dialNumberOf(it.remoteUri), it.displayName, it.callType, it.timestamp) }
            .filter { it.number.any(Char::isDigit) }
        if (typed.isEmpty()) {
            val rows = ArrayList<DialSuggestion>()
            for (call in calls) {
                val last = rows.lastOrNull()
                if (last != null && sameNumber(last.number, call.number)) {
                    rows[rows.lastIndex] = last.copy(count = last.count + 1)
                } else {
                    if (rows.size == MAX_RECENT_ROWS) break
                    rows += call
                }
            }
            rows
        } else {
            val fromContacts = contacts.asSequence().flatMap { contact ->
                contact.allNumbers.asSequence().map {
                    DialSuggestion(it.filter { c -> c.isDigit() || c == '+' }, contact.name, null, null)
                }
            }
            (calls + fromContacts)
                .filter { it.number.filter(Char::isDigit).contains(typed) && it.number != input }
                .distinctBy { it.number.filter(Char::isDigit).takeLast(10) }
                .take(MAX_RECENT_ROWS)
                .toList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 01712345678 and +8801712345678 are the same phone
    private fun sameNumber(a: String, b: String) =
        a.filter(Char::isDigit).takeLast(10) == b.filter(Char::isDigit).takeLast(10)

    /** The last number this phone dialed, for "call" with an empty dial pad (redial). */
    val lastDialedNumber: StateFlow<String?> = callLogRepository.allLogs
        .map { logs ->
            logs.asSequence().filter { it.callType == CallType.OUTGOING }
                .map { dialNumberOf(it.remoteUri) }.firstOrNull { it.any(Char::isDigit) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private fun dialNumberOf(remoteUri: String): String =
        remoteUri.removePrefix("sip:").removePrefix("sips:").substringBefore("@").substringBefore(";")

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
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { SoftphoneViewModel() }
        }
    }
}

/**
 * A row above the dial pad. [callType] and [timestamp] are null for a phone contact; [count] is
 * how many calls in a row this one stands for.
 */
data class DialSuggestion(
    val number: String,
    val name: String,
    val callType: CallType?,
    val timestamp: Long?,
    val count: Int = 1
)

private const val MAX_RECENT_ROWS = 200
