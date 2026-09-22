package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.PushMessageItem
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FcmTokenManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("fcm_token_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _fcmToken = MutableStateFlow<String?>(prefs.getString(KEY_FCM_TOKEN, null))
    val fcmToken: StateFlow<String?> = _fcmToken.asStateFlow()

    private val _tokenStatus = MutableStateFlow(
        if (_fcmToken.value != null) "FCM Token Ready" else "Initializing FCM..."
    )
    val tokenStatus: StateFlow<String> = _tokenStatus.asStateFlow()

    private val _receivedPushes = MutableStateFlow<List<PushMessageItem>>(emptyList())
    val receivedPushes: StateFlow<List<PushMessageItem>> = _receivedPushes.asStateFlow()

    fun initialize() {
        fetchToken()
    }

    fun fetchToken() {
        _tokenStatus.value = "Fetching FCM Device Token..."
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    val errorMsg = task.exception?.message ?: "Unknown FCM error"
                    Log.w(TAG, "Fetching FCM registration token failed: $errorMsg", task.exception)
                    _tokenStatus.value = "FCM Token Error: $errorMsg"
                    return@addOnCompleteListener
                }

                // Get new FCM registration token
                val token = task.result
                Log.d(TAG, "FCM Device Token retrieved: $token")
                saveToken(token)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "FirebaseMessaging exception: ${e.message}", e)
            _tokenStatus.value = "FCM Unavailable (Offline/Config)"
        }
    }

    fun saveToken(token: String) {
        _fcmToken.value = token
        _tokenStatus.value = "Active • Ready for Background Push"
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    fun refreshToken() {
        scope.launch {
            try {
                _tokenStatus.value = "Refreshing FCM token..."
                FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener {
                    fetchToken()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error refreshing token: ${e.message}")
                fetchToken()
            }
        }
    }

    fun recordPushMessage(item: PushMessageItem) {
        val updated = (listOf(item) + _receivedPushes.value).take(30)
        _receivedPushes.value = updated
    }

    fun clearPushHistory() {
        _receivedPushes.value = emptyList()
    }

    companion object {
        private const val TAG = "FcmTokenManager"
        private const val KEY_FCM_TOKEN = "key_cached_fcm_token"
    }
}
