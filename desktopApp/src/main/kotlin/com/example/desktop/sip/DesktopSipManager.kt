package com.example.desktop.sip

import com.example.data.model.AccountBalance
import com.example.data.model.AppSettings
import com.example.data.model.CallState
import com.example.data.model.ConferenceParticipant
import com.example.data.model.RegistrationStatus
import com.example.data.model.SecondaryCallInfo
import com.example.data.model.SipAccount
import com.example.data.model.SipTransport
import com.example.data.repository.CallRecordings
import com.example.data.repository.SettingsRepository
import com.example.desktop.sip.LinphoneNative.Companion.CALL_CONNECTED
import com.example.desktop.sip.LinphoneNative.Companion.CALL_END
import com.example.desktop.sip.LinphoneNative.Companion.CALL_ERROR
import com.example.desktop.sip.LinphoneNative.Companion.CALL_INCOMING_EARLY_MEDIA
import com.example.desktop.sip.LinphoneNative.Companion.CALL_INCOMING_RECEIVED
import com.example.desktop.sip.LinphoneNative.Companion.CALL_OUTGOING_EARLY_MEDIA
import com.example.desktop.sip.LinphoneNative.Companion.CALL_OUTGOING_INIT
import com.example.desktop.sip.LinphoneNative.Companion.CALL_OUTGOING_PROGRESS
import com.example.desktop.sip.LinphoneNative.Companion.CALL_OUTGOING_RINGING
import com.example.desktop.sip.LinphoneNative.Companion.CALL_PAUSED
import com.example.desktop.sip.LinphoneNative.Companion.CALL_PAUSING
import com.example.desktop.sip.LinphoneNative.Companion.CALL_PUSH_INCOMING_RECEIVED
import com.example.desktop.sip.LinphoneNative.Companion.CALL_RELEASED
import com.example.desktop.sip.LinphoneNative.Companion.CALL_RESUMING
import com.example.desktop.sip.LinphoneNative.Companion.CALL_STREAMS_RUNNING
import com.example.desktop.sip.LinphoneNative.Companion.FALSE
import com.example.desktop.sip.LinphoneNative.Companion.TRUE
import com.example.desktop.sip.LinphoneNative.Companion.bool
import com.example.sip.CallEvent
import com.example.sip.ChatHeaders
import com.example.sip.IncomingChatMessage
import com.example.sip.SipManager
import com.example.sip.toDialableNumber
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/**
 * SipManager for Windows on top of liblinphone (C API through JNA).
 *
 * liblinphone is not thread-safe: one thread ("linphone") owns the core, iterates it every 20 ms
 * and runs every call into it. Callbacks arrive on that thread during iterate.
 * Same server workarounds as on Android: only G.711 is offered (iTelSwitchPlus echoes the whole
 * SDP offer on re-INVITE), hold music keeps RTP flowing, and the balance comes from the
 * "iTelSwitchPlus" header of the REGISTER 200 OK.
 */
class DesktopSipManager : SipManager {

    private val linphoneThread = Executors.newSingleThreadExecutor { Thread(it, "linphone").apply { isDaemon = true } }
    private val linphoneDispatcher = linphoneThread.asCoroutineDispatcher()
    /** Everything that touches the core runs here. */
    private val sip = CoroutineScope(SupervisorJob() + linphoneDispatcher)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var lib: LinphoneLibrary? = null
    private val native get() = lib!!.linphone
    private var factory: Pointer? = null
    private var core: Pointer? = null
    private var iterateJob: Job? = null

    private var activeAccountModel: SipAccount? = null
    private var currentSettings = AppSettings()
    private var primaryCall: Pointer? = null
    private var secondaryCall: Pointer? = null
    private var callSessionActive = false
    private var callSessionIncoming = false
    private var durationJob: Job? = null

    // Chat messages waiting for a final state (ref'd so the SDK keeps them alive)
    private val pendingChat = mutableMapOf<Pointer, (Boolean) -> Unit>()

