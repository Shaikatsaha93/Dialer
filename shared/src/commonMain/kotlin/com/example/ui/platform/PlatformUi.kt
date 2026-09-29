package com.example.ui.platform

import androidx.compose.runtime.Composable

/** True on phones (Android, later iOS): shows background/push settings and phone-only UI. */
expect val isMobilePlatform: Boolean

/** Returns a function that shows a short message (a Toast on Android, a snackbar-like popup on Windows). */
@Composable
expect fun rememberToast(): (String) -> Unit

/**
 * Asks once for what the app needs at start (Android: microphone, contacts, notifications...).
 * [onContactsGranted] runs when contacts may be read.
 */
@Composable
expect fun RequestStartupPermissions(onContactsGranted: () -> Unit)

/** Returns a function that asks for the contacts permission; [onResult] gets whether it was granted. */
@Composable
expect fun rememberContactsPermissionRequest(onResult: (Boolean) -> Unit): () -> Unit

/** Asks for the microphone again when it is not allowed (e.g. denied once): a call without it is silent. */
@Composable
expect fun EnsureMicrophonePermission()
