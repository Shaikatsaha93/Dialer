package com.example.desktop.sip

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.io.File

/**
 * The part of the liblinphone C API the Windows app uses, called through JNA.
 * Every object is an opaque pointer. bool_t is an unsigned char, so it is mapped to Byte.
 * All calls must happen on the Linphone thread (see DesktopSipManager).
 */
@Suppress("FunctionName", "LocalVariableName")
interface LinphoneNative : Library {
    // Factory
    fun linphone_factory_get(): Pointer
    fun linphone_factory_set_top_resources_dir(factory: Pointer, path: String)
    fun linphone_factory_set_msplugins_dir(factory: Pointer, path: String)
    fun linphone_factory_create_config(factory: Pointer, path: String?): Pointer
    fun linphone_factory_create_core_with_config_3(factory: Pointer, config: Pointer, context: Pointer?): Pointer?
    fun linphone_factory_create_core_cbs(factory: Pointer): Pointer
    fun linphone_factory_create_address(factory: Pointer, address: String): Pointer?
    fun linphone_factory_create_auth_info_2(
        factory: Pointer, username: String, userid: String?, passwd: String?, ha1: String?,
        realm: String?, domain: String?, algorithm: String?
    ): Pointer
    fun linphone_factory_create_chat_message_cbs(factory: Pointer): Pointer

    // Config
    fun linphone_config_set_int(config: Pointer, section: String, key: String, value: Int)
    fun linphone_config_set_string(config: Pointer, section: String, key: String, value: String?)

    // Core
    fun linphone_core_cbs_set_account_registration_state_changed(cbs: Pointer, cb: AccountRegistrationStateChangedCb)
    fun linphone_core_cbs_set_call_state_changed(cbs: Pointer, cb: CallStateChangedCb)
    fun linphone_core_cbs_set_message_received(cbs: Pointer, cb: MessageReceivedCb)
    fun linphone_core_add_callbacks(core: Pointer, cbs: Pointer)
    fun linphone_core_start(core: Pointer): Int
    fun linphone_core_iterate(core: Pointer)
    fun linphone_core_stop(core: Pointer)
    fun linphone_core_set_network_reachable(core: Pointer, reachable: Byte)
    fun linphone_core_enable_keep_alive(core: Pointer, enable: Byte)
    fun linphone_core_enable_video_capture(core: Pointer, enable: Byte)
    fun linphone_core_enable_video_display(core: Pointer, enable: Byte)
    fun linphone_core_enable_echo_cancellation(core: Pointer, enable: Byte)
    fun linphone_core_enable_adaptive_rate_control(core: Pointer, enable: Byte)
    fun linphone_core_enable_ipv6(core: Pointer, enable: Byte)
    fun linphone_core_set_mic_gain_db(core: Pointer, level: Float)
    fun linphone_core_set_stun_server(core: Pointer, server: String?)
    fun linphone_core_enable_mic(core: Pointer, enable: Byte)
    fun linphone_core_set_play_file(core: Pointer, file: String?)
    fun linphone_core_set_ring(core: Pointer, path: String?)
    fun linphone_core_set_ringback(core: Pointer, path: String?)
    fun linphone_core_set_user_agent(core: Pointer, name: String, version: String)
    fun linphone_core_get_audio_payload_types(core: Pointer): Pointer?
    fun linphone_payload_type_enable(pt: Pointer, enabled: Byte): Int
    fun linphone_payload_type_get_mime_type(pt: Pointer): String?
    fun linphone_payload_type_get_clock_rate(pt: Pointer): Int

    // Accounts
    fun linphone_core_create_account_params(core: Pointer): Pointer
    fun linphone_account_params_set_identity_address(params: Pointer, address: Pointer): Int
    fun linphone_account_params_set_server_address(params: Pointer, address: Pointer): Int
    fun linphone_account_params_set_register_enabled(params: Pointer, enable: Byte)
    fun linphone_account_params_set_expires(params: Pointer, expires: Int)
    fun linphone_core_create_account(core: Pointer, params: Pointer): Pointer
    fun linphone_core_add_account(core: Pointer, account: Pointer): Int
    fun linphone_core_set_default_account(core: Pointer, account: Pointer?)
    fun linphone_core_get_default_account(core: Pointer): Pointer?
    fun linphone_core_clear_accounts(core: Pointer)
    fun linphone_core_clear_all_auth_info(core: Pointer)
    fun linphone_core_add_auth_info(core: Pointer, info: Pointer)
    fun linphone_core_ensure_registered(core: Pointer)
    fun linphone_account_get_custom_header(account: Pointer, name: String): String?
    fun linphone_account_get_error_info(account: Pointer): Pointer?
    fun linphone_account_refresh_register(account: Pointer)
    fun linphone_error_info_get_protocol_code(info: Pointer): Int
    fun linphone_error_info_get_phrase(info: Pointer): String?

