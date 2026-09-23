package com.example.sip

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.example.data.model.AccountBalance
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
import org.linphone.core.AudioDevice
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
    val accountBalance: StateFlow<AccountBalance?>
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
    fun hangupSecondaryCall()

    fun addDiagnosticLog(log: String)
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
    private var primaryLinphoneCall: Call? = null
    private var secondaryLinphoneCall: Call? = null
    private var durationTimerJob: Job? = null
    private var confStatsJob: Job? = null
    private var activeAccountModel: SipAccount? = null
    private var currentSettings: AppSettings = AppSettings()

    private val _registrationState = MutableStateFlow(RegistrationStatus.UNREGISTERED)
    override val registrationState: StateFlow<RegistrationStatus> = _registrationState.asStateFlow()

    private val _registrationMessage = MutableStateFlow("Ready to configure account")
    override val registrationMessage: StateFlow<String> = _registrationMessage.asStateFlow()

    private val _accountBalance = MutableStateFlow<AccountBalance?>(null)
    override val accountBalance: StateFlow<AccountBalance?> = _accountBalance.asStateFlow()

    private val _diagnosticLogs = MutableStateFlow<List<String>>(emptyList())
    override val diagnosticLogs: StateFlow<List<String>> = _diagnosticLogs.asStateFlow()

    override fun clearLogs() {
        _diagnosticLogs.value = emptyList()
    }

    override fun addDiagnosticLog(log: String) {
        _diagnosticLogs.value = (_diagnosticLogs.value + log).takeLast(60)
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
                    // iTelSwitchPlus reports the prepaid balance on every REGISTER 200 OK
                    // (refreshed ~every minute), e.g. "Balance=18.855 ... Currency=BDT"
                    try {
                        AccountBalance.parse(account?.getCustomHeader("iTelSwitchPlus"))?.let {
                            _accountBalance.value = it
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Balance header: ${e.message}")
                    }
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
            Log.d(TAG, "Linphone call state changed: $state, message: $message, addr=${call.remoteAddress?.asStringUriOnly()}")
            val remoteAddress = call.remoteAddress?.asStringUriOnly() ?: "Unknown"
            val remoteDisplayName = call.remoteAddress?.displayName?.ifBlank { null } ?: remoteAddress
            val current = _callState.value

            // While in a conference the SDK re-INVITEs/pauses the member calls itself; those
            // events must not overwrite the conference state or toggle our hold flag.
            if (current is CallState.Connected && current.isConference &&
                handleConferenceCallState(core, call, state, remoteAddress, remoteDisplayName, current)
            ) {
                return
            }

            // Detect if this state event belongs to the secondary line / 2nd call
            val isSecondary = (current is CallState.Connected && current.secondaryCall != null &&
                    (call == secondaryLinphoneCall || (primaryLinphoneCall != null && call != primaryLinphoneCall)))

            if (isSecondary && current is CallState.Connected) {
                secondaryLinphoneCall = call
                when (state) {
                    Call.State.OutgoingInit, Call.State.OutgoingProgress -> {
                        _diagnosticLogs.value = (_diagnosticLogs.value + "[Line 2] Calling: $remoteDisplayName").takeLast(50)
                        _callState.value = current.copy(
                            isOnHold = true,
                            secondaryCall = current.secondaryCall?.copy(
                                uri = remoteAddress,
                                displayName = remoteDisplayName,
                                isOnHold = false
                            )
                        )
                    }
                    Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> {
                        _diagnosticLogs.value = (_diagnosticLogs.value + "[Line 2] Ringing: $remoteDisplayName").takeLast(50)
                        _callState.value = current.copy(
                            isOnHold = true,
                            secondaryCall = current.secondaryCall?.copy(
                                uri = remoteAddress,
                                displayName = remoteDisplayName,
                                isOnHold = false
                            )
                        )
                    }
                    Call.State.Connected, Call.State.StreamsRunning -> {
                        _diagnosticLogs.value = (_diagnosticLogs.value + "[Line 2] Connected! 2 calls active. Merge option ready.").takeLast(50)
                        _callState.value = current.copy(
                            isOnHold = true,
                            secondaryCall = current.secondaryCall?.copy(
                                uri = remoteAddress,
                                displayName = remoteDisplayName,
                                isOnHold = false,
                                isConnected = true
                            ) ?: SecondaryCallInfo(
                                uri = remoteAddress,
                                displayName = remoteDisplayName,
                                isOnHold = false,
                                isConnected = true
                            )
                        )
                    }
                    Call.State.Pausing, Call.State.Paused -> {
                        _callState.value = current.copy(
                            secondaryCall = current.secondaryCall?.copy(isOnHold = true)
                        )
                    }
                    Call.State.Resuming -> {
                        _callState.value = current.copy(
                            secondaryCall = current.secondaryCall?.copy(isOnHold = false)
                        )
                    }
                    Call.State.End, Call.State.Released, Call.State.Error -> {
                        _diagnosticLogs.value = (_diagnosticLogs.value + "[Line 2] Call ended ($message). Resuming Line 1.").takeLast(50)
                        secondaryLinphoneCall = null
                        primaryLinphoneCall?.resume()
                        currentLinphoneCall = primaryLinphoneCall
                        _isOnHold.value = false
                        _callState.value = current.copy(
                            isOnHold = false,
                            secondaryCall = null
                        )
                    }
                    else -> {
                        Log.d(TAG, "Unhandled secondary call state: $state")
                    }
                }
                return
            }

            // Primary call handling (never point at a call that is already over)
            if (state !in TERMINAL_STATES) {
                currentLinphoneCall = call
                if (primaryLinphoneCall == null) {
                    primaryLinphoneCall = call
                }
            }

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
                    // StreamsRunning repeats after every re-INVITE; don't reset the timer each time
                    if (_callState.value !is CallState.Connected) {
                        startDurationTimer()
                    }
                    val existingConnected = _callState.value as? CallState.Connected
                    _callState.value = CallState.Connected(
                        remoteUri = remoteAddress,
                        displayName = remoteDisplayName,
                        durationSeconds = _callDuration.value,
                        isMuted = _isMuted.value,
                        isSpeakerOn = _isSpeakerOn.value,
                        isOnHold = _isOnHold.value,
                        isConference = existingConnected?.isConference ?: false,
                        participants = existingConnected?.participants ?: emptyList(),
                        secondaryCall = existingConnected?.secondaryCall
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
                    // If Line 1 ended but Line 2 is still active, promote Line 2 to primary!
                    val sec = (_callState.value as? CallState.Connected)?.secondaryCall
                    if (sec != null && secondaryLinphoneCall != null) {
                        _diagnosticLogs.value = (_diagnosticLogs.value + "[Call] Line 1 ended. Switching to Line 2.").takeLast(50)
                        primaryLinphoneCall = secondaryLinphoneCall
                        secondaryLinphoneCall = null
                        currentLinphoneCall = primaryLinphoneCall
                        primaryLinphoneCall?.resume()
                        _isOnHold.value = false
                        _callState.value = (_callState.value as CallState.Connected).copy(
                            remoteUri = sec.uri,
                            displayName = sec.displayName,
                            isOnHold = false,
                            secondaryCall = null
                        )
                        return
                    }

                    // Another call is still up (e.g. the last member left after a conference):
                    // keep it as the active line instead of tearing the UI down.
                    val others = liveCalls(core, exclude = call)
                    if (others.isNotEmpty()) {
                        if (primaryLinphoneCall !in others) {
                            promoteRemainingCall(others.first())
                        }
                        return
                    }

                    val duration = _callDuration.value
                    val wasMissed = (_callState.value is CallState.Incoming)
                    stopDurationTimer()
                    try { core.isMicEnabled = true } catch (_: Throwable) {}
                    _callState.value = CallState.Disconnected(reason = message.ifBlank { "Call Ended" })
                    currentLinphoneCall = null
                    primaryLinphoneCall = null
                    secondaryLinphoneCall = null

                    scope.launch {
                        _callEvents.emit(
                            CallEvent.CallEnded(
                                remoteUri = remoteAddress,
                                displayName = remoteDisplayName,
                                durationSeconds = duration,
                                wasMissed = wasMissed
                            )
                        )
                        refreshBalanceSoon()
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

    /**
     * Returns true when the event was fully handled here. Returns false only when the last
     * conference call ended, so the normal end-of-call flow runs.
     */
    private fun handleConferenceCallState(
        c: Core,
        call: Call,
        state: Call.State,
        remoteAddress: String,
        remoteDisplayName: String,
        current: CallState.Connected
    ): Boolean {
        if (state !in TERMINAL_STATES) {
            // A call that got answered after the merge must still be pulled into the mixer
            val conf = c.conference
            if (state == Call.State.StreamsRunning && call.conference == null && conf != null) {
                try {
                    conf.addParticipant(call)
                    _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] $remoteDisplayName answered, added to the mixer").takeLast(50)
                } catch (e: Throwable) {
                    Log.w(TAG, "Late add to conference: ${e.message}")
                }
            }
            return true
        }
        val others = liveCalls(c, exclude = call)
        when {
            others.isEmpty() -> return false
            others.size == 1 -> {
                _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] $remoteDisplayName left. Back to 1-on-1 call.").takeLast(50)
                promoteRemainingCall(others.first())
            }
            else -> {
                val remaining = current.participants.filterNot { userPart(it.uri) == userPart(remoteAddress) }
                if (remaining.size != current.participants.size) {
                    _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] $remoteDisplayName left the conference").takeLast(50)
                    _callState.value = current.copy(participants = remaining)
                }
            }
        }
        return true
    }

    private fun promoteRemainingCall(remaining: Call) {
        primaryLinphoneCall = remaining
        currentLinphoneCall = remaining
        secondaryLinphoneCall = null
        if (remaining.state == Call.State.Paused) {
            try { remaining.resume() } catch (_: Throwable) {}
        }
        _isOnHold.value = false
        val uri = remaining.remoteAddress?.asStringUriOnly() ?: "Unknown"
        val name = remaining.remoteAddress?.displayName?.ifBlank { null } ?: uri
        val cur = _callState.value as? CallState.Connected ?: return
        _callState.value = cur.copy(
            remoteUri = uri,
            displayName = name,
            isOnHold = false,
            isConference = false,
            participants = emptyList(),
            secondaryCall = null
        )
    }

    /**
     * Every 2s while in a conference, logs per leg: codec, direction, RTP in/out kbit/s and loss.
     * "in 0.0" on a leg means that caller's audio never reaches the mixer.
     */
    private fun startConferenceStatsLogger() {
        confStatsJob?.cancel()
        confStatsJob = scope.launch {
            while (isActive && (_callState.value as? CallState.Connected)?.isConference == true) {
                delay(2000)
                val c = core ?: break
                val conf = c.conference
                val header = "[Conf Stats] conf=${conf?.state} in=${conf?.isIn} members=${conf?.participantCount}"
                Log.i(TAG, header)
                _diagnosticLogs.value = (_diagnosticLogs.value + header).takeLast(50)
                for (call in liveCalls(c)) {
                    val pt = call.currentParams?.usedAudioPayloadType
                    val st = call.audioStats
                    val line = "[Conf Stats] ${userPart(call.remoteAddress?.asStringUriOnly().orEmpty())} " +
                        "state=${call.state} inConf=${call.conference != null} " +
                        "codec=${pt?.mimeType}/${pt?.clockRate} pt=${pt?.number} dir=${call.currentParams?.audioDirection} " +
                        "in=${"%.1f".format(st?.downloadBandwidth ?: 0f)}kbps out=${"%.1f".format(st?.uploadBandwidth ?: 0f)}kbps " +
                        "loss=${"%.1f".format(st?.receiverLossRate ?: 0f)}%"
                    Log.i(TAG, line)
                    _diagnosticLogs.value = (_diagnosticLogs.value + line).takeLast(50)
                }
            }
        }
    }

    /** Re-REGISTER shortly after a call so the balance header shows the post-call balance. */
    private fun refreshBalanceSoon() {
        scope.launch {
            delay(1500)
            try { core?.defaultAccount?.refreshRegister() } catch (e: Throwable) { Log.w(TAG, "Balance refresh: ${e.message}") }
        }
    }

    private fun liveCalls(c: Core, exclude: Call? = null): List<Call> =
        c.calls.filter { it != exclude && it.state !in TERMINAL_STATES }

    private fun userPart(uri: String): String =
        uri.removePrefix("sip:").removePrefix("sips:").substringBefore("@").substringBefore(";")

    /** Routes audio for the active call or conference through Linphone, which owns the audio path. */
    private fun applyAudioRoute(c: Core, speaker: Boolean) {
        val playable = c.audioDevices.filter { it.hasCapability(AudioDevice.Capabilities.CapabilityPlay) }
        val device = if (speaker) {
            playable.firstOrNull { it.type == AudioDevice.Type.Speaker }
        } else {
            val preferred = listOf(
                AudioDevice.Type.Bluetooth,
                AudioDevice.Type.Headset,
                AudioDevice.Type.Headphones,
                AudioDevice.Type.Earpiece
            )
            preferred.firstNotNullOfOrNull { type -> playable.firstOrNull { it.type == type } }
        } ?: return
        val conf = c.conference
        if (conf != null && conf.isIn) {
            conf.outputAudioDevice = device
        } else {
            c.outputAudioDevice = device
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
                        // Mirror SDK logs (SDP, RTP, conference) to logcat so adb can capture them
                        Log.println(if (level == LogLevel.Error || level == LogLevel.Fatal) Log.ERROR else Log.DEBUG, "LinphoneSdk", line)
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

            // Offer only G.711 (+ DTMF). iTelSwitchPlus answers re-INVITEs by echoing the whole
            // offer instead of picking one codec, so after the conference re-INVITE Linphone
            // switched that leg to opus while the carrier kept sending PCMU: the caller heard
            // noise and the other party heard nothing.
            try {
                for (pt in newCore.audioPayloadTypes) {
                    val keep = (pt.mimeType.equals("PCMU", true) || pt.mimeType.equals("PCMA", true) ||
                        pt.mimeType.equals("telephone-event", true)) && pt.clockRate == 8000
                    pt.enable(keep)
                }
                Log.i(TAG, "Audio codecs: " + newCore.audioPayloadTypes.filter { it.enabled() }
                    .joinToString { "${it.mimeType}/${it.clockRate}" })
            } catch (e: Throwable) {
                Log.w(TAG, "Codec setup: ${e.message}")
            }

            // Stream hold music to a paused call. Without it no RTP flows while on hold and the
            // switch (iTelSwitchPlus, ~30s RTP timeout) hangs up the held caller, e.g. line 1
            // while the agent is still dialing line 2 for a 3-way call.
            try {
                val holdMusic = java.io.File(context.filesDir, "share/sounds/linphone/toy-mono.wav")
                if (holdMusic.exists()) {
                    newCore.playFile = holdMusic.absolutePath
                } else {
                    Log.w(TAG, "Hold music not found at ${holdMusic.absolutePath}")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Hold music setup: ${e.message}")
            }

            // The SDK's CoreManager iterates the core on the main thread. The core is not
            // thread-safe, so never iterate it from another thread as well.
            newCore.isAutoIterateEnabled = true

            newCore.addListener(coreListener)
            newCore.start()
            core = newCore
            applySettings(currentSettings)
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
        _accountBalance.value = null
    }

    override fun makeCall(destinationUri: String, displayName: String) {
        initializeSdk()
        val current = _callState.value
        if (current is CallState.Connected) {
            addParticipantToCall(destinationUri, displayName)
            return
        }

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
        Log.i(TAG, "hangupCall triggered: Terminating all calls and conference.")
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

        // 1. Terminate Linphone conference and ALL active/held calls so both parties hang up
        core?.let { c ->
            try {
                c.conference?.terminate()
            } catch (e: Throwable) {
                Log.w(TAG, "Conference terminate: ${e.message}")
            }
            try {
                c.terminateAllCalls()
            } catch (e: Throwable) {
                Log.w(TAG, "Linphone terminateAllCalls: ${e.message}")
            }
            try {
                primaryLinphoneCall?.terminate()
            } catch (_: Throwable) {}
            try {
                secondaryLinphoneCall?.terminate()
            } catch (_: Throwable) {}
            try {
                currentLinphoneCall?.terminate()
            } catch (_: Throwable) {}
            try {
                for (call in c.calls) {
                    call.terminate()
                }
            } catch (_: Throwable) {}
            try { c.isMicEnabled = true } catch (_: Throwable) {}
        }

        primaryLinphoneCall = null
        secondaryLinphoneCall = null
        currentLinphoneCall = null

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
            refreshBalanceSoon()
            delay(1200)
            _callState.value = CallState.Idle
            _callDuration.value = 0L
            _isMuted.value = false
            _isSpeakerOn.value = false
            _isOnHold.value = false
        }
    }

    override fun toggleMute() {
        val newMute = !_isMuted.value
        _isMuted.value = newMute
        // Core-level mic mute covers a single call and the conference mixer alike;
        // muting one call only silenced one leg of a conference.
        val c = core
        if (c != null) {
            try { c.isMicEnabled = !newMute } catch (e: Throwable) { Log.w(TAG, "Mic toggle: ${e.message}") }
        }
        updateConnectedStateIfActive()
    }

    override fun toggleSpeaker() {
        val newSpeaker = !_isSpeakerOn.value
        _isSpeakerOn.value = newSpeaker
        try {
            val c = core
            if (c != null) {
                applyAudioRoute(c, newSpeaker)
            } else {
                audioManager.isSpeakerphoneOn = newSpeaker
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to toggle speakerphone: ${e.message}")
        }
        updateConnectedStateIfActive()
    }

    override fun toggleHold() {
        val newHold = !_isOnHold.value
        _isOnHold.value = newHold
        val conf = core?.conference
        if ((_callState.value as? CallState.Connected)?.isConference == true && conf != null) {
            // Holding a conference means stepping out of the mixer; the others keep talking
            try {
                if (newHold) conf.leave() else conf.enter()
            } catch (e: Throwable) {
                Log.w(TAG, "Conference hold: ${e.message}")
            }
        } else {
            currentLinphoneCall?.let {
                if (newHold) it.pause() else it.resume()
            }
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
        val rawDest = destinationUri.trim()
        if (rawDest.isBlank()) return

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

        if (current is CallState.Connected) {
            if (current.isConference) {
                // Add directly to existing conference
                val newParticipant = ConferenceParticipant(
                    id = "conf_p_${System.currentTimeMillis()}",
                    uri = finalSipUri,
                    displayName = effectiveDisplayName,
                    isMuted = false,
                    isOnHold = false,
                    joinedAtSeconds = _callDuration.value
                )
                val updatedList = current.participants + newParticipant
                _callState.value = current.copy(participants = updatedList)
                _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] Inviting participant: $effectiveDisplayName ($finalSipUri)").takeLast(50)

                // Let the conference place the call itself: it joins the mixer once answered.
                // (Adding a still-ringing call via addAllToConference broke the mixer.)
                c?.let { coreInstance ->
                    try {
                        val factory = Factory.instance()
                        val address = factory.createAddress(finalSipUri) ?: coreInstance.interpretUrl(finalSipUri)
                        if (address != null) {
                            val callParams = coreInstance.createCallParams(null)
                            callParams?.isVideoEnabled = false
                            val conf = coreInstance.conference
                            if (conf != null) {
                                conf.inviteParticipants(arrayOf(address), callParams)
                            } else if (callParams != null) {
                                coreInstance.inviteAddressWithParams(address, callParams)
                            } else {
                                coreInstance.inviteAddress(address)
                            }
                            _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP] Conference INVITE sent to $finalSipUri").takeLast(50)
                        } else {
                            _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Error] Invalid conference URI: $finalSipUri").takeLast(50)
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Linphone add participant error: ${e.message}")
                        _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Error] Conference invite error: ${e.message}").takeLast(50)
                    }
                }
            } else if (c != null && (primaryLinphoneCall ?: currentLinphoneCall) != null &&
                startConferenceAndInvite(c, current, finalSipUri, effectiveDisplayName)
            ) {
                return
            } else {
                // Fallback: put 1st call on hold, start 2nd call as secondary active call
                if (primaryLinphoneCall == null) {
                    primaryLinphoneCall = currentLinphoneCall
                }
                primaryLinphoneCall?.let {
                    try { it.pause() } catch (_: Throwable) {}
                }
                _isOnHold.value = true

                val secCall = SecondaryCallInfo(
                    uri = finalSipUri,
                    displayName = effectiveDisplayName,
                    isOnHold = false,
                    durationSeconds = 0L
                )
                _callState.value = current.copy(
                    isOnHold = true,
                    secondaryCall = secCall
                )
                _diagnosticLogs.value = (_diagnosticLogs.value + "[Call] Line 1 placed on hold. Dialing Line 2: $effectiveDisplayName ($finalSipUri)").takeLast(50)

                // Actually transmit SIP INVITE on 2nd Line via Linphone Core
                c?.let { coreInstance ->
                    try {
                        val factory = Factory.instance()
                        val address = factory.createAddress(finalSipUri) ?: coreInstance.interpretUrl(finalSipUri)
                        if (address != null) {
                            val callParams = coreInstance.createCallParams(null)
                            val newCall = if (callParams != null) {
                                coreInstance.inviteAddressWithParams(address, callParams)
                            } else {
                                coreInstance.inviteAddress(address)
                            }
                            secondaryLinphoneCall = newCall
                            _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP] 2nd Line Outgoing INVITE transmitted to $finalSipUri").takeLast(50)
                        } else {
                            _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Error] Failed to resolve address: $finalSipUri").takeLast(50)
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to invite secondary line: ${e.message}", e)
                        _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Error] Dialing 2nd line failed: ${e.message}").takeLast(50)
                    }
                } ?: run {
                    // Preview or simulated fallback
                    scope.launch {
                        delay(1500)
                        val cur = _callState.value
                        if (cur is CallState.Connected && cur.secondaryCall != null) {
                            _callState.value = cur.copy(secondaryCall = cur.secondaryCall.copy(isConnected = true))
                            _diagnosticLogs.value = (_diagnosticLogs.value + "[Line 2] Connected (Simulated). 2 calls active. Merge option ready.").takeLast(50)
                        }
                    }
                }
            }
        }
    }

    /**
     * 3-way call without SIP hold: the switch (iTelSwitchPlus) hangs up any call that stays
     * on hold (sendonly) for ~30s, even with hold music. So line 1 goes straight into a local
     * conference (stays sendrecv) and line 2 is invited into it; it joins the mixer on answer.
     * Returns false if the conference could not be set up, so the caller falls back to hold.
     */
    private fun startConferenceAndInvite(
        c: Core,
        current: CallState.Connected,
        finalSipUri: String,
        displayName: String
    ): Boolean {
        val first = primaryLinphoneCall ?: currentLinphoneCall ?: return false
        return try {
            val address = Factory.instance().createAddress(finalSipUri) ?: c.interpretUrl(finalSipUri) ?: return false

            var conf = c.conference
            if (conf == null) {
                val confParams = c.createConferenceParams(null)
                confParams.isAudioEnabled = true
                confParams.isVideoEnabled = false
                confParams.isLocalParticipantEnabled = true
                // Keep the conference alive while line 2 is still ringing (only line 1 inside)
                confParams.isOneParticipantConferenceEnabled = true
                conf = c.createConferenceWithParams(confParams)
            }
            if (conf == null) return false

            if (first.conference == null) {
                conf.addParticipant(first)
            }
            if (!conf.isIn) {
                conf.enter()
            }

            val callParams = c.createCallParams(null)
            callParams?.isVideoEnabled = false
            conf.inviteParticipants(arrayOf(address), callParams)

            try { c.isMicEnabled = !_isMuted.value } catch (_: Throwable) {}
            try { applyAudioRoute(c, _isSpeakerOn.value) } catch (_: Throwable) {}

            _isOnHold.value = false
            _callState.value = current.copy(
                isConference = true,
                isOnHold = false,
                secondaryCall = null,
                participants = listOf(
                    ConferenceParticipant(
                        id = "conf_p_1",
                        uri = current.remoteUri,
                        displayName = current.displayName.ifBlank { userPart(current.remoteUri) },
                        joinedAtSeconds = 0L
                    ),
                    ConferenceParticipant(
                        id = "conf_p_${System.currentTimeMillis()}",
                        uri = finalSipUri,
                        displayName = displayName,
                        joinedAtSeconds = _callDuration.value
                    )
                )
            )
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] Line 1 kept live in conference, inviting $displayName ($finalSipUri)").takeLast(50)
            startConferenceStatsLogger()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Start conference and invite: ${e.message}", e)
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference Error] ${e.message}. Falling back to hold + merge.").takeLast(50)
            false
        }
    }

    override fun mergeCallsIntoConference() {
        val current = _callState.value
        if (current is CallState.Connected && current.secondaryCall != null) {
            val sec = current.secondaryCall
            // A still-ringing call cannot join the mixer: it would end up outside the
            // conference once answered and nobody would hear anybody.
            if (!sec.isConnected) {
                _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] Line 2 has not answered yet. Merge after it connects.").takeLast(50)
                return
            }
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
                    // Do NOT resume held calls by hand first: resuming one call makes Linphone
                    // pause the other, and those competing re-INVITEs race with the merge and
                    // leave legs half-paused (choppy / one-way audio). addParticipants() takes
                    // care of un-holding each call inside the conference.
                    val calls = liveCalls(c).filter { it.conference == null }
                    Log.i(TAG, "Merging ${calls.size} calls into conference")

                    var conf = c.conference
                    if (conf == null) {
                        val confParams = c.createConferenceParams(null)
                        confParams.isAudioEnabled = true
                        confParams.isVideoEnabled = false
                        confParams.isLocalParticipantEnabled = true
                        conf = c.createConferenceWithParams(confParams)
                    }
                    if (conf == null) {
                        _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference Error] Could not create local conference").takeLast(50)
                        return
                    }

                    conf.addParticipants(calls.toTypedArray())
                    if (!conf.isIn) {
                        conf.enter()
                    }

                    // Keep the user's current mute / speaker choice for the mixer's local leg
                    try { c.isMicEnabled = !_isMuted.value } catch (_: Throwable) {}
                    try { applyAudioRoute(c, _isSpeakerOn.value) } catch (_: Throwable) {}

                    _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference Audio] ${calls.size} calls added to the mixer").takeLast(50)
                    startConferenceStatsLogger()
                } catch (e: Throwable) {
                    Log.e(TAG, "Linphone merge into conference: ${e.message}", e)
                    _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference Error] ${e.message}").takeLast(50)
                }
            }
        }
    }

    override fun removeParticipantFromConference(participantId: String) {
        val current = _callState.value
        if (current is CallState.Connected && current.isConference) {
            val removed = current.participants.firstOrNull { it.id == participantId }
            val updated = current.participants.filterNot { it.id == participantId }
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] Participant removed: $participantId").takeLast(50)

            // Actually drop that caller's SIP leg; otherwise they stay in the audio mix
            val c = core
            if (removed != null && c != null) {
                val target = liveCalls(c).firstOrNull {
                    userPart(it.remoteAddress?.asStringUriOnly().orEmpty()) == userPart(removed.uri)
                }
                try {
                    target?.terminate()
                } catch (e: Throwable) {
                    Log.w(TAG, "Terminate conference participant: ${e.message}")
                }
            }

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
                durationSeconds = current.durationSeconds,
                isConnected = true
            )
            _callState.value = current.copy(
                remoteUri = newPrimaryUri,
                displayName = newPrimaryName,
                secondaryCall = newSec,
                isOnHold = false
            )
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Call] Swapped active and held lines. Active: $newPrimaryName").takeLast(50)

            core?.let { c ->
                try {
                    for (call in c.calls) {
                        if (call.state == org.linphone.core.Call.State.Paused) {
                            call.resume()
                        } else if (call.state == org.linphone.core.Call.State.StreamsRunning) {
                            call.pause()
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Linphone swap error: ${e.message}")
                }
            }
        }
    }

    override fun hangupSecondaryCall() {
        val sec = secondaryLinphoneCall
        if (sec != null) {
            try {
                sec.terminate()
            } catch (e: Throwable) {
                Log.w(TAG, "Linphone terminate secondary call: ${e.message}")
            }
        }
        secondaryLinphoneCall = null
        primaryLinphoneCall?.resume()
        currentLinphoneCall = primaryLinphoneCall
        _isOnHold.value = false
        val current = _callState.value as? CallState.Connected
        if (current != null) {
            _callState.value = current.copy(secondaryCall = null, isOnHold = false)
        }
        _diagnosticLogs.value = (_diagnosticLogs.value + "[Call] Line 2 ended manually. Line 1 resumed.").takeLast(50)
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
        private val TERMINAL_STATES = setOf(Call.State.End, Call.State.Released, Call.State.Error)
    }
}