    private val _registrationState = MutableStateFlow(RegistrationStatus.UNREGISTERED)
    override val registrationState: StateFlow<RegistrationStatus> = _registrationState.asStateFlow()
    private val _registrationMessage = MutableStateFlow("Ready to configure account")
    override val registrationMessage: StateFlow<String> = _registrationMessage.asStateFlow()
    private val _accountBalance = MutableStateFlow<AccountBalance?>(null)
    override val accountBalance: StateFlow<AccountBalance?> = _accountBalance.asStateFlow()
    private val _diagnosticLogs = MutableStateFlow<List<String>>(emptyList())
    override val diagnosticLogs: StateFlow<List<String>> = _diagnosticLogs.asStateFlow()
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
    private val _callEvents = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)
    override val callEvents: SharedFlow<CallEvent> = _callEvents.asSharedFlow()
    private val _incomingChatMessages = MutableSharedFlow<IncomingChatMessage>(extraBufferCapacity = 64)
    override val incomingChatMessages: SharedFlow<IncomingChatMessage> = _incomingChatMessages.asSharedFlow()

    @Volatile
    private var registeredNumber: String? = null
    override val ownNumber: String? get() = registeredNumber

    override fun clearLogs() {
        _diagnosticLogs.value = emptyList()
    }

    override fun addDiagnosticLog(log: String) {
        _diagnosticLogs.value = (_diagnosticLogs.value + log).takeLast(60)
    }

    private fun log(line: String) {
        println("[SIP] $line")
        addDiagnosticLog(line)
    }

    // ---- Callbacks (strong references, they must outlive the core) ----

    private val registrationCb = LinphoneNative.AccountRegistrationStateChangedCb { _, account, state, message ->
        onRegistration(account, state, message.orEmpty())
    }

    private val callStateCb = LinphoneNative.CallStateChangedCb { c, call, state, message ->
        try {
            onCallState(c, call, state, message.orEmpty())
        } catch (e: Throwable) {
            log("[Error] Call state handler: ${e.message}")
        }
    }

    private val messageReceivedCb = LinphoneNative.MessageReceivedCb { _, room, message ->
        val n = native
        val text = n.linphone_chat_message_get_utf8_text(message) ?: return@MessageReceivedCb
        val from = n.linphone_chat_message_get_from_address(message)
        val headers = listOf(ChatHeaders.GROUP_ID, ChatHeaders.GROUP_NAME, ChatHeaders.GROUP_MEMBERS).mapNotNull { name ->
            n.linphone_chat_message_get_custom_header(message, name)?.takeIf { it.isNotEmpty() }?.let { name to it }
        }.toMap()
        val fromUri = from?.let { lib!!.takeString(n.linphone_address_as_string_uri_only(it)) }.orEmpty()
        _incomingChatMessages.tryEmit(
            IncomingChatMessage(
                from = from?.let { n.linphone_address_get_username(it) } ?: userPart(fromUri),
                fromDisplayName = from?.let { n.linphone_address_get_display_name(it) }.orEmpty(),
                text = text,
                timestamp = n.linphone_chat_message_get_time(message) * 1000,
                headers = headers
            )
        )
        n.linphone_chat_room_mark_as_read(room)
    }

    private val messageStateCb = LinphoneNative.MessageStateChangedCb { message, state ->
        val delivered = state == LinphoneNative.MSG_DELIVERED || state == LinphoneNative.MSG_DELIVERED_TO_USER ||
            state == LinphoneNative.MSG_DISPLAYED
        if (delivered || state == LinphoneNative.MSG_NOT_DELIVERED) {
            pendingChat.remove(message)?.let { onResult ->
                onResult(delivered)
                native.linphone_chat_message_unref(message)
            }
        }
    }

    // ---- Core lifecycle ----

    override fun initializeSdk() {
        sip.launch { startCore() }
    }

    private fun startCore() {
        if (core != null) return
        try {
            val library = LinphoneLibrary(LinphoneLibrary.locate())
            lib = library
            val n = library.linphone
            n.linphone_logging_service_set_log_level(n.linphone_logging_service_get(), LinphoneNative.LOG_WARNING)

            val f = n.linphone_factory_get()
            factory = f
            n.linphone_factory_set_top_resources_dir(f, library.shareDir.absolutePath)
            n.linphone_factory_set_msplugins_dir(f, library.pluginDir.absolutePath)

            // Settings live in %APPDATA%\Dialer\linphonerc (Linphone's own state, not our accounts)
            val configFile = File(appDataDir(), "linphonerc")
            val config = n.linphone_factory_create_config(f, configFile.absolutePath)
            n.linphone_config_set_int(config, "sip", "guess_hostname", 0)
            n.linphone_config_set_int(config, "sip", "enable_enum_lookup", 0)
            n.linphone_config_set_int(config, "sip", "inc_timeout", 60)
            n.linphone_config_set_int(config, "sip", "in_call_timeout", 0)
            n.linphone_config_set_int(config, "sip", "keepalive_period", 20000)
            // Survive a short network drop: wait 60 s without voice packets before ending the call
            n.linphone_config_set_int(config, "rtp", "nortp_timeout", 60)
            // Low latency: 40 ms starting jitter buffer (adaptive), voice packets marked DSCP EF
            n.linphone_config_set_int(config, "rtp", "audio_jitt_comp", 40)
            n.linphone_config_set_int(config, "rtp", "audio_adaptive_jitt_comp_enabled", 1)
            n.linphone_config_set_int(config, "rtp", "audio_dscp", 0x2e)
            // Random local ports, like on Android (-1 = random; 0 would switch the transport off)
            n.linphone_config_set_int(config, "sip", "sip_port", -1)
            n.linphone_config_set_int(config, "sip", "sip_tcp_port", -1)
            n.linphone_config_set_int(config, "sip", "sip_tls_port", -1)
            n.linphone_config_set_int(config, "video", "enabled", 0)
            n.linphone_config_set_int(config, "video", "capture", 0)
            n.linphone_config_set_int(config, "video", "display", 0)
            n.linphone_config_set_int(config, "video", "automatically_initiate", 0)
            n.linphone_config_set_int(config, "video", "automatically_accept", 0)
            val sounds = File(library.shareDir, "sounds/linphone")
            n.linphone_config_set_string(config, "sound", "local_ring", File(sounds, "rings/oldphone-mono.wav").absolutePath)
            n.linphone_config_set_string(config, "sound", "remote_ring", File(sounds, "ringback.wav").absolutePath)

            val c = n.linphone_factory_create_core_with_config_3(f, config, null)
                ?: error("linphone_factory_create_core_with_config_3 returned null")

            val cbs = n.linphone_factory_create_core_cbs(f)
            n.linphone_core_cbs_set_account_registration_state_changed(cbs, registrationCb)
            n.linphone_core_cbs_set_call_state_changed(cbs, callStateCb)
            n.linphone_core_cbs_set_message_received(cbs, messageReceivedCb)
            n.linphone_core_add_callbacks(c, cbs)

            n.linphone_core_set_user_agent(c, "Dialer-Windows", "1.0")
            n.linphone_core_enable_video_capture(c, FALSE)
            n.linphone_core_enable_video_display(c, FALSE)
            n.linphone_core_enable_keep_alive(c, TRUE)

            // Offer only G.711 (+ DTMF events); see the class comment
            val enabled = mutableListOf<String>()
            for (pt in library.listItems(n.linphone_core_get_audio_payload_types(c))) {
                val mime = n.linphone_payload_type_get_mime_type(pt).orEmpty()
                val keep = (mime.equals("PCMU", true) || mime.equals("PCMA", true) || mime.equals("telephone-event", true)) &&
                    n.linphone_payload_type_get_clock_rate(pt) == 8000
                n.linphone_payload_type_enable(pt, bool(keep))
                if (keep) enabled += mime
            }

            n.linphone_core_set_ring(c, File(sounds, "rings/oldphone-mono.wav").absolutePath)
            n.linphone_core_set_ringback(c, File(sounds, "ringback.wav").absolutePath)
            // Hold music keeps RTP flowing, or the switch drops a held call after ~30 s
            n.linphone_core_set_play_file(c, File(sounds, "toy-mono.wav").absolutePath)

            check(n.linphone_core_start(c) == 0) { "linphone_core_start failed" }
            n.linphone_core_set_network_reachable(c, TRUE)
            core = c
            applySettingsNow(currentSettings)

            iterateJob = sip.launch {
                while (isActive) {
                    n.linphone_core_iterate(c)
                    delay(20)
                }
            }
            log("[Linphone] Core started (codecs: ${enabled.joinToString()})")
        } catch (e: Throwable) {
            log("[Error] Linphone could not start: ${e.message}")
            _registrationState.value = RegistrationStatus.FAILED
            _registrationMessage.value = "SIP engine could not start: ${e.message}"
        }
    }

    /** Stops the core cleanly (unregisters, ends calls). Called when the app quits. */
    fun shutdown() {
        runCatching {
            sip.launch {
                core?.let { c ->
                    native.linphone_core_terminate_all_calls(c)
                    iterateJob?.cancel()
                    native.linphone_core_stop(c)
                }
                core = null
            }.let { job -> kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(3000) { job.join() } } }
        }
    }

    override fun applySettings(settings: AppSettings) {
        currentSettings = settings
        sip.launch { applySettingsNow(settings) }
    }

    private fun applySettingsNow(settings: AppSettings) {
        val c = core ?: return
        val n = native
        n.linphone_core_enable_echo_cancellation(c, bool(settings.echoCancellation))
        n.linphone_core_enable_adaptive_rate_control(c, bool(settings.adaptiveRateControl))
        n.linphone_core_enable_ipv6(c, bool(settings.ipv6Enabled))
        n.linphone_core_set_mic_gain_db(c, if (settings.micGainBoost) 6f else 0f)
        // STUN puts the public IP in the SDP (see LinphoneSipManager.applyNatPolicy on Android)
        val stunServer = settings.stunServer.trim().ifBlank { SettingsRepository.DEFAULT_STUN }
        val policy = n.linphone_core_get_nat_policy(c) ?: n.linphone_core_create_nat_policy(c)
        n.linphone_nat_policy_set_stun_server(policy, stunServer)
        n.linphone_nat_policy_enable_stun(policy, bool(settings.stunEnabled))
        n.linphone_nat_policy_enable_ice(policy, FALSE)
        n.linphone_core_set_nat_policy(c, policy)
        n.linphone_core_set_stun_server(c, if (settings.stunEnabled) stunServer else null)
        log(if (settings.stunEnabled) "[Network] STUN on ($stunServer): calls use the public IP" else "[Network] STUN off")
    }

    // ---- Registration ----

    override fun registerAccount(account: SipAccount) {
        val previous = activeAccountModel
        activeAccountModel = account
        sip.launch {
            startCore()
            val c = core ?: return@launch
            // Same login already registered: keep it (re-creating it unregisters first, and a
            // call arriving in that gap is lost)
            if (previous != null && previous.username.trim() == account.username.trim() &&
                previous.password == account.password && previous.domain.trim() == account.domain.trim() &&
                previous.port == account.port && previous.transport == account.transport &&
                previous.displayName == account.displayName && native.linphone_core_get_default_account(c) != null &&
                _registrationState.value in setOf(RegistrationStatus.REGISTERED, RegistrationStatus.REGISTERING)
            ) {
                native.linphone_core_ensure_registered(c)
                return@launch
            }
            val n = native
            val f = factory!!
            val host = cleanHost(account.domain)
            val port = account.domain.substringAfterLast(':', "").toIntOrNull()?.takeIf { account.domain.contains(':') } ?: account.port
            val user = account.username.trim().removePrefix("sip:").substringBefore("@").trim()

            _registrationState.value = RegistrationStatus.REGISTERING
            _registrationMessage.value = "Registering $user@$host..."
            log("[SIP] Registering $user@$host:$port (${account.transport.name})")

            n.linphone_core_clear_accounts(c)
            n.linphone_core_clear_all_auth_info(c)
            // Any realm: Asterisk/FreePBX challenge with their own realm (e.g. "asterisk")
            n.linphone_core_add_auth_info(c, n.linphone_factory_create_auth_info_2(f, user, user, account.password, null, null, null, null))
            n.linphone_core_add_auth_info(c, n.linphone_factory_create_auth_info_2(f, user, null, account.password, null, null, host, null))

            val params = n.linphone_core_create_account_params(c)
            val identity = n.linphone_factory_create_address(f, "sip:$user@$host")
            if (identity == null) {
                _registrationState.value = RegistrationStatus.FAILED
                _registrationMessage.value = "Invalid username or server"
                return@launch
            }
            n.linphone_address_set_display_name(identity, account.displayName.ifBlank { user })
            n.linphone_account_params_set_identity_address(params, identity)
            val transport = when (account.transport) {
                SipTransport.UDP -> "udp"
                SipTransport.TCP -> "tcp"
                SipTransport.TLS -> "tls"
            }
            val server = n.linphone_factory_create_address(f, "sip:$host:$port;transport=$transport")
            if (server == null) {
                _registrationState.value = RegistrationStatus.FAILED
                _registrationMessage.value = "Invalid server address"
                return@launch
            }
            n.linphone_account_params_set_server_address(params, server)
            n.linphone_account_params_set_register_enabled(params, TRUE)
            n.linphone_account_params_set_expires(params, 300)
            val linphoneAccount = n.linphone_core_create_account(c, params)
            n.linphone_core_add_account(c, linphoneAccount)
            n.linphone_core_set_default_account(c, linphoneAccount)
            registeredNumber = null
        }
    }

    private fun onRegistration(account: Pointer, state: Int, message: String) {
        val n = native
        val info = n.linphone_account_get_error_info(account)
        val code = info?.let { n.linphone_error_info_get_protocol_code(it) } ?: 0
        val phrase = info?.let { n.linphone_error_info_get_phrase(it) }.orEmpty().trim()
        log("[SIP Reg] State: ${regName(state)}" + if (code > 0) ", Code: $code $phrase" else " ($message)")
        when (state) {
            LinphoneNative.REG_OK -> {
                _registrationState.value = RegistrationStatus.REGISTERED
                _registrationMessage.value = "Registered successfully"
                registeredNumber = activeAccountModel?.username?.trim()?.removePrefix("sip:")?.substringBefore("@")
                AccountBalance.parse(n.linphone_account_get_custom_header(account, "iTelSwitchPlus"))?.let {
                    _accountBalance.value = it
                }
            }
            LinphoneNative.REG_PROGRESS, LinphoneNative.REG_REFRESHING -> {
                _registrationState.value = RegistrationStatus.REGISTERING
                _registrationMessage.value = "Registering with SIP server..."
            }
            LinphoneNative.REG_FAILED -> {
                _registrationState.value = RegistrationStatus.FAILED
                registeredNumber = null
                _registrationMessage.value = when (code) {
                    401, 407 -> "401 Unauthorized: Server challenged credentials. Check password, auth username, or realm"
                    403 -> "403 Forbidden: Incorrect credentials or IP not allowed by SIP server"
                    404 -> "404 Not Found: Extension/user does not exist on this SIP server"
                    408 -> "408 Timeout: Server unreachable. Check Domain/IP, Port, or switch Transport (UDP/TCP)"
                    503 -> "503 Service Unavailable: SIP server is offline or rejected registration"
                    else -> phrase.ifBlank { message.ifBlank { "Registration failed" } }
                }
            }
            LinphoneNative.REG_CLEARED, LinphoneNative.REG_NONE -> {
                _registrationState.value = RegistrationStatus.UNREGISTERED
                _registrationMessage.value = "Unregistered"
                registeredNumber = null
            }
        }
    }

    override fun unregisterCurrentAccount() {
        sip.launch {
            core?.let {
                native.linphone_core_clear_accounts(it)
                native.linphone_core_clear_all_auth_info(it)
            }
        }
        registeredNumber = null
        _registrationState.value = RegistrationStatus.UNREGISTERED
        _registrationMessage.value = "Unregistered"
        _accountBalance.value = null
    }

    override fun onPushWakeup() {
        sip.launch { core?.let { native.linphone_core_ensure_registered(it) } }
    }

    // ---- Calls ----

    override fun makeCall(destinationUri: String, displayName: String) {
        if (_callState.value is CallState.Connected) {
            addParticipantToCall(destinationUri, displayName)
            return
        }
        sip.launch {
            val call = invite(destinationUri) ?: return@launch
            primaryCall = call
            val uri = remoteUri(call)
            _callState.value = CallState.Outgoing(uri, displayName.ifBlank { userPart(uri) })
        }
    }

    /** Starts an outgoing call on the Linphone thread; returns the (ref'd) call or null. */
    private fun invite(destination: String): Pointer? {
        val c = core ?: run {
            log("[SIP Call] SIP engine not running")
            return null
        }
        val n = native
        val target = destination.trim().replace(Regex("[\\s\\-()]"), "").removePrefix("sip:").removePrefix("sips:")
            .let { dest ->
                // +8801XXXXXXXXX -> 01XXXXXXXXX (see toDialableNumber); "@domain" stays as typed
                val user = dest.substringBefore("@")
                toDialableNumber(user) + dest.substring(user.length)
            }
        val domain = activeAccountModel?.domain?.let { cleanHost(it) }
        val uri = when {
            target.contains("@") -> "sip:$target"
            domain != null -> "sip:$target@$domain"
            else -> "sip:$target"
        }
        val address = n.linphone_factory_create_address(factory!!, uri) ?: run {
            log("[SIP Call] Invalid number: $destination")
            return null
        }
        val params = n.linphone_core_create_call_params(c, null) ?: return null
        n.linphone_core_get_default_account(c)?.let { n.linphone_call_params_set_account(params, it) }
        n.linphone_call_params_enable_audio(params, TRUE)
        n.linphone_call_params_enable_video(params, FALSE)
        if (currentSettings.recordCalls) {
            n.linphone_call_params_set_record_file(params, CallRecordings.newFile(uri, incoming = false).absolutePath)
        }
        log("[SIP Call] Dialing $uri")
        val call = n.linphone_core_invite_address_with_params(c, address, params)
        if (call == null) log("[SIP Call] Could not start the call to $uri")
        return call?.also { n.linphone_call_ref(it) }
    }

    override fun acceptCall() {
        sip.launch {
            val call = primaryCall ?: return@launch
            val n = native
            if (currentSettings.recordCalls) {
                val params = n.linphone_core_create_call_params(core!!, call)
                if (params != null) {
                    n.linphone_call_params_set_record_file(params, CallRecordings.newFile(remoteUri(call), incoming = true).absolutePath)
                    n.linphone_call_accept_with_params(call, params)
                    return@launch
                }
            }
            n.linphone_call_accept(call)
        }
    }

    override fun hangupCall() {
        val current = _callState.value
        val (uri, name) = when (current) {
            is CallState.Connected -> current.remoteUri to current.displayName
            is CallState.Outgoing -> current.remoteUri to current.displayName
            is CallState.Incoming -> current.remoteUri to current.displayName
            else -> "" to ""
        }
        val duration = _callDuration.value
        sip.launch {
            core?.let { c ->
                native.linphone_core_get_conference(c)?.let { native.linphone_conference_terminate(it) }
                native.linphone_core_terminate_all_calls(c)
                native.linphone_core_enable_mic(c, TRUE)
            }
            releaseCalls()
        }
        stopDurationTimer()
        _callState.value = CallState.Disconnected("Call Terminated")
        emitCallEnded(uri, name, duration, wasMissed = current is CallState.Incoming)
        resetToIdleSoon()
    }

    override fun toggleMute() {
        val mute = !_isMuted.value
        _isMuted.value = mute
        sip.launch { core?.let { native.linphone_core_enable_mic(it, bool(!mute)) } }
        updateConnected()
    }

    // A computer has one output (speakers or headset, chosen in Windows); the button just flips
    // the icon so the shared call screen behaves the same.
    override fun toggleSpeaker() {
        _isSpeakerOn.value = !_isSpeakerOn.value
        updateConnected()
    }

    override fun toggleHold() {
        val hold = !_isOnHold.value
        _isOnHold.value = hold
        sip.launch {
            val call = primaryCall ?: return@launch
            if (hold) native.linphone_call_pause(call) else native.linphone_call_resume(call)
        }
        updateConnected()
    }

    override fun sendDtmf(dtmfChar: Char) {
        sip.launch {
            val call = primaryCall ?: return@launch
            if (native.linphone_call_get_state(call) == CALL_STREAMS_RUNNING) {
                native.linphone_call_send_dtmf(call, dtmfChar.code.toByte())
            }
        }
    }

    override fun simulateIncomingCall(callerUri: String, callerName: String) {
        if (_callState.value !is CallState.Idle) return
        _callState.value = CallState.Incoming(callerUri, callerName.ifBlank { callerUri })
    }

    override fun simulateAutoAnswer() = acceptCall()

    // ---- Second line and conference ----

    override fun addParticipantToCall(destinationUri: String, displayName: String) {
        val current = _callState.value as? CallState.Connected ?: return
        if (current.secondaryCall != null) return
        sip.launch {
            // Line 1 waits (with hold music) while line 2 rings
            primaryCall?.let { native.linphone_call_pause(it) }
            val call = invite(destinationUri) ?: run {
                primaryCall?.let { native.linphone_call_resume(it) }
                return@launch
            }
            secondaryCall = call
            val uri = remoteUri(call)
            _isOnHold.value = true
            _callState.value = current.copy(
                isOnHold = true,
                secondaryCall = SecondaryCallInfo(uri = uri, displayName = displayName.ifBlank { userPart(uri) }, isOnHold = false)
            )
        }
    }

    override fun mergeCallsIntoConference() {
        val current = _callState.value as? CallState.Connected ?: return
        val second = current.secondaryCall ?: return
        if (!second.isConnected) {
            log("[Conference] Line 2 has not answered yet. Merge after it connects.")
            return
        }
        sip.launch {
            val c = core ?: return@launch
            val ok = native.linphone_core_add_all_to_conference(c) == 0
            log(if (ok) "[Conference] Calls merged" else "[Conference] Merge failed")
            if (!ok) return@launch
            _isOnHold.value = false
            _callState.value = current.copy(
                isOnHold = false,
                isConference = true,
                secondaryCall = null,
                participants = listOf(
                    ConferenceParticipant("conf_p_1", current.remoteUri, current.displayName.ifBlank { userPart(current.remoteUri) }),
                    ConferenceParticipant("conf_p_2", second.uri, second.displayName.ifBlank { userPart(second.uri) })
                )
            )
        }
    }

    override fun removeParticipantFromConference(participantId: String) {
        val current = _callState.value as? CallState.Connected ?: return
        val participant = current.participants.firstOrNull { it.id == participantId } ?: return
        sip.launch {
            val c = core ?: return@launch
            liveCalls(c).firstOrNull { userPart(remoteUri(it)) == userPart(participant.uri) }
                ?.let { native.linphone_call_terminate(it) }
        }
    }

    override fun toggleParticipantMute(participantId: String) {
        val current = _callState.value as? CallState.Connected ?: return
        _callState.value = current.copy(
            participants = current.participants.map { if (it.id == participantId) it.copy(isMuted = !it.isMuted) else it }
        )
    }

    override fun swapActiveAndHeldCalls() {
        val current = _callState.value as? CallState.Connected ?: return
        val second = current.secondaryCall ?: return
        sip.launch {
            val first = primaryCall ?: return@launch
            val other = secondaryCall ?: return@launch
            native.linphone_call_pause(first)
            native.linphone_call_resume(other)
            primaryCall = other
            secondaryCall = first
            _callState.value = current.copy(
                remoteUri = second.uri,
                displayName = second.displayName,
                isOnHold = false,
                secondaryCall = SecondaryCallInfo(current.remoteUri, current.displayName, isOnHold = true, isConnected = true)
            )
            _isOnHold.value = false
        }
    }

    override fun hangupSecondaryCall() {
        sip.launch { secondaryCall?.let { native.linphone_call_terminate(it) } }
    }

    private fun onCallState(c: Pointer, call: Pointer, state: Int, message: String) {
        val n = native
        updateRecording(c, call, state)
        val uri = remoteUri(call)
        val name = n.linphone_call_get_remote_address(call)?.let { n.linphone_address_get_display_name(it) }
            ?.takeIf { it.isNotBlank() } ?: userPart(uri)
        val current = _callState.value

        if (current is CallState.Connected && current.isConference) {
            if (state == CALL_END || state == CALL_ERROR || state == CALL_RELEASED) {
                val others = liveCalls(c).filter { it != call }
                when {
                    others.isEmpty() -> Unit // falls through to the normal end below
                    others.size == 1 -> {
                        primaryCall = others.first()
                        _callState.value = current.copy(
                            remoteUri = remoteUri(others.first()),
                            displayName = userPart(remoteUri(others.first())),
                            isConference = false,
                            participants = emptyList()
                        )
                        return
                    }
                    else -> {
                        _callState.value = current.copy(participants = current.participants.filterNot { userPart(it.uri) == userPart(uri) })
                        return
                    }
                }
            } else {
                return
            }
        }

        // Second line
        if (call == secondaryCall && current is CallState.Connected) {
            when (state) {
                CALL_CONNECTED, CALL_STREAMS_RUNNING -> _callState.value = current.copy(
                    secondaryCall = (current.secondaryCall ?: SecondaryCallInfo(uri, name)).copy(isOnHold = false, isConnected = true)
                )
                CALL_PAUSED -> _callState.value = current.copy(secondaryCall = current.secondaryCall?.copy(isOnHold = true))
                CALL_END, CALL_ERROR, CALL_RELEASED -> {
                    if (state != CALL_RELEASED) log("[Line 2] Call ended ($message). Resuming line 1.")
                    n.linphone_call_unref(call)
                    secondaryCall = null
                    primaryCall?.let { n.linphone_call_resume(it) }
                    _isOnHold.value = false
                    _callState.value = current.copy(isOnHold = false, secondaryCall = null)
                }
            }
            return
        }

        when (state) {
            CALL_INCOMING_RECEIVED, CALL_PUSH_INCOMING_RECEIVED, CALL_INCOMING_EARLY_MEDIA -> {
                if (primaryCall != null && primaryCall != call) {
                    // Already busy: decline the second incoming call
                    n.linphone_call_terminate(call)
                    return
                }
                if (primaryCall == null) primaryCall = n.linphone_call_ref(call)
                _callState.value = CallState.Incoming(uri, name)
                emitCallStarted(uri, name, incoming = true)
                if (currentSettings.autoAnswer) {
                    scope.launch {
                        delay(currentSettings.autoAnswerDelaySeconds.coerceAtLeast(1) * 1000L)
                        if (_callState.value is CallState.Incoming) acceptCall()
                    }
                }
            }
            CALL_OUTGOING_INIT, CALL_OUTGOING_PROGRESS -> {
                _callState.value = CallState.Outgoing(uri, name, isEarlyMediaOrRinging = false)
                emitCallStarted(uri, name, incoming = false)
            }
            CALL_OUTGOING_RINGING, CALL_OUTGOING_EARLY_MEDIA -> {
                _callState.value = CallState.Outgoing(uri, name, isEarlyMediaOrRinging = true)
            }
            CALL_CONNECTED, CALL_STREAMS_RUNNING -> {
                if (_callState.value !is CallState.Connected) startDurationTimer()
                val existing = _callState.value as? CallState.Connected
                _callState.value = CallState.Connected(
                    remoteUri = uri,
                    displayName = name,
                    durationSeconds = _callDuration.value,
                    isMuted = _isMuted.value,
                    isSpeakerOn = _isSpeakerOn.value,
                    isOnHold = _isOnHold.value,
                    isConference = existing?.isConference ?: false,
                    participants = existing?.participants.orEmpty(),
                    secondaryCall = existing?.secondaryCall
                )
            }
            CALL_PAUSING, CALL_PAUSED -> {
                _isOnHold.value = true
                updateConnected()
            }
            CALL_RESUMING -> {
                _isOnHold.value = false
                updateConnected()
            }
            CALL_END, CALL_ERROR, CALL_RELEASED -> {
                if (call != primaryCall) return
                val others = liveCalls(c).filter { it != call }
                if (others.isNotEmpty()) {
                    // Line 1 hung up while line 2 is up: line 2 becomes the call
                    n.linphone_call_unref(call)
                    primaryCall = others.first()
                    secondaryCall = null
                    n.linphone_call_resume(others.first())
                    _isOnHold.value = false
                    (current as? CallState.Connected)?.let {
                        _callState.value = it.copy(
                            remoteUri = remoteUri(others.first()),
                            displayName = userPart(remoteUri(others.first())),
                            isOnHold = false,
                            secondaryCall = null
                        )
                    }
                    return
                }
                releaseCalls()
                if (!callSessionActive) return
                val duration = _callDuration.value
                stopDurationTimer()
                n.linphone_core_enable_mic(c, TRUE)
                _callState.value = CallState.Disconnected(message.ifBlank { "Call Ended" })
                emitCallEnded(uri, name, duration, wasMissed = current is CallState.Incoming)
                refreshBalanceSoon()
                resetToIdleSoon()
            }
        }
    }

    private fun updateRecording(c: Pointer, call: Pointer, state: Int) {
        val n = native
        try {
            when (state) {
                CALL_STREAMS_RUNNING -> {
                    val params = n.linphone_call_get_params(call)
                    if (params != null && n.linphone_call_params_get_record_file(params) != null &&
                        n.linphone_call_is_recording(call) == FALSE
                    ) {
                        n.linphone_call_start_recording(call)
                    }
                }
                CALL_END, CALL_ERROR, CALL_RELEASED -> {
                    if (n.linphone_call_is_recording(call) != FALSE) n.linphone_call_stop_recording(call)
                    CallRecordings.cleanUpEmpty()
                }
            }
        } catch (e: Throwable) {
            log("[Recording] ${e.message}")
        }
        _isRecording.value = liveCalls(c).any { n.linphone_call_is_recording(it) != FALSE }
    }

    private fun releaseCalls() {
        primaryCall?.let { native.linphone_call_unref(it) }
        secondaryCall?.let { native.linphone_call_unref(it) }
        primaryCall = null
        secondaryCall = null
    }

    private fun liveCalls(c: Pointer): List<Pointer> =
        lib!!.listItems(native.linphone_core_get_calls(c)).filter {
            native.linphone_call_get_state(it) !in setOf(CALL_END, CALL_ERROR, CALL_RELEASED)
        }

    private fun remoteUri(call: Pointer): String =
        native.linphone_call_get_remote_address(call)?.let { lib!!.takeString(native.linphone_address_as_string_uri_only(it)) }
            ?: "Unknown"

    // ---- Chat ----

    override fun sendChatMessage(to: String, text: String, headers: Map<String, String>, onResult: (Boolean) -> Unit) {
        sip.launch {
            val c = core
            val domain = activeAccountModel?.domain?.let { cleanHost(it) }
            if (c == null || domain == null || _registrationState.value != RegistrationStatus.REGISTERED) {
                onResult(false)
                return@launch
            }
            val n = native
            val uri = when {
                to.startsWith("sip:") -> to
                to.contains("@") -> "sip:$to"
                else -> "sip:$to@$domain"
            }
            val room = n.linphone_factory_create_address(factory!!, uri)?.let { n.linphone_core_get_chat_room(c, it) }
            if (room == null) {
                onResult(false)
                return@launch
            }
            val message = n.linphone_chat_room_create_message_from_utf8(room, text)
            headers.forEach { (name, value) -> n.linphone_chat_message_add_custom_header(message, name, value) }
            val cbs = n.linphone_factory_create_chat_message_cbs(factory!!)
            n.linphone_chat_message_cbs_set_msg_state_changed(cbs, messageStateCb)
            n.linphone_chat_message_add_callbacks(message, cbs)
            n.linphone_chat_message_ref(message)
            pendingChat[message] = onResult
            n.linphone_chat_message_send(message)
        }
    }

    // ---- Helpers ----

    private fun emitCallStarted(uri: String, name: String, incoming: Boolean) {
        if (callSessionActive) return
        callSessionActive = true
        callSessionIncoming = incoming
        _callEvents.tryEmit(CallEvent.CallStarted(uri, name, incoming))
    }

    private fun emitCallEnded(uri: String, name: String, duration: Long, wasMissed: Boolean) {
        if (!callSessionActive) return
        callSessionActive = false
        _callEvents.tryEmit(CallEvent.CallEnded(uri, name, duration, wasMissed, callSessionIncoming))
    }

    private fun resetToIdleSoon() {
        scope.launch {
            delay(1200)
            if (_callState.value is CallState.Disconnected) {
                _callState.value = CallState.Idle
                _callDuration.value = 0L
                _isMuted.value = false
                _isSpeakerOn.value = false
                _isOnHold.value = false
            }
        }
    }

    /** Re-REGISTER shortly after a call so the balance header shows the new balance. */
    private fun refreshBalanceSoon() {
        sip.launch {
            delay(1500)
            core?.let { c -> native.linphone_core_get_default_account(c)?.let { native.linphone_account_refresh_register(it) } }
        }
    }

    private fun startDurationTimer() {
        durationJob?.cancel()
        _callDuration.value = 0L
        durationJob = scope.launch {
            while (isActive) {
                delay(1000)
                _callDuration.value += 1
                (_callState.value as? CallState.Connected)?.let { _callState.value = it.copy(durationSeconds = _callDuration.value) }
            }
        }
    }

    private fun stopDurationTimer() {
        durationJob?.cancel()
        durationJob = null
    }

    private fun updateConnected() {
        val current = _callState.value as? CallState.Connected ?: return
        _callState.value = current.copy(isMuted = _isMuted.value, isSpeakerOn = _isSpeakerOn.value, isOnHold = _isOnHold.value)
    }

    private fun cleanHost(domain: String): String =
        domain.trim().removePrefix("sip:").removePrefix("sips:").removePrefix("http://").removePrefix("https://")
            .trimEnd('/').substringBefore(":").trim()

    private fun userPart(uri: String): String =
        uri.removePrefix("sip:").removePrefix("sips:").substringBefore("@").substringBefore(";")

    private fun regName(state: Int) = when (state) {
        LinphoneNative.REG_NONE -> "None"
        LinphoneNative.REG_PROGRESS -> "Progress"
        LinphoneNative.REG_OK -> "Ok"
        LinphoneNative.REG_CLEARED -> "Cleared"
        LinphoneNative.REG_FAILED -> "Failed"
        LinphoneNative.REG_REFRESHING -> "Refreshing"
        else -> state.toString()
    }

    companion object {
        /** %APPDATA%\Dialer: database, settings and Linphone state. */
        fun appDataDir(): File =
            File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Dialer").apply { mkdirs() }
    }
}
