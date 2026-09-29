package com.example.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

actual val isMobilePlatform: Boolean = false

/** Message shown by [rememberToast]; the window draws it at the bottom (see DesktopToastHost). */
object DesktopToast {
    var message by mutableStateOf<String?>(null)
        internal set

    fun show(text: String) {
        message = text
    }

    fun dismiss() {
        message = null
    }
}

@Composable
actual fun rememberToast(): (String) -> Unit = DesktopToast::show

// Windows asks for the microphone itself the first time audio is used
@Composable
actual fun RequestStartupPermissions(onContactsGranted: () -> Unit) {}

@Composable
actual fun rememberContactsPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit = { onResult(true) }

@Composable
actual fun EnsureMicrophonePermission() {}
