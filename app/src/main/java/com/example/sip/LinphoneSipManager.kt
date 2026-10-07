package com.example.sip

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.example.data.model.AccountBalance
import com.example.data.repository.CallRecordings
import com.example.data.repository.SettingsRepository
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
import org.linphone.core.ChatMessage
import org.linphone.core.ChatMessageListenerStub
import org.linphone.core.ChatRoom
import org.linphone.core.Factory
import org.linphone.core.LogLevel
import org.linphone.core.ProxyConfig
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType

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
    private var callSessionActive = false
    private var callSessionIncoming = false
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

    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _callEvents = MutableSharedFlow<CallEvent>()
    override val callEvents: SharedFlow<CallEvent> = _callEvents.asSharedFlow()

    private val _incomingChatMessages = MutableSharedFlow<IncomingChatMessage>(extraBufferCapacity = 64)
    override val incomingChatMessages: SharedFlow<IncomingChatMessage> = _incomingChatMessages.asSharedFlow()

    override val ownNumber: String?
        get() = core?.defaultAccount?.params?.identityAddress?.username

    // Messages still waiting for a final state; the SDK drops listeners of unreferenced messages
    private val pendingChatMessages = mutableMapOf<ChatMessage, (Boolean) -> Unit>()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val dtmfTonePlayer = DtmfTonePlayer()

    private val coreListener: CoreListener = object : CoreListenerStub() {
        // A Bluetooth or wired headset was connected or removed: move the call audio to it (or
        // back to the phone), the way the built-in phone app does
        override fun onAudioDevicesListUpdated(core: Core) {
            if (_callState.value !is CallState.Connected) return
            Log.i(TAG, "Audio devices changed: " + core.audioDevices.joinToString { "${it.type}" })
            try { applyAudioRoute(core, _isSpeakerOn.value) } catch (e: Throwable) {
                Log.w(TAG, "Audio route: ${e.message}")
            }
        }

        override fun onMessageReceived(core: Core, chatRoom: ChatRoom, message: ChatMessage) {
            val text = message.utf8Text ?: return
            val fromAddress = message.fromAddress
            val headers = CHAT_HEADERS.mapNotNull { name ->
                message.getCustomHeader(name)?.takeIf { it.isNotEmpty() }?.let { name to it }
            }.toMap()
            Log.i(TAG, "Chat message from ${fromAddress.asStringUriOnly()} (${headers.keys})")
            _incomingChatMessages.tryEmit(
                IncomingChatMessage(
                    from = fromAddress.username ?: userPart(fromAddress.asStringUriOnly()),
                    fromDisplayName = fromAddress.displayName.orEmpty(),
                    text = text,
                    timestamp = message.time * 1000,
                    headers = headers
                )
            )
            chatRoom.markAsRead()
        }

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
            _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Auth] Provided credentials for realm=${authInfo.realm.orEmpty()}").takeLast(DIAGNOSTIC_LINES)
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
            _diagnosticLogs.value = (_diagnosticLogs.value + stateLog).takeLast(DIAGNOSTIC_LINES)

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
            // One plain line saying why a call failed (e.g. "404 Not Found"), easy to spot in
            // the diagnostics copy among the SIP messages
            if (state == Call.State.Error || state == Call.State.End) {
                val info = call.errorInfo
                val code = info?.protocolCode ?: 0
                if (state == Call.State.Error || code >= 300) {
                    _diagnosticLogs.value = (_diagnosticLogs.value +
                        "[Call Failed] ${call.remoteAddress?.asStringUriOnly()}: $code ${info?.phrase.orEmpty()} (${info?.reason}) $message"
                    ).takeLast(DIAGNOSTIC_LINES)
                }
            }
            updateRecording(core, call, state)
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
                        _diagnosticLogs.value = (_diagnosticLogs.value + "[Line 2] Calling: $remoteDisplayName").takeLast(DIAGNOSTIC_LINES)
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
                        _diagnosticLogs.value = (_diagnosticLogs.value + "[Line 2] Ringing: $remoteDisplayName").takeLast(DIAGNOSTIC_LINES)
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
                        _diagnosticLogs.value = (_diagnosticLogs.value + "[Line 2] Connected! 2 calls active. Merge option ready.").takeLast(DIAGNOSTIC_LINES)
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
                        _diagnosticLogs.value = (_diagnosticLogs.value + "[Line 2] Call ended ($message). Resuming Line 1.").takeLast(DIAGNOSTIC_LINES)
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
                    emitCallStarted(remoteAddress, remoteDisplayName, incoming = true)
                    scope.launch {
                        if (currentSettings.autoAnswer) {
                            val delayMs = (currentSettings.autoAnswerDelaySeconds.coerceAtLeast(1)) * 1000L
                            _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Auto-Answer] Answering in ${currentSettings.autoAnswerDelaySeconds}s").takeLast(DIAGNOSTIC_LINES)
                            delay(delayMs)
                            if (_callState.value is CallState.Incoming) {
                                acceptCall()
                            }
                        }
                    }
                }
                Call.State.OutgoingInit, Call.State.OutgoingProgress -> {
                    _callState.value = CallState.Outgoing(remoteAddress, remoteDisplayName, isEarlyMediaOrRinging = false)
                    emitCallStarted(remoteAddress, remoteDisplayName, incoming = false)
                }
                Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> {
                    _callState.value = CallState.Outgoing(remoteAddress, remoteDisplayName, isEarlyMediaOrRinging = true)
                }
                Call.State.Connected, Call.State.StreamsRunning -> {
                    // StreamsRunning repeats after every re-INVITE; don't reset the timer each time
                    if (_callState.value !is CallState.Connected) {
                        startDurationTimer()
                        // Choose the speaker and microphone ourselves instead of trusting each
                        // phone's default (see applyAudioRoute)
                        try { applyAudioRoute(core, _isSpeakerOn.value) } catch (e: Throwable) {
                            Log.w(TAG, "Audio route: ${e.message}")
                        }
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
                        _diagnosticLogs.value = (_diagnosticLogs.value + "[Call] Line 1 ended. Switching to Line 2.").takeLast(DIAGNOSTIC_LINES)
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

                    // Released after End (or End after hangupCall) belongs to a session that
                    // is already over; don't disconnect or log it a second time.
                    if (!callSessionActive) {
                        currentLinphoneCall = null
                        primaryLinphoneCall = null
                        secondaryLinphoneCall = null
                        return
                    }

                    val duration = _callDuration.value
                    val wasMissed = (_callState.value is CallState.Incoming)
                    stopDurationTimer()
                    try { core.isMicEnabled = true } catch (_: Throwable) {}
                    val failure = failureReason(call, state)
                    _callState.value = CallState.Disconnected(reason = failure ?: message.ifBlank { "Call Ended" })
                    currentLinphoneCall = null
                    primaryLinphoneCall = null
                    secondaryLinphoneCall = null
                    emitCallEnded(remoteAddress, remoteDisplayName, duration, wasMissed)

                    scope.launch {
                        refreshBalanceSoon()
                        // A failed call keeps its reason on screen long enough to read
                        delay(if (failure != null) 4000 else 1500)
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
                    _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] $remoteDisplayName answered, added to the mixer").takeLast(DIAGNOSTIC_LINES)
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
                _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] $remoteDisplayName left. Back to 1-on-1 call.").takeLast(DIAGNOSTIC_LINES)
                promoteRemainingCall(others.first())
            }
            else -> {
                val remaining = current.participants.filterNot { userPart(it.uri) == userPart(remoteAddress) }
                if (remaining.size != current.participants.size) {
                    _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] $remoteDisplayName left the conference").takeLast(DIAGNOSTIC_LINES)
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
                _diagnosticLogs.value = (_diagnosticLogs.value + header).takeLast(DIAGNOSTIC_LINES)
                for (call in liveCalls(c)) {
                    val pt = call.currentParams?.usedAudioPayloadType
                    val st = call.audioStats
                    val line = "[Conf Stats] ${userPart(call.remoteAddress?.asStringUriOnly().orEmpty())} " +
                        "state=${call.state} inConf=${call.conference != null} " +
                        "codec=${pt?.mimeType}/${pt?.clockRate} pt=${pt?.number} dir=${call.currentParams?.audioDirection} " +
                        "in=${"%.1f".format(st?.downloadBandwidth ?: 0f)}kbps out=${"%.1f".format(st?.uploadBandwidth ?: 0f)}kbps " +
                        "loss=${"%.1f".format(st?.receiverLossRate ?: 0f)}%"
                    Log.i(TAG, line)
                    _diagnosticLogs.value = (_diagnosticLogs.value + line).takeLast(DIAGNOSTIC_LINES)
                }
            }
        }
    }

    /**
     * One call session = one CallStarted and one CallEnded (= one history entry).
     * Linphone reports several terminal events per call (End, then Released), hangupCall()
     * ends the session itself, and a conference ends several legs; without this guard each
     * of those wrote its own history row. IncomingReceived/IncomingEarlyMedia and
     * OutgoingInit/OutgoingProgress likewise both used to report the start.
     */
    private fun emitCallStarted(uri: String, displayName: String, incoming: Boolean) {
        if (callSessionActive) return
        callSessionActive = true
        callSessionIncoming = incoming
        scope.launch { _callEvents.emit(CallEvent.CallStarted(uri, displayName, incoming)) }
    }

    private fun emitCallEnded(uri: String, displayName: String, durationSeconds: Long, wasMissed: Boolean) {
        if (!callSessionActive) return
        callSessionActive = false
        val incoming = callSessionIncoming
        scope.launch {
            _callEvents.emit(CallEvent.CallEnded(uri, displayName, durationSeconds, wasMissed, incoming))
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

    /**
     * Why an outgoing call did not go through, in words an agent understands, with the SIP code
     * for the admin ("Number not found (404)"); null when the call simply ended.
     */
    private fun failureReason(call: Call, state: Call.State): String? {
        val info = call.errorInfo
        val code = info?.protocolCode ?: 0
        if (code < 300 && state != Call.State.Error) return null
        val text = when (code) {
            0 -> "No answer from the server. Check the internet connection"
            403 -> "Call not allowed (check balance or number)"
            404, 484, 604 -> "Number not found"
            408 -> "No answer from the server. Check the internet connection"
            480 -> "Number not reachable"
            486, 600 -> "Busy"
            487 -> "Call cancelled"
            488, 606 -> "Call not accepted by the server"
            in 500..599 -> "Server error"
            else -> info?.phrase?.takeIf { it.isNotBlank() } ?: "Call failed"
        }
        return if (code > 0) "$text ($code)" else text
    }

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
        // Pick the microphone that belongs to the output: a Bluetooth or wired headset's own mic,
        // otherwise the phone's. Left to itself, some phones pick a capture device that records
        // silence (e.g. the "telephony" one), and the other side hears nothing.
        val recordable = c.audioDevices.filter { it.hasCapability(AudioDevice.Capabilities.CapabilityRecord) }
        val input = when {
            !speaker && device.type == AudioDevice.Type.Bluetooth ->
                recordable.firstOrNull { it.type == AudioDevice.Type.Bluetooth }
            !speaker && device.type == AudioDevice.Type.Headset ->
                recordable.firstOrNull { it.type == AudioDevice.Type.Headset }
            else -> null
        } ?: recordable.firstOrNull { it.type == AudioDevice.Type.Microphone }
        val conf = c.conference
        if (conf != null && conf.isIn) {
            conf.outputAudioDevice = device
            if (input != null) conf.inputAudioDevice = input
        } else {
            c.outputAudioDevice = device
            if (input != null) c.inputAudioDevice = input
        }
        Log.i(TAG, "Audio route: out=${device.type}/${device.deviceName} in=${input?.type}/${input?.deviceName}")
        _diagnosticLogs.value = (_diagnosticLogs.value + "[Audio] Route: ${device.type} / mic ${input?.type ?: "default"}").takeLast(DIAGNOSTIC_LINES)
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
                        // The screen's log keeps what explains a call or registration problem;
                        // the server's OPTIONS pings and network chatter (every few seconds)
                        // would push a failed call out of it within a minute
                        if (!isRoutineSdkLine(line)) {
                            _diagnosticLogs.value = (_diagnosticLogs.value + "[${level.name}] $line").takeLast(DIAGNOSTIC_LINES)
                        }
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
                // UDP keepalive every 20s: mobile-carrier NATs often forget an idle UDP mapping
                // after ~30s, and then the switch's INVITE never reaches the phone.
                config.setInt("sip", "keepalive_period", 20000)
                // Start IPv4-only (Linphone's default is IPv6 on); applyIpFamily turns IPv6 on
                // only where it can work (see there)
                config.setInt("sip", "use_ipv6", 0)
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

            try {
                newCore.isKeepAliveEnabled = true
                // A short network drop (lift, Wi-Fi to mobile data handover) must not end the call:
                // wait 60 s without voice packets before hanging up (default 30 s)
                newCore.nortpTimeout = 60
            } catch (_: Throwable) {}

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
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Linphone] Core started successfully").takeLast(DIAGNOSTIC_LINES)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize Linphone Core: ${e.message}", e)
            _registrationMessage.value = "Linphone core init: ${e.message}"
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Error] Core init: ${e.message}").takeLast(DIAGNOSTIC_LINES)
        }
    }

    override fun applySettings(settings: AppSettings) {
        currentSettings = settings
        val c = core ?: return
        try {
            // Phones with a built-in echo canceller (most since ~2018) already clean the mic signal.
            // Running Linphone's software one on top adds processing delay and can clip speech.
            val builtinAec = try { c.hasBuiltinEchoCanceller() } catch (_: Throwable) { false }
            c.isEchoCancellationEnabled = settings.echoCancellation && !builtinAec
            // Low-latency audio: start the jitter buffer at 40 ms instead of 60 ms; it still grows
            // by itself on a bad network (adaptive), and shrinks again when the network calms down.
            c.audioJittcomp = 40
            c.isAudioAdaptiveJittcompEnabled = true
            // Mark voice packets as real-time traffic (DSCP EF), so Wi-Fi routers send them first
            c.audioDscp = 0x2e
            c.isAdaptiveRateControlEnabled = settings.adaptiveRateControl
            applyIpFamily(c)
            c.micGainDb = if (settings.micGainBoost) 6.0f else 0.0f
            applyNatPolicy(c, settings)
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Audio] Echo canceller: " + (if (builtinAec) "phone's built-in" else if (settings.echoCancellation) "software" else "off") + ", jitter buffer 40 ms adaptive").takeLast(DIAGNOSTIC_LINES)
            Log.d(TAG, "Applied audio/network settings: AEC=${settings.echoCancellation}, builtinAEC=$builtinAec, AdaptiveRate=${settings.adaptiveRateControl}, IPv6=${settings.ipv6Enabled}")
        } catch (e: Throwable) {
            Log.w(TAG, "applySettings failed: ${e.message}")
        }
    }

    /**
     * IPv4 or IPv6 for SIP and voice. Bangladeshi mobile networks are now often IPv6-only (the
     * phone reaches IPv4 sites through the operator's NAT64 / 464XLAT). With IPv6 on, the app
     * then registered and was reachable, but put its IPv6 address in calls, which an IPv4-only
     * switch cannot answer or send voice to: calls failed on mobile data and worked on Wi-Fi.
     * A switch given as an IPv4 address therefore always gets IPv4 (Android's 464XLAT carries it
     * on IPv6-only networks); the IPv6 setting only applies to a switch with a host name.
     */
    private fun applyIpFamily(c: Core) {
        val host = activeAccountModel?.domain?.trim().orEmpty()
            .removePrefix("sip:").removePrefix("sips:").substringBefore(";").substringBefore("/")
            .let { if (it.startsWith("[")) it else it.substringBefore(":") }
        val ipv4Switch = IPV4_ADDRESS.matches(host)
        val ipv6 = currentSettings.ipv6Enabled && !ipv4Switch
        if (c.isIpv6Enabled != ipv6) {
            c.isIpv6Enabled = ipv6
            val why = if (ipv4Switch && currentSettings.ipv6Enabled) " (switch $host is IPv4)" else ""
            Log.i(TAG, "IPv6 ${if (ipv6) "on" else "off"}$why")
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Network] IPv6 ${if (ipv6) "on" else "off"}$why").takeLast(DIAGNOSTIC_LINES)
        }
    }

    /**
     * STUN: before a call the phone asks the STUN server for its public IP and port and puts that
     * in the SDP instead of the Wi-Fi address (e.g. 192.168.1.x), which the PBX cannot reach.
     * Setting only the server address is not enough: the policy's STUN switch must be on too.
     * ICE stays off: most PBXs (Asterisk without icesupport, iTelSwitch) ignore it and it only
     * delays the call setup.
     */
    private fun applyNatPolicy(c: Core, settings: AppSettings) {
        val server = settings.stunServer.trim().ifBlank { SettingsRepository.DEFAULT_STUN }
        val policy = c.natPolicy ?: c.createNatPolicy()
        policy.stunServer = server
        policy.isStunEnabled = settings.stunEnabled
        policy.isIceEnabled = false
        policy.isTurnEnabled = false
        policy.isUpnpEnabled = false
        c.natPolicy = policy
        c.stunServer = if (settings.stunEnabled) server else null
        val line = if (settings.stunEnabled) "[Network] STUN on ($server): calls use the public IP" else "[Network] STUN off: calls use the local IP"
        Log.i(TAG, line)
        _diagnosticLogs.value = (_diagnosticLogs.value + line).takeLast(DIAGNOSTIC_LINES)
    }

    override fun registerAccount(account: SipAccount) {
        initializeSdk()
        val c = core
        // Same account already registered (or registering): keep it. Re-creating it unregisters
        // first, and a call arriving in that gap is lost. The app start, the settings screen and
        // a saved edit all ask for registration, often for the same account.
        val previous = activeAccountModel
        if (c != null && previous != null && sameLogin(previous, account) && c.accountList.isNotEmpty() &&
            (_registrationState.value == RegistrationStatus.REGISTERED || _registrationState.value == RegistrationStatus.REGISTERING)
        ) {
            activeAccountModel = account
            c.ensureRegistered()
            return
        }
        activeAccountModel = account
        // Before the account is created, so REGISTER and calls use the right address family
        core?.let { applyIpFamily(it) }
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
            _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP] Registering $cleanUsername@$host:$port (${account.transport.name})").takeLast(DIAGNOSTIC_LINES)

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
            // No push parameters in the Contact: neither PBX sends FCM pushes, and the long token
            // (~250 bytes) made every INVITE to the phone bigger. Over mobile data a UDP packet above
            // ~1300 bytes gets fragmented, and lost fragments = an incoming call that never rings.
            try {
                accountParams.pushNotificationAllowed = false
                accountParams.remotePushNotificationAllowed = false
            } catch (_: Throwable) {}
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
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Error] Config error: ${e.message}").takeLast(DIAGNOSTIC_LINES)
        }
    }

    private fun sameLogin(a: SipAccount, b: SipAccount) =
        a.username.trim() == b.username.trim() && a.password == b.password && a.domain.trim() == b.domain.trim() &&
            a.port == b.port && a.transport == b.transport && a.displayName == b.displayName

    private fun simulateRegistration(account: SipAccount) {
        scope.launch {
            _registrationState.value = RegistrationStatus.REGISTERING
            _registrationMessage.value = "Connecting to ${account.domain}..."
            delay(1000)
            _registrationState.value = RegistrationStatus.REGISTERED
            _registrationMessage.value = "Registered (${account.transport.name} / Port ${account.port})"
        }
    }

    /**
     * Records a call whose params carry a record file (set when dialing or answering with
     * "Record calls" on): starts once audio flows, stops when the call ends.
     */
    private fun updateRecording(core: Core, call: Call, state: Call.State) {
        try {
            when (state) {
                Call.State.StreamsRunning -> {
                    if (call.params.recordFile != null && !call.isRecording) {
                        call.startRecording()
                        Log.i(TAG, "Recording call to ${call.params.recordFile}")
                    }
                }
                Call.State.End, Call.State.Error, Call.State.Released -> {
                    if (call.isRecording) call.stopRecording()
                    CallRecordings.cleanUpEmpty(context)
                }
                else -> {}
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Call recording: ${e.message}")
        }
        _isRecording.value = core.calls.any { runCatching { it.isRecording }.getOrDefault(false) }
    }

    override fun sendChatMessage(
        to: String,
        text: String,
        headers: Map<String, String>,
        onResult: (Boolean) -> Unit
    ) {
        scope.launch(Dispatchers.Main) {
            val c = core
            val domain = c?.defaultAccount?.params?.identityAddress?.domain
            if (c == null || domain == null || _registrationState.value != RegistrationStatus.REGISTERED) {
                onResult(false)
                return@launch
            }
            val uri = if (to.startsWith("sip:") || to.contains("@")) {
                if (to.startsWith("sip:")) to else "sip:$to"
            } else {
                "sip:$to@$domain"
            }
            val peer = Factory.instance().createAddress(uri)
            val room = peer?.let { c.getChatRoom(it) }
            if (room == null) {
                Log.w(TAG, "Chat: cannot open chat room for $uri")
                onResult(false)
                return@launch
            }
            val message = room.createMessageFromUtf8(text)
            headers.forEach { (name, value) -> message.addCustomHeader(name, value) }
            pendingChatMessages[message] = onResult
            message.addListener(object : ChatMessageListenerStub() {
                override fun onMsgStateChanged(msg: ChatMessage, state: ChatMessage.State) {
                    val delivered = state == ChatMessage.State.Delivered ||
                        state == ChatMessage.State.DeliveredToUser ||
                        state == ChatMessage.State.Displayed
                    if (delivered || state == ChatMessage.State.NotDelivered) {
                        Log.i(TAG, "Chat to $uri: $state")
                        pendingChatMessages.remove(msg)?.invoke(delivered)
                    }
                }
            })
            message.send()
        }
    }

    override fun onPushWakeup() {
        scope.launch(Dispatchers.Main) {
            initializeSdk()
            val c = core ?: return@launch
            val account = activeAccountModel
            if (c.accountList.isEmpty() && account != null) {
                registerAccount(account)
            } else {
                // Re-REGISTERs only if the registration is not currently OK
                c.ensureRegistered()
            }
            _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP] Checking registration").takeLast(DIAGNOSTIC_LINES)
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
        val numberOrUser = cleanDest.removePrefix("sip:").removePrefix("sips:").trim().let { dest ->
            // +8801XXXXXXXXX -> 01XXXXXXXXX (see toDialableNumber); "@domain" stays as typed
            val user = dest.substringBefore("@")
            toDialableNumber(user) + dest.substring(user.length)
        }

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
                _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Call] Failed to parse URI: $finalSipUri").takeLast(DIAGNOSTIC_LINES)
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
                if (currentSettings.recordCalls) {
                    callParams.recordFile = CallRecordings.newFile(context, finalSipUri, incoming = false).absolutePath
                }
                try {
                    callParams.isEarlyMediaSendingEnabled = true
                } catch (_: Throwable) {}
            }

            val dialLog = "[SIP Call] Dialing: ${address.asStringUriOnly()} via ${account?.params?.identityAddress?.asStringUriOnly() ?: "default transport"}"
            Log.i(TAG, dialLog)
            _diagnosticLogs.value = (_diagnosticLogs.value + dialLog).takeLast(DIAGNOSTIC_LINES)

            val call = if (callParams != null) {
                c.inviteAddressWithParams(address, callParams)
            } else {
                c.inviteAddress(address) ?: c.invite(finalSipUri)
            }

            if (call == null) {
                Log.w(TAG, "inviteAddressWithParams returned null for $finalSipUri, fallback simulated")
                _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Call] Linphone invite returned null for $finalSipUri").takeLast(DIAGNOSTIC_LINES)
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
            _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Call Error] ${e.message}").takeLast(DIAGNOSTIC_LINES)
            simulateOutgoingCall(finalSipUri, effectiveDisplayName)
        }
    }

    override fun acceptCall() {
        val call = currentLinphoneCall
        if (call != null) {
            try {
                val params = if (currentSettings.recordCalls) {
                    core?.createCallParams(call)?.apply {
                        recordFile = CallRecordings.newFile(
                            context, call.remoteAddress?.asStringUriOnly().orEmpty(), incoming = true
                        ).absolutePath
                    }
                } else {
                    null
                }
                if (params != null) call.acceptWithParams(params) else call.accept()
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
        emitCallEnded(remoteUri, dispName, duration, wasMissed)

        scope.launch {
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
        emitCallStarted(callerUri, callerName, incoming = true)
    }

    override fun simulateAutoAnswer() {
        acceptCall()
    }

    private fun simulateOutgoingCall(uri: String, displayName: String) {
        scope.launch {
            _callState.value = CallState.Outgoing(uri, displayName, isEarlyMediaOrRinging = false)
            emitCallStarted(uri, displayName, incoming = false)
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
        val numberOrUser = cleanDest.removePrefix("sip:").removePrefix("sips:").trim().let { dest ->
            // +8801XXXXXXXXX -> 01XXXXXXXXX (see toDialableNumber); "@domain" stays as typed
            val user = dest.substringBefore("@")
            toDialableNumber(user) + dest.substring(user.length)
        }

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
                _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] Inviting participant: $effectiveDisplayName ($finalSipUri)").takeLast(DIAGNOSTIC_LINES)

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
                            _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP] Conference INVITE sent to $finalSipUri").takeLast(DIAGNOSTIC_LINES)
                        } else {
                            _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Error] Invalid conference URI: $finalSipUri").takeLast(DIAGNOSTIC_LINES)
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Linphone add participant error: ${e.message}")
                        _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Error] Conference invite error: ${e.message}").takeLast(DIAGNOSTIC_LINES)
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
                _diagnosticLogs.value = (_diagnosticLogs.value + "[Call] Line 1 placed on hold. Dialing Line 2: $effectiveDisplayName ($finalSipUri)").takeLast(DIAGNOSTIC_LINES)

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
                            _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP] 2nd Line Outgoing INVITE transmitted to $finalSipUri").takeLast(DIAGNOSTIC_LINES)
                        } else {
                            _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Error] Failed to resolve address: $finalSipUri").takeLast(DIAGNOSTIC_LINES)
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to invite secondary line: ${e.message}", e)
                        _diagnosticLogs.value = (_diagnosticLogs.value + "[SIP Error] Dialing 2nd line failed: ${e.message}").takeLast(DIAGNOSTIC_LINES)
                    }
                } ?: run {
                    // Preview or simulated fallback
                    scope.launch {
                        delay(1500)
                        val cur = _callState.value
                        if (cur is CallState.Connected && cur.secondaryCall != null) {
                            _callState.value = cur.copy(secondaryCall = cur.secondaryCall?.copy(isConnected = true))
                            _diagnosticLogs.value = (_diagnosticLogs.value + "[Line 2] Connected (Simulated). 2 calls active. Merge option ready.").takeLast(DIAGNOSTIC_LINES)
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
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] Line 1 kept live in conference, inviting $displayName ($finalSipUri)").takeLast(DIAGNOSTIC_LINES)
            startConferenceStatsLogger()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Start conference and invite: ${e.message}", e)
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference Error] ${e.message}. Falling back to hold + merge.").takeLast(DIAGNOSTIC_LINES)
            false
        }
    }

    override fun mergeCallsIntoConference() {
        val current = _callState.value
        if (current is CallState.Connected && current.secondaryCall != null) {
            val sec = current.secondaryCall ?: return
            // A still-ringing call cannot join the mixer: it would end up outside the
            // conference once answered and nobody would hear anybody.
            if (!sec.isConnected) {
                _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] Line 2 has not answered yet. Merge after it connects.").takeLast(DIAGNOSTIC_LINES)
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
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] Merged calls into multi-party conference session (2 participants)").takeLast(DIAGNOSTIC_LINES)

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
                        _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference Error] Could not create local conference").takeLast(DIAGNOSTIC_LINES)
                        return
                    }

                    conf.addParticipants(calls.toTypedArray())
                    if (!conf.isIn) {
                        conf.enter()
                    }

                    // Keep the user's current mute / speaker choice for the mixer's local leg
                    try { c.isMicEnabled = !_isMuted.value } catch (_: Throwable) {}
                    try { applyAudioRoute(c, _isSpeakerOn.value) } catch (_: Throwable) {}

                    _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference Audio] ${calls.size} calls added to the mixer").takeLast(DIAGNOSTIC_LINES)
                    startConferenceStatsLogger()
                } catch (e: Throwable) {
                    Log.e(TAG, "Linphone merge into conference: ${e.message}", e)
                    _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference Error] ${e.message}").takeLast(DIAGNOSTIC_LINES)
                }
            }
        }
    }

    override fun removeParticipantFromConference(participantId: String) {
        val current = _callState.value
        if (current is CallState.Connected && current.isConference) {
            val removed = current.participants.firstOrNull { it.id == participantId }
            val updated = current.participants.filterNot { it.id == participantId }
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] Participant removed: $participantId").takeLast(DIAGNOSTIC_LINES)

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
                    _diagnosticLogs.value = (_diagnosticLogs.value + "[Conference] Reverted to 1-on-1 call with ${remaining.displayName}").takeLast(DIAGNOSTIC_LINES)
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
            val sec = current.secondaryCall ?: return
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
            _diagnosticLogs.value = (_diagnosticLogs.value + "[Call] Swapped active and held lines. Active: $newPrimaryName").takeLast(DIAGNOSTIC_LINES)

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
        _diagnosticLogs.value = (_diagnosticLogs.value + "[Call] Line 2 ended manually. Line 1 resumed.").takeLast(DIAGNOSTIC_LINES)
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
                if (_callDuration.value % 10 == 3L) logCallQuality()
                val current = _callState.value
                if (current is CallState.Connected) {
                    _callState.value = current.copy(durationSeconds = _callDuration.value)
                }
            }
        }
    }

    /**
     * Writes the call's network numbers to Diagnostics (every 10 s): round trip time, jitter
     * buffer size and loss. Mouth-to-ear delay is roughly RTT/2 + jitter buffer + ~60 ms audio.
     * High RTT = slow network or far server; big jitter buffer = unstable network.
     */
    private fun logCallQuality() {
        val call = currentLinphoneCall ?: return
        try {
            val st = call.audioStats ?: return
            val rttMs = (st.roundTripDelay * 1000).toInt()
            val line = "[Call Quality] RTT ${rttMs} ms, jitter buffer ${st.jitterBufferSizeMs.toInt()} ms, " +
                "jitter ${(st.receiverInterarrivalJitter * 1000).toInt()} ms, loss ${"%.1f".format(st.receiverLossRate)}%, " +
                "late ${"%.1f".format(st.localLateRate)}%, codec ${call.currentParams?.usedAudioPayloadType?.mimeType}" +
                when {
                    rttMs > 400 -> " -> slow network (check Wi-Fi/mobile data or server distance)"
                    st.jitterBufferSizeMs > 150 -> " -> unstable network"
                    else -> ""
                }
            Log.i(TAG, line)
            _diagnosticLogs.value = (_diagnosticLogs.value + line).takeLast(DIAGNOSTIC_LINES)
        } catch (e: Throwable) {
            Log.w(TAG, "Call quality: ${e.message}")
        }
    }

    private fun stopDurationTimer() {
        durationTimerJob?.cancel()
        durationTimerJob = null
    }

    companion object {
        private const val TAG = "LinphoneSipManager"
        private val IPV4_ADDRESS = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /** Lines kept in Settings > Connection Diagnostics (enough for a whole call) */
        private const val DIAGNOSTIC_LINES = 300

        /** SDK log lines that repeat all the time and say nothing about a problem */
        // Only OPTIONS transactions: an INVITE also lists OPTIONS in its Allow header
        // (and the voicemail NOTIFYs the switch sends after each registration)
        private val OPTIONS_CSEQ = Regex("CSeq:\\s*\\d+\\s+OPTIONS|Event: message-summary")

        private fun isRoutineSdkLine(line: String): Boolean =
            OPTIONS_CSEQ.containsMatchIn(line) ||
                line.startsWith("bellesip_wake_lock") ||
                line.contains("recv background task") ||
                line.contains("bytes parsed") ||
                line.contains("keep alive sent") ||
                line.startsWith("[Platform Helper]") ||
                line.startsWith("[Android Platform Helper] Found DNS")
        private val CHAT_HEADERS = listOf(ChatHeaders.GROUP_ID, ChatHeaders.GROUP_NAME, ChatHeaders.GROUP_MEMBERS)
        private val TERMINAL_STATES = setOf(Call.State.End, Call.State.Released, Call.State.Error)
    }
}
