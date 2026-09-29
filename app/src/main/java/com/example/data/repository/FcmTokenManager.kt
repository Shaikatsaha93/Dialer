package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.model.PushMessageItem
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FcmTokenManager(private val context: Context) : PushSource {

    private val prefs = context.getSharedPreferences("fcm_token_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)

    // Last real token Firebase gave us (never a made-up one: a fake token can never receive a push)
    private val _fcmToken = MutableStateFlow(
        prefs.getString(KEY_FCM_TOKEN, null)?.takeUnless { it.startsWith(FAKE_TOKEN_PREFIX) }
    )
    override val fcmToken: StateFlow<String?> = _fcmToken.asStateFlow()

    private val _tokenStatus = MutableStateFlow("Fetching FCM token...")
    override val tokenStatus: StateFlow<String> = _tokenStatus.asStateFlow()

    private val _receivedPushes = MutableStateFlow<List<PushMessageItem>>(emptyList())
    override val receivedPushes: StateFlow<List<PushMessageItem>> = _receivedPushes.asStateFlow()

    fun initialize() {
        // Drop a fake token saved by older versions
        if (prefs.getString(KEY_FCM_TOKEN, null)?.startsWith(FAKE_TOKEN_PREFIX) == true) {
            prefs.edit().remove(KEY_FCM_TOKEN).apply()
        }
        ensureFirebaseInitialized()
        fetchLiveTokenInBackground()
    }

    private fun ensureFirebaseInitialized() {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApplicationId("1:99810658098:android:b20c3467cf2df34c")
                    .setApiKey("AIzaSyD2dyTMFtr-0aKqo_4VnVbuOnVjk4deAvY")
                    .setProjectId("fir-7eb9d")
                    .setGcmSenderId("99810658098")
                    .setDatabaseUrl("https://fir-7eb9d.firebaseio.com")
                    .setStorageBucket("fir-7eb9d.firebasestorage.app")
                    .build()
                FirebaseApp.initializeApp(context, options)
                Log.d(TAG, "FirebaseApp initialized with explicit options")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Explicit FirebaseApp init notice: ${e.message}")
        }
    }

    private fun fetchLiveTokenInBackground() {
        scope.launch {
            ensureFirebaseInitialized()
            try {
                val gmsAvailability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
                if (gmsAvailability != ConnectionResult.SUCCESS) {
                    Log.w(TAG, "Google Play Services unavailable ($gmsAvailability): no FCM push on this device")
                    _tokenStatus.value = "Unavailable • Google Play Services missing"
                    return@launch
                }

                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    val token = if (task.isSuccessful) task.result else null
                    if (!token.isNullOrBlank()) {
                        Log.d(TAG, "FCM token: $token")
                        saveToken(token)
                    } else {
                        val errorMsg = task.exception?.localizedMessage ?: "unknown error"
                        Log.w(TAG, "FCM token fetch failed: $errorMsg")
                        _tokenStatus.value = if (_fcmToken.value != null) {
                            "Active • Using last token (refresh failed: $errorMsg)"
                        } else {
                            "Failed • $errorMsg"
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "FirebaseMessaging error: ${e.message}")
                _tokenStatus.value = "Failed • ${e.message}"
            }
        }
    }

    fun fetchToken() {
        fetchLiveTokenInBackground()
    }

    fun saveToken(token: String) {
        _fcmToken.value = token
        _tokenStatus.value = "Active • Real FCM token"
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    override fun refreshToken() {
        _tokenStatus.value = "Refreshing FCM token..."
        fetchLiveTokenInBackground()
    }

    override fun recordPushMessage(item: PushMessageItem) {
        val updated = (listOf(item) + _receivedPushes.value).take(30)
        _receivedPushes.value = updated
    }

    override fun clearPushHistory() {
        _receivedPushes.value = emptyList()
    }

    companion object {
        private const val TAG = "FcmTokenManager"
        private const val KEY_FCM_TOKEN = "key_cached_fcm_token"
        private const val FAKE_TOKEN_PREFIX = "fcm_token_fir_7eb9d_"
    }
}