    // Addresses
    fun linphone_address_set_display_name(address: Pointer, name: String?): Int
    fun linphone_address_get_username(address: Pointer): String?
    fun linphone_address_get_display_name(address: Pointer): String?
    fun linphone_address_get_domain(address: Pointer): String?
    fun linphone_address_as_string_uri_only(address: Pointer): Pointer?

    // Calls
    fun linphone_core_create_call_params(core: Pointer, call: Pointer?): Pointer?
    fun linphone_call_params_set_account(params: Pointer, account: Pointer?)
    fun linphone_call_params_enable_video(params: Pointer, enable: Byte)
    fun linphone_call_params_enable_audio(params: Pointer, enable: Byte)
    fun linphone_call_params_set_record_file(params: Pointer, path: String?)
    fun linphone_call_params_get_record_file(params: Pointer): String?
    fun linphone_call_get_params(call: Pointer): Pointer?
    fun linphone_core_invite_address_with_params(core: Pointer, address: Pointer, params: Pointer): Pointer?
    fun linphone_call_accept(call: Pointer): Int
    fun linphone_call_accept_with_params(call: Pointer, params: Pointer): Int
    fun linphone_call_terminate(call: Pointer): Int
    fun linphone_core_terminate_all_calls(core: Pointer): Int
    fun linphone_call_pause(call: Pointer): Int
    fun linphone_call_resume(call: Pointer): Int
    fun linphone_call_send_dtmf(call: Pointer, dtmf: Byte): Int
    fun linphone_call_start_recording(call: Pointer)
    fun linphone_call_stop_recording(call: Pointer)
    fun linphone_call_is_recording(call: Pointer): Byte
    fun linphone_call_get_state(call: Pointer): Int
    fun linphone_call_get_remote_address(call: Pointer): Pointer?
    fun linphone_call_ref(call: Pointer): Pointer
    fun linphone_call_unref(call: Pointer)
    fun linphone_core_get_calls(core: Pointer): Pointer?
    fun linphone_core_add_all_to_conference(core: Pointer): Int
    fun linphone_core_get_conference(core: Pointer): Pointer?
    fun linphone_conference_terminate(conference: Pointer): Int

    // Chat (SIP MESSAGE)
    fun linphone_core_get_chat_room(core: Pointer, address: Pointer): Pointer?
    fun linphone_chat_room_create_message_from_utf8(room: Pointer, text: String): Pointer
    fun linphone_chat_room_mark_as_read(room: Pointer)
    fun linphone_chat_message_add_custom_header(message: Pointer, name: String, value: String)
    fun linphone_chat_message_get_custom_header(message: Pointer, name: String): String?
    fun linphone_chat_message_add_callbacks(message: Pointer, cbs: Pointer)
    fun linphone_chat_message_cbs_set_msg_state_changed(cbs: Pointer, cb: MessageStateChangedCb)
    fun linphone_chat_message_send(message: Pointer)
    fun linphone_chat_message_ref(message: Pointer): Pointer
    fun linphone_chat_message_unref(message: Pointer)
    fun linphone_chat_message_get_utf8_text(message: Pointer): String?
    fun linphone_chat_message_get_from_address(message: Pointer): Pointer?
    fun linphone_chat_message_get_time(message: Pointer): Long

    // Logging
    fun linphone_logging_service_get(): Pointer
    fun linphone_logging_service_set_log_level(service: Pointer, level: Int)

    // Callbacks (C function pointers). Keep strong references: JNA frees them otherwise.
    fun interface AccountRegistrationStateChangedCb : Callback {
        fun invoke(core: Pointer, account: Pointer, state: Int, message: String?)
    }

    fun interface CallStateChangedCb : Callback {
        fun invoke(core: Pointer, call: Pointer, state: Int, message: String?)
    }

    fun interface MessageReceivedCb : Callback {
        fun invoke(core: Pointer, room: Pointer, message: Pointer)
    }

