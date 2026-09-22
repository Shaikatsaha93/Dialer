package com.example.sip

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.example.data.model.AppSettings
import com.example.data.model.CallState
import com.example.data.model.ConferenceParticipant
import com.example.data.model.RegistrationStatus
import com.example.data.model.SecondaryCallInfo
import com.example.data.model.SipAccount
import com.example.data.model.SipTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.AuthInfo
import org.linphone.core.AuthMethod
import org.linphone.core.Call
import org.linphone.core.CallParams
import org.linphone.core.Core
import org.linphone.core.CoreListener
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.LogLevel
import org.linphone.core.ProxyConfig
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType

interface SipManager {
    val registrationState: StateFlow<RegistrationStatus>
    val registrationMessage: StateFlow<String>
    val diagnosticLogs: StateFlow<List<String>>
    val callState: StateFlow<CallState>
    val callDuration: StateFlow<Long>
    val isMuted: StateFlow<Boolean>
    val isSpeakerOn: StateFlow<Boolean>
    val isOnHold: StateFlow<Boolean>
    val callEvents: SharedFlow<CallEvent>

    fun initializeSdk()
    fun applySettings(settings: AppSettings)
    fun registerAccount(account: SipAccount)
    fun unregisterCurrentAccount()
    fun clearLogs()
    fun makeCall(destinationUri: String, displayName: String = "")
    fun acceptCall()
    fun hangupCall()
    fun toggleMute()
    fun toggleSpeaker()
    fun toggleHold()
    fun sendDtmf(dtmfChar: Char)
    fun simulateIncomingCall(callerUri: String, callerName: String)
    fun simulateAutoAnswer()

    // Conference Call Features
    fun addParticipantToCall(destinationUri: String, displayName: String = "")
    fun mergeCallsIntoConference()
    fun removeParticipantFromConference(participantId: String)
    fun toggleParticipantMute(participantId: String)
    fun swapActiveAndHeldCalls()
}

sealed class CallEvent {
    data class CallStarted(val remoteUri: String, val displayName: String, val isIncoming: Boolean) : CallEvent()
    data class CallEnded(val remoteUri: String, val displayName: String, val durationSeconds: Long, val wasMissed: Boolean) : CallEvent()
}