    fun interface MessageStateChangedCb : Callback {
        fun invoke(message: Pointer, state: Int)
    }

    companion object {
        // LinphoneCallState
        const val CALL_INCOMING_RECEIVED = 1
        const val CALL_PUSH_INCOMING_RECEIVED = 2
        const val CALL_OUTGOING_INIT = 3
        const val CALL_OUTGOING_PROGRESS = 4
        const val CALL_OUTGOING_RINGING = 5
        const val CALL_OUTGOING_EARLY_MEDIA = 6
        const val CALL_CONNECTED = 7
        const val CALL_STREAMS_RUNNING = 8
        const val CALL_PAUSING = 9
        const val CALL_PAUSED = 10
        const val CALL_RESUMING = 11
        const val CALL_ERROR = 13
        const val CALL_END = 14
        const val CALL_INCOMING_EARLY_MEDIA = 17
        const val CALL_RELEASED = 19

        // LinphoneRegistrationState
        const val REG_NONE = 0
        const val REG_PROGRESS = 1
        const val REG_OK = 2
        const val REG_CLEARED = 3
        const val REG_FAILED = 4
        const val REG_REFRESHING = 5

        // LinphoneChatMessageState
        const val MSG_DELIVERED = 2
        const val MSG_NOT_DELIVERED = 3
        const val MSG_DELIVERED_TO_USER = 6
        const val MSG_DISPLAYED = 7

        // LinphoneLogLevel (bit mask)
        const val LOG_MESSAGE = 1 shl 2
        const val LOG_WARNING = 1 shl 3

        const val TRUE: Byte = 1
        const val FALSE: Byte = 0

        fun bool(value: Boolean): Byte = if (value) TRUE else FALSE
    }
}

/** bctoolbox helpers: list walking and freeing strings returned by liblinphone. */
@Suppress("FunctionName")
interface BctoolboxNative : Library {
    fun bctbx_list_next(list: Pointer): Pointer?
    fun bctbx_list_get_data(list: Pointer): Pointer?
    fun bctbx_list_free(list: Pointer?): Pointer?
    fun bctbx_free(ptr: Pointer?)
}

private interface Kernel32Dll : StdCallLibrary {
    fun SetDllDirectoryW(path: WString): Boolean
}

/** Loads liblinphone from the folder the app ships it in. */
class LinphoneLibrary(val root: File) {
    val linphone: LinphoneNative
    val bctoolbox: BctoolboxNative

    val binDir get() = File(root, "bin")
    val pluginDir get() = File(root, "lib/mediastreamer/plugins")
    val shareDir get() = File(root, "share")

    init {
        require(File(binDir, "liblinphone.dll").exists()) { "liblinphone.dll not found in $binDir" }
        // Lets Windows find the DLLs liblinphone and the audio plugins depend on
        Native.load("kernel32", Kernel32Dll::class.java).SetDllDirectoryW(WString(binDir.absolutePath))
        val options = mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")
        bctoolbox = Native.load(File(binDir, "bctoolbox.dll").absolutePath, BctoolboxNative::class.java, options)
        linphone = Native.load(File(binDir, "liblinphone.dll").absolutePath, LinphoneNative::class.java, options)
    }

    /** Walks a bctbx_list_t of pointers. */
    fun listItems(list: Pointer?): List<Pointer> {
        val items = mutableListOf<Pointer>()
        var node = list
        while (node != null) {
            bctoolbox.bctbx_list_get_data(node)?.let { items += it }
            node = bctoolbox.bctbx_list_next(node)
        }
        return items
    }

    /** Reads and frees a char* that liblinphone handed over. */
    fun takeString(ptr: Pointer?): String? {
        if (ptr == null) return null
        return try {
            ptr.getString(0, "UTF-8")
        } finally {
            bctoolbox.bctbx_free(ptr)
        }
    }

    companion object {
        /**
         * Folder with bin/, lib/ and share/: next to the installed app, or desktopApp/linphone
         * when started from Gradle.
         */
        fun locate(): File {
            val candidates = listOfNotNull(
                System.getProperty("compose.application.resources.dir")?.let { File(it) },
                File("linphone/windows-x64"),
                File("desktopApp/linphone/windows-x64")
            )
            return candidates.firstOrNull { File(it, "bin/liblinphone.dll").exists() }
                ?: candidates.first()
        }
    }
}