class LinphoneSipManager(
    private val context: Context
) : SipManager {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var core: Core? = null
    private var currentLinphoneCall: Call? = null
    private var iterateJob: Job? = null
    private var durationTimerJob: Job? = null
    private var activeAccountModel: SipAccount? = null
    private var currentSettings: AppSettings = AppSettings()

    private val _registrationState = MutableStateFlow(RegistrationStatus.UNREGISTERED)
    override val registrationState: StateFlow<RegistrationStatus> = _registrationState.asStateFlow()

    private val _registrationMessage = MutableStateFlow("Ready to configure account")
    override val registrationMessage: StateFlow<String> = _registrationMessage.asStateFlow()

    private val _diagnosticLogs = MutableStateFlow<List<String>>(emptyList())
    override val diagnosticLogs: StateFlow<List<String>> = _diagnosticLogs.asStateFlow()

    override fun clearLogs() {
        _diagnosticLogs.value = emptyList()
    }

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    override val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _callDuration = MutableStateFlow(0L)
    override val callDuration: StateFlow<Long> = _callDuration.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    override val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    override val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _isOnHold = MutableStateFlow(false)
    override val isOnHold: StateFlow<Boolean> = _isOnHold.asStateFlow()

    private val _callEvents = MutableSharedFlow<CallEvent>()
    override val callEvents: SharedFlow<CallEvent> = _callEvents.asSharedFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val dtmfTonePlayer = DtmfTonePlayer()

    private val coreListener: CoreListener = object : CoreListenerStub() {
        override fun onAuthenticationRequested(
            core: Core,
            authInfo: AuthInfo,
            method: AuthMethod
        ) {
            Log.d(TAG, "onAuthenticationRequested: username=${authInfo.username}, realm=${authInfo.realm}, domain=${authInfo.domain}")
            val account = activeAccountModel ?: return
            val cleanUsername = account.username.trim()
            val password = account.password
            val factory = Factory.instance()

            val newAuth = factory.createAuthInfo(
                cleanUsername,
                cleanUsername,
                password,
                null,
                authInfo.realm,
                authInfo.domain ?: account.domain.trim()
            )
            core.addAuthInfo(newAuth)
            _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Auth] Provided credentials for realm=${authInfo.realm.orEmpty()}").takeLast(50)
        }

        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState,
            message: String
        ) {
            handleRegistrationState(account, state, message)
        }

        override fun onRegistrationStateChanged(
            core: Core,
            cfg: ProxyConfig,
            state: RegistrationState,
            message: String
        ) {
            val account = core.accountList.firstOrNull()
            handleRegistrationState(account, state, message)
        }

        private fun handleRegistrationState(
            account: Account?,
            state: RegistrationState,
            message: String
        ) {
            Log.d(TAG, "Linphone registration state changed: $state, message: $message")
            val errorInfo = account?.errorInfo
            val code = errorInfo?.protocolCode ?: 0
            val phrase = errorInfo?.phrase.orEmpty().trim()
            val stateLog = if (code > 0 || phrase.isNotBlank()) {
                "[SIP Reg] State: $state, Code: $code, Reason: $phrase ($message)"
            } else {
                "[SIP Reg] State: $state ($message)"
            }
            _diagnosticLogs.value = (_diagnosticLogs.value + stateLog).takeLast(50)

            when (state) {
                RegistrationState.Ok -> {
                    _registrationState.value = RegistrationStatus.REGISTERED
                    _registrationMessage.value = "Registered successfully"
                }
                RegistrationState.Progress, RegistrationState.Refreshing -> {
                    _registrationState.value = RegistrationStatus.REGISTERING
                    _registrationMessage.value = "Registering with SIP server..."
                }
                RegistrationState.Failed -> {
                    _registrationState.value = RegistrationStatus.FAILED
                    val detailedMsg = when {
                        code == 401 || code == 407 -> "401 Unauthorized: Server challenged credentials. Check password, auth username, or realm"
                        code == 403 -> "403 Forbidden: Incorrect credentials or IP not allowed by SIP server"
                        code == 404 -> "404 Not Found: Extension/user does not exist on this SIP server"
                        code == 408 -> "408 Timeout: Server unreachable. Check Domain/IP, Port, or switch Transport (UDP/TCP)"
                        code == 503 -> "503 Service Unavailable: SIP server is offline or rejected registration"
                        phrase.isNotBlank() && code > 0 -> "$phrase (Code $code)"
                        phrase.isNotBlank() -> phrase
                        message.isNotBlank() -> message
                        else -> "Registration failed (Check credentials, server address, or transport)"
                    }
                    _registrationMessage.value = detailedMsg
                }
                RegistrationState.Cleared, RegistrationState.None -> {
                    _registrationState.value = RegistrationStatus.UNREGISTERED
                    _registrationMessage.value = "Unregistered"
                }
                else -> {
                    _registrationMessage.value = "Status: $state"
                }
            }
        }

        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State,
            message: String
        ) {
            Log.d(TAG, "Linphone call state changed: $state, message: $message")
            currentLinphoneCall = call
            val remoteAddress = call.remoteAddress?.asStringUriOnly() ?: "Unknown"
            val remoteDisplayName = call.remoteAddress?.displayName?.ifBlank { null } ?: remoteAddress

            when (state) {
                Call.State.IncomingReceived, Call.State.IncomingEarlyMedia -> {
                    _callState.value = CallState.Incoming(remoteAddress, remoteDisplayName)
                    scope.launch {
                        _callEvents.emit(CallEvent.CallStarted(remoteAddress, remoteDisplayName, isIncoming = true))
                        if (currentSettings.autoAnswer) {
                            val delayMs = (currentSettings.autoAnswerDelaySeconds.coerceAtLeast(1)) * 1000L
                            _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Auto-Answer] Answering in ${currentSettings.autoAnswerDelaySeconds}s").takeLast(50)
                            delay(delayMs)
                            if (_callState.value is CallState.Incoming) {
                                acceptCall()
                            }
                        }
                    }
                }
                Call.State.OutgoingInit, Call.State.OutgoingProgress -> {
                    _callState.value = CallState.Outgoing(remoteAddress, remoteDisplayName, isEarlyMediaOrRinging = false)
                    scope.launch {
                        _callEvents.emit(CallEvent.CallStarted(remoteAddress, remoteDisplayName, isIncoming = false))
                    }
                }
                Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> {
                    _callState.value = CallState.Outgoing(remoteAddress, remoteDisplayName, isEarlyMediaOrRinging = true)
                }
                Call.State.Connected, Call.State.StreamsRunning -> {
                    startDurationTimer()
                    _callState.value = CallState.Connected(
                        remoteUri = remoteAddress,
                        displayName = remoteDisplayName,
                        durationSeconds = _callDuration.value,
                        isMuted = _isMuted.value,
                        isSpeakerOn = _isSpeakerOn.value,
                        isOnHold = _isOnHold.value
                    )
                }
                Call.State.Pausing, Call.State.Paused -> {
                    _isOnHold.value = true
                    updateConnectedStateIfActive()
                }
                Call.State.Resuming -> {
                    _isOnHold.value = false
                    updateConnectedStateIfActive()
                }
                Call.State.End, Call.State.Released, Call.State.Error -> {
                    val duration = _callDuration.value
                    val wasMissed = (_callState.value is CallState.Incoming)
                    stopDurationTimer()
                    _callState.value = CallState.Disconnected(reason = message.ifBlank { "Call Ended" })
                    currentLinphoneCall = null

                    scope.launch {
                        _callEvents.emit(
                            CallEvent.CallEnded(
                                remoteUri = remoteAddress,
                                displayName = remoteDisplayName,
                                durationSeconds = duration,
                                wasMissed = wasMissed
                            )
                        )
                        delay(1200)
                        if (_callState.value is CallState.Disconnected) {
                            _callState.value = CallState.Idle
                            _isMuted.value = false
                            _isSpeakerOn.value = false
                            _isOnHold.value = false
                            _callDuration.value = 0L
                        }
                    }
                }
                else -> {
                    Log.d(TAG, "Unhandled call state: $state")
                }
            }
        }
    }

    override fun initializeSdk() {
        if (core != null) return
        try {
            val factory = Factory.instance()
            factory.setDebugMode(true, "LinphoneSdk")
            try {
                factory.enableLogcatLogs(true)
            } catch (e: Throwable) {
                Log.d(TAG, "enableLogcatLogs: ${e.message}")
            }
            try {
                val loggingService = factory.loggingService
                loggingService.setLogLevel(LogLevel.Debug)
                loggingService.addListener { _, _, level, message ->
                    val line = message.trim()
                    if (line.isNotEmpty()) {
                        _diagnosticLogs.value = (_diagnosticLogs.value + "[${level.name}] $line").takeLast(50)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "LoggingService setup: ${e.message}")
            }

            val config = factory.createConfig(null)
            try {
                config.setInt("sip", "guess_hostname", 0)
                config.setBool("sip", "enable_enum_lookup", false)
                config.setString("sip", "enum_domain", "")
                config.setInt("sip", "inc_timeout", 60)
                config.setInt("sip", "in_call_timeout", 0)
                config.setInt("video", "capture", 0)
                config.setInt("video", "display", 0)
                config.setInt("video", "enabled", 0)
                config.setInt("video", "show_local", 0)
                config.setInt("video", "self_view", 0)
                config.setInt("video", "automatically_initiate", 0)
                config.setInt("video", "automatically_accept", 0)
                config.setString("video", "device", "")
            } catch (e: Throwable) {
                Log.d(TAG, "Config video/sip setup: ${e.message}")
            }
            val newCore = factory.createCoreWithConfig(config, context)
            try {
                newCore.isVideoCaptureEnabled = false
                newCore.isVideoDisplayEnabled = false
                val policy = newCore.videoActivationPolicy
                policy.automaticallyInitiate = false
                policy.automaticallyAccept = false
                newCore.videoActivationPolicy = policy
                try {
                    newCore.videoDevice = ""
                    newCore.captureDevice = ""
                } catch (_: Throwable) {}
            } catch (e: Throwable) {
                Log.w(TAG, "Could not configure video policy: ${e.message}")
            }
            try {
                newCore.isNativeRingingEnabled = true
            } catch (e: Throwable) {
                Log.w(TAG, "Native ringing setup: ${e.message}")
            }

            // Ensure network is active so Linphone sockets can send REGISTER packets
            newCore.isNetworkReachable = true
            try {
                newCore.setSipNetworkReachable(true)
            } catch (_: Throwable) {}

            // Enable dynamic ports for UDP, TCP and TLS (-1 is dynamic random port; 0 disabled the transport!)
            try {
                val transports = newCore.transports
                transports.udpPort = -1
                transports.tcpPort = -1
                transports.tlsPort = -1
                newCore.transports = transports
            } catch (e: Throwable) {
                Log.w(TAG, "Transports setup: ${e.message}")
            }

            newCore.addListener(coreListener)
            newCore.start()
            core = newCore
            applySettings(currentSettings)
            startIterateLoop()
            Log.i(TAG, "Linphone Core initialized and started successfully.")
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Linphone] Core started successfully").takeLast(50)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize Linphone Core: ${e.message}", e)
            _registrationMessage.value = "Linphone core init: ${e.message}"
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Error] Core init: ${e.message}").takeLast(50)
        }
    }

    override fun applySettings(settings: AppSettings) {
        currentSettings = settings
        val c = core ?: return
        try {
            c.isEchoCancellationEnabled = settings.echoCancellation
            c.isAdaptiveRateControlEnabled = settings.adaptiveRateControl
            c.isIpv6Enabled = settings.ipv6Enabled
            c.micGainDb = if (settings.micGainBoost) 6.0f else 0.0f
            if (settings.stunEnabled && settings.stunServer.isNotBlank()) {
                c.stunServer = settings.stunServer
                c.natPolicy?.stunServer = settings.stunServer
            } else {
                c.stunServer = null
            }
            Log.d(TAG, "Applied audio/network settings: AEC=${settings.echoCancellation}, AdaptiveRate=${settings.adaptiveRateControl}, IPv6=${settings.ipv6Enabled}")
        } catch (e: Throwable) {
            Log.w(TAG, "applySettings failed: ${e.message}")
        }
    }

    private fun startIterateLoop() {
        iterateJob?.cancel()
        iterateJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    core?.iterate()
                } catch (e: Exception) {
                    Log.w(TAG, "Exception in core.iterate: ${e.message}")
                }
                delay(20)
            }
        }
    }

    override fun registerAccount(account: SipAccount) {
        activeAccountModel = account
        initializeSdk()
        val c = core
        if (c == null) {
            // Simulated registration fallback for testing when native library is absent
            simulateRegistration(account)
            return
        }

        try {
            // Sanitize Domain & Host
            val rawDomain = account.domain.trim()
            val cleanDomain = rawDomain
                .removePrefix("sip:")
                .removePrefix("sips:")
                .removePrefix("http://")
                .removePrefix("https://")
                .trimEnd('/')
                .trim()

            val (host, port) = if (cleanDomain.contains(":")) {
                val parts = cleanDomain.split(":")
                parts[0].trim() to (parts.getOrNull(1)?.trim()?.toIntOrNull() ?: account.port)
            } else {
                cleanDomain to account.port
            }

            // Sanitize Username (remove sip: and any @domain if user typed full SIP URI)
            val cleanUsername = account.username.trim()
                .removePrefix("sip:")
                .substringBefore("@")
                .trim()

            _registrationState.value = RegistrationStatus.REGISTERING
            _registrationMessage.value = "Registering $cleanUsername@$host..."
            _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP] Registering $cleanUsername@$host:$port (${account.transport.name})").takeLast(50)

            c.clearAccounts()
            c.clearAllAuthInfo()

            val factory = Factory.instance()

            // 1. Host-scoped AuthInfo with userid=cleanUsername
            c.addAuthInfo(
                factory.createAuthInfo(
                    cleanUsername,
                    cleanUsername,
                    account.password,
                    null,
                    null,
                    host
                )
            )

            // 2. Host-scoped AuthInfo with userid=null
            c.addAuthInfo(
                factory.createAuthInfo(
                    cleanUsername,
                    null,
                    account.password,
                    null,
                    null,
                    host
                )
            )

            // 3. Realm-scoped AuthInfo with realm=host
            c.addAuthInfo(
                factory.createAuthInfo(
                    cleanUsername,
                    null,
                    account.password,
                    null,
                    host,
                    null
                )
            )

            // 4. Universal AuthInfo (realm=null, domain=null)
            // Essential for PBXs (e.g. Asterisk / FreePBX) that challenge with custom realms (e.g. realm="asterisk")
            c.addAuthInfo(
                factory.createAuthInfo(
                    cleanUsername,
                    null,
                    account.password,
                    null,
                    null,
                    null
                )
            )
            c.addAuthInfo(
                factory.createAuthInfo(
                    cleanUsername,
                    cleanUsername,
                    account.password,
                    null,
                    null,
                    null
                )
            )

            val accountParams = c.createAccountParams()
            val identityAddress: Address? = factory.createAddress("sip:$cleanUsername@$host")
            identityAddress?.displayName = account.displayName.ifBlank { cleanUsername }
            accountParams.identityAddress = identityAddress

            val transportStr = when (account.transport) {
                SipTransport.UDP -> "udp"
                SipTransport.TCP -> "tcp"
                SipTransport.TLS -> "tls"
            }
            val serverUriStr = "sip:$host:$port;transport=$transportStr"
            val serverAddress: Address? = factory.createAddress(serverUriStr)
            accountParams.serverAddress = serverAddress
            accountParams.isRegisterEnabled = true
            accountParams.expires = 300
            accountParams.isOutboundProxyEnabled = false
            try {
                accountParams.isDialEscapePlusEnabled = false
                accountParams.useInternationalPrefixForCallsAndChats = false
                accountParams.internationalPrefix = null
            } catch (_: Throwable) {}

            val linphoneAccount = c.createAccount(accountParams)
            c.addAccount(linphoneAccount)
            c.defaultAccount = linphoneAccount
            Log.i(TAG, "Registered account configured for $serverUriStr")
        } catch (e: Throwable) {
            Log.e(TAG, "Error configuring SIP account: ${e.message}", e)
            _registrationState.value = RegistrationStatus.FAILED
            _registrationMessage.value = "Registration error: ${e.message}"
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Error] Config error: ${e.message}").takeLast(50)
        }
    }

    private fun simulateRegistration(account: SipAccount) {
        scope.launch {
            _registrationState.value = RegistrationStatus.REGISTERING
            _registrationMessage.value = "Connecting to ${account.domain}..."
            delay(1000)
            _registrationState.value = RegistrationStatus.REGISTERED
            _registrationMessage.value = "Registered (${account.transport.name} / Port ${account.port})"
        }
    }

    override fun unregisterCurrentAccount() {
        core?.let { c ->
            c.clearAccounts()
            c.clearAllAuthInfo()
        }
        _registrationState.value = RegistrationStatus.UNREGISTERED
        _registrationMessage.value = "Unregistered"
    }

    override fun makeCall(destinationUri: String, displayName: String) {
        initializeSdk()
        val rawDest = destinationUri.trim()
        val cleanDest = rawDest.replace(Regex("[\\s\\-\\(\\)]"), "")
        val numberOrUser = cleanDest.removePrefix("sip:").removePrefix("sips:").trim()

        val c = core
        val activeAcc = activeAccountModel
        val domainFromAccount = activeAcc?.domain?.trim()?.let { d ->
            d.removePrefix("sip:").removePrefix("sips:").removePrefix("http://").removePrefix("https://").trimEnd('/').split(":")[0].trim()
        } ?: c?.defaultAccount?.params?.serverAddress?.domain ?: c?.defaultAccount?.params?.identityAddress?.domain

        val (targetUser, targetDomain) = if (numberOrUser.contains("@")) {
            val parts = numberOrUser.split("@", limit = 2)
            parts[0] to parts[1]
        } else {
            numberOrUser to (domainFromAccount ?: "")
        }

        val finalSipUri = if (targetDomain.isNotBlank()) {
            "sip:$targetUser@$targetDomain"
        } else {
            "sip:$targetUser"
        }

        val effectiveDisplayName = if (displayName.isNotBlank() && !displayName.startsWith("sip:")) {
            displayName
        } else {
            targetUser
        }

        if (c == null) {
            simulateOutgoingCall(finalSipUri, effectiveDisplayName)
            return
        }

        try {
            val factory = Factory.instance()
            val account = c.defaultAccount ?: c.accountList.firstOrNull()

            val address: Address? = try {
                factory.createAddress(finalSipUri) ?: c.createAddress(finalSipUri)
            } catch (e: Exception) {
                c.interpretUrl(finalSipUri)
            }

            if (address == null) {
                Log.w(TAG, "Could not parse URI: $finalSipUri, fallback to simulated call")
                _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Call] Failed to parse URI: $finalSipUri").takeLast(50)
                simulateOutgoingCall(finalSipUri, effectiveDisplayName)
                return
            }

            val callParams = c.createCallParams(null)
            if (callParams != null) {
                if (account != null) {
                    callParams.account = account
                }
                callParams.isAudioEnabled = true
                callParams.isVideoEnabled = false
                try {
                    callParams.isEarlyMediaSendingEnabled = true
                } catch (_: Throwable) {}
            }

            val dialLog = "[SIP Call] Dialing: ${address.asStringUriOnly()} via ${account?.params?.identityAddress?.asStringUriOnly() ?: "default transport"}"
            Log.i(TAG, dialLog)
            _diagnosticLogs.value = (_diagnosticLogs.value + dialLog).takeLast(50)

            val call = if (callParams != null) {
                c.inviteAddressWithParams(address, callParams)
            } else {
                c.inviteAddress(address) ?: c.invite(finalSipUri)
            }

            if (call == null) {
                Log.w(TAG, "inviteAddressWithParams returned null for $finalSipUri, fallback simulated")
                _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Call] Linphone invite returned null for $finalSipUri").takeLast(50)
                simulateOutgoingCall(finalSipUri, effectiveDisplayName)
            } else {
                currentLinphoneCall = call
                _callState.value = CallState.Outgoing(
                    remoteUri = address.asStringUriOnly(),
                    displayName = effectiveDisplayName,
                    isEarlyMediaOrRinging = false
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error initiating Linphone call: ${e.message}", e)
            _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Call Error] ${e.message}").takeLast(50)
            simulateOutgoingCall(finalSipUri, effectiveDisplayName)
        }
    }

    override fun acceptCall() {
        val call = currentLinphoneCall
        if (call != null) {
            try {
                call.accept()
            } catch (e: Throwable) {
                Log.e(TAG, "Error accepting call: ${e.message}", e)
            }
        } else {
            val current = _callState.value
            if (current is CallState.Incoming) {
                startDurationTimer()
                _callState.value = CallState.Connected(
                    remoteUri = current.remoteUri,
                    displayName = current.displayName,
                    durationSeconds = 0L,
                    isMuted = _isMuted.value,
                    isSpeakerOn = _isSpeakerOn.value,
                    isOnHold = false
                )
            }
        }
    }

    override fun hangupCall() {
        val call = currentLinphoneCall
        if (call != null) {
            try {
                call.terminate()
            } catch (e: Throwable) {
                Log.e(TAG, "Error terminating call: ${e.message}", e)
            }
        } else {
            val current = _callState.value
            val duration = _callDuration.value
            val remoteUri = when (current) {
                is CallState.Connected -> current.remoteUri
                is CallState.Outgoing -> current.remoteUri
                is CallState.Incoming -> current.remoteUri
                else -> "Unknown"
            }
            val dispName = when (current) {
                is CallState.Connected -> current.displayName
                is CallState.Outgoing -> current.displayName
                is CallState.Incoming -> current.displayName
                else -> remoteUri
            }
            val wasMissed = (current is CallState.Incoming)

            stopDurationTimer()
            _callState.value = CallState.Disconnected("Call Terminated")

            scope.launch {
                _callEvents.emit(
                    CallEvent.CallEnded(
                        remoteUri = remoteUri,
                        displayName = dispName,
                        durationSeconds = duration,
                        wasMissed = wasMissed
                    )
                )
                delay(1200)
                _callState.value = CallState.Idle
                _callDuration.value = 0L
                _isMuted.value = false
                _isSpeakerOn.value = false
                _isOnHold.value = false
            }
        }
    }

    override fun toggleMute() {
        val newMute = !_isMuted.value
        _isMuted.value = newMute
        currentLinphoneCall?.let {
            it.microphoneMuted = newMute
        }
        updateConnectedStateIfActive()
    }

    override fun toggleSpeaker() {
        val newSpeaker = !_isSpeakerOn.value
        _isSpeakerOn.value = newSpeaker
        try {
            audioManager.isSpeakerphoneOn = newSpeaker
        } catch (e: Exception) {
            Log.w(TAG, "Failed to toggle speakerphone: ${e.message}")
        }
        updateConnectedStateIfActive()
    }

    override fun toggleHold() {
        val newHold = !_isOnHold.value
        _isOnHold.value = newHold
        currentLinphoneCall?.let {
            if (newHold) it.pause() else it.resume()
        }
        updateConnectedStateIfActive()
    }

    override fun sendDtmf(dtmfChar: Char) {
        dtmfTonePlayer.playTone(dtmfChar)
        currentLinphoneCall?.let {
            try {
                it.sendDtmf(dtmfChar)
            } catch (e: Throwable) {
                Log.w(TAG, "Error sending DTMF: ${e.message}")
            }
        }
    }

    override fun simulateIncomingCall(callerUri: String, callerName: String) {
        if (_callState.value !is CallState.Idle) return
        _callState.value = CallState.Incoming(
            remoteUri = callerUri,
            displayName = callerName.ifBlank { callerUri }
        )
        scope.launch {
            _callEvents.emit(CallEvent.CallStarted(callerUri, callerName, isIncoming = true))
        }
    }

    override fun simulateAutoAnswer() {
        acceptCall()
    }

    private fun simulateOutgoingCall(uri: String, displayName: String) {
        scope.launch {
            _callState.value = CallState.Outgoing(uri, displayName, isEarlyMediaOrRinging = false)
            _callEvents.emit(CallEvent.CallStarted(uri, displayName, isIncoming = false))
            delay(1500)
            if (_callState.value is CallState.Outgoing) {
                _callState.value = CallState.Outgoing(uri, displayName, isEarlyMediaOrRinging = true)
                delay(2000)
                if (_callState.value is CallState.Outgoing) {
                    startDurationTimer()
                    _callState.value = CallState.Connected(
                        remoteUri = uri,
                        displayName = displayName,
                        durationSeconds = 0L,
                        isMuted = _isMuted.value,
                        isSpeakerOn = _isSpeakerOn.value,
                        isOnHold = false
                    )
                }
            }
        }
    }

    override fun addParticipantToCall(destinationUri: String, displayName: String) {
        val current = _callState.value
        val cleanDest = destinationUri.trim()
        if (cleanDest.isBlank()) return

        val effectiveDisplayName = displayName.ifBlank { cleanDest.removePrefix("sip:").substringBefore("@") }

        if (current is CallState.Connected) {
            if (current.isConference) {
                // Add directly to existing conference
                val newParticipant = ConferenceParticipant(
                    id = "conf_p_${System.currentTimeMillis()}",
                    uri = cleanDest,
                    displayName = effectiveDisplayName,
                    isMuted = false,
                    isOnHold = false,
                    joinedAtSeconds = _callDuration.value
                )
                val updatedList = current.participants + newParticipant
                _callState.value = current.copy(participants = updatedList)
                _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] Added participant: $effectiveDisplayName ($cleanDest)").takeLast(50)

                // If Linphone SDK is connected, invite and add to conference
                core?.let { c ->
                    try {
                        val factory = Factory.instance()
                        val address = factory.createAddress(cleanDest) ?: c.interpretUrl(cleanDest)
                        if (address != null) {
                            val callParams = c.createCallParams(null)
                            val newCall = if (callParams != null) {
                                c.inviteAddressWithParams(address, callParams)
                            } else {
                                c.inviteAddress(address)
                            }
                            newCall?.let {
                                try {
                                    c.addAllToConference()
                                } catch (_: Throwable) {}
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Linphone add participant error: ${e.message}")
                    }
                }
            } else {
                // Put 1st call on hold, start 2nd call as secondary held call
                _isOnHold.value = true
                currentLinphoneCall?.let {
                    try { it.pause() } catch (_: Throwable) {}
                }
                val secCall = SecondaryCallInfo(
                    uri = cleanDest,
                    displayName = effectiveDisplayName,
                    isOnHold = false,
                    durationSeconds = 0L
                )
                _callState.value = current.copy(
                    isOnHold = true,
                    secondaryCall = secCall
                )
                _diagnosticLogs.value = (_diagnosticLogs.value + "[Call] Line 1 on hold. Dialing Line 2: $effectiveDisplayName ($cleanDest)").takeLast(50)
            }
        }
    }

    override fun mergeCallsIntoConference() {
        val current = _callState.value
        if (current is CallState.Connected && current.secondaryCall != null) {
            val sec = current.secondaryCall
            val p1 = ConferenceParticipant(
                id = "conf_p_1",
                uri = current.remoteUri,
                displayName = current.displayName.ifBlank { current.remoteUri.removePrefix("sip:").substringBefore("@") },
                isMuted = false,
                isOnHold = false,
                joinedAtSeconds = 0L
            )
            val p2 = ConferenceParticipant(
                id = "conf_p_2",
                uri = sec.uri,
                displayName = sec.displayName.ifBlank { sec.uri.removePrefix("sip:").substringBefore("@") },
                isMuted = false,
                isOnHold = false,
                joinedAtSeconds = _callDuration.value
            )
            _isOnHold.value = false
            _callState.value = current.copy(
                isConference = true,
                participants = listOf(p1, p2),
                secondaryCall = null,
                isOnHold = false
            )
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] Merged calls into multi-party conference session (2 participants)").takeLast(50)

            core?.let { c ->
                try {
                    c.addAllToConference()
                } catch (e: Throwable) {
                    Log.w(TAG, "Linphone addAllToConference: ${e.message}")
                }
            }
        }
    }

    override fun removeParticipantFromConference(participantId: String) {
        val current = _callState.value
        if (current is CallState.Connected && current.isConference) {
            val updated = current.participants.filterNot { it.id == participantId }
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] Participant removed: $participantId").takeLast(50)

            if (updated.size <= 1) {
                val remaining = updated.firstOrNull()
                if (remaining != null) {
                    // Revert to 1-on-1 call
                    _callState.value = current.copy(
                        remoteUri = remaining.uri,
                        displayName = remaining.displayName,
                        isConference = false,
                        participants = emptyList()
                    )
                    _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] Reverted to 1-on-1 call with ${remaining.displayName}").takeLast(50)
                } else {
                    hangupCall()
                }
            } else {
                _callState.value = current.copy(participants = updated)
            }
        }
    }

    override fun toggleParticipantMute(participantId: String) {
        val current = _callState.value
        if (current is CallState.Connected && current.isConference) {
            val updated = current.participants.map { p ->
                if (p.id == participantId) p.copy(isMuted = !p.isMuted) else p
            }
            _callState.value = current.copy(participants = updated)
        }
    }

    override fun swapActiveAndHeldCalls() {
        val current = _callState.value
        if (current is CallState.Connected && current.secondaryCall != null) {
            val sec = current.secondaryCall
            val newPrimaryUri = sec.uri
            val newPrimaryName = sec.displayName
            val newSec = SecondaryCallInfo(
                uri = current.remoteUri,
                displayName = current.displayName,
                isOnHold = true,
                durationSeconds = current.durationSeconds
            )
            _callState.value = current.copy(
                remoteUri = newPrimaryUri,
                displayName = newPrimaryName,
                secondaryCall = newSec,
                isOnHold = false
            )
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Call] Swapped active and held lines. Active: $newPrimaryName").takeLast(50)
        }
    }

    private fun updateConnectedStateIfActive() {
        val current = _callState.value
        if (current is CallState.Connected) {
            _callState.value = current.copy(
                isMuted = _isMuted.value,
                isSpeakerOn = _isSpeakerOn.value,
                isOnHold = _isOnHold.value
            )
        }
    }

    private fun startDurationTimer() {
        durationTimerJob?.cancel()
        _callDuration.value = 0L
        durationTimerJob = scope.launch {
            while (isActive) {
                delay(1000)
                _callDuration.value += 1
                val current = _callState.value
                if (current is CallState.Connected) {
                    _callState.value = current.copy(durationSeconds = _callDuration.value)
                }
            }
        }
    }

    private fun stopDurationTimer() {
        durationTimerJob?.cancel()
        durationTimerJob = null
    }

    companion object {
        private const val TAG = "LinphoneSipManager"
    }
}
