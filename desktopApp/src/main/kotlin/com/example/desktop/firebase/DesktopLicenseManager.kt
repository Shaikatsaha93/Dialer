package com.example.desktop.firebase

import com.example.data.model.SipAccount
import com.example.data.model.SipTransport
import com.example.data.repository.KeyValueStore
import com.example.data.repository.LicenseService
import com.example.data.repository.LicenseState
import com.example.data.repository.SipAccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import java.time.Instant

/** The Firebase project the Android app uses (same values as in FcmTokenManager). */
object DesktopFirebaseConfig {
    const val API_KEY = "AIzaSyD2dyTMFtr-0aKqo_4VnVbuOnVjk4deAvY"
    const val PROJECT_ID = "fir-7eb9d"
}

/**
 * Admin approval for the Windows app. Same model and Firestore rules as LicenseManager on
 * Android: an anonymous Firebase user per install, a devices/{uid} request, 30 days from the
 * approval, then approved switches back to false. Firestore has no REST listener, so the record
 * is re-read every 30 seconds (and right away on refresh()).
 */
class DesktopLicenseManager(
    private val firebase: FirebaseRest,
    private val prefs: KeyValueStore,
    private val accountRepository: SipAccountRepository,
    private val scope: CoroutineScope
) : LicenseService {

    private val _state = MutableStateFlow<LicenseState>(LicenseState.Checking)
    override val state: StateFlow<LicenseState> = _state.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    override val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val sessionLock = Mutex()
    private var session: FirebaseRest.Session? = null
    private var pollJob: Job? = null
    private var subscriptionWriteTried = false
    private var expireWriteTried = false

    override val installId: String? get() = session?.uid ?: prefs.getString(KEY_UID, null)

    fun start() {
        pollJob?.cancel()
        pollJob = scope.launch {
            val s = try {
                currentSession()
            } catch (e: Exception) {
                _state.value = LicenseState.Error("No internet connection. Connect and try again.")
                return@launch
            }
            if (s.email.equals(LicenseService.ADMIN_EMAIL, ignoreCase = true)) {
                enterAdminMode()
                return@launch
            }
            while (isActive) {
                check()
                delay(POLL_MILLIS)
            }
        }
    }

    override fun refresh() {
        subscriptionWriteTried = false
        expireWriteTried = false
        start()
    }

    /** A valid ID token: the saved session (refreshed when needed) or a new anonymous user. */
    private suspend fun currentSession(): FirebaseRest.Session = sessionLock.withLock {
        session?.takeIf { it.expiresAtMillis - System.currentTimeMillis() > 60_000 }?.let { return it }
        val saved = session?.refreshToken ?: prefs.getString(KEY_REFRESH_TOKEN, null)
        val email = session?.email ?: prefs.getString(KEY_EMAIL, null)
        val fresh = if (saved != null) {
            try {
                firebase.refresh(saved, email)
            } catch (e: FirebaseRest.FirebaseException) {
                // Token revoked or user deleted: start over as a new install
                if (e.status in 400..499) firebase.signInAnonymously() else throw e
            }
        } else {
            firebase.signInAnonymously()
        }
        save(fresh)
        fresh
    }

    private fun save(s: FirebaseRest.Session) {
        session = s
        prefs.putString(KEY_REFRESH_TOKEN, s.refreshToken)
        prefs.putString(KEY_UID, s.uid)
        if (s.email != null) prefs.putString(KEY_EMAIL, s.email) else prefs.remove(KEY_EMAIL)
    }

    private suspend fun check() {
        try {
            val s = currentSession()
            val doc = firebase.getDocument(s.idToken, "${LicenseService.COLLECTION}/${s.uid}")
            if (doc == null) {
                _state.value = LicenseState.NeedsRequest
                return
            }
            onRecord(s, doc)
        } catch (e: FirebaseRest.FirebaseException) {
            if (e.status == 403 || e.code == "PERMISSION_DENIED") {
                onDenied()
            } else if (_state.value is LicenseState.Checking) {
                _state.value = LicenseState.Error("Could not check access: ${e.message}")
            }
        } catch (e: Exception) {
            if (_state.value is LicenseState.Checking) {
                _state.value = LicenseState.Error("No internet connection. Connect and try again.")
            }
        }
    }

    private suspend fun onRecord(s: FirebaseRest.Session, doc: Map<String, Any?>) {
        if (doc["approved"] == true) {
            var expiresAt = (doc["expiresAt"] as? Instant)?.toEpochMilli()
            if (expiresAt == null) expiresAt = startSubscription(s)
            if (expiresAt < System.currentTimeMillis()) {
                onDenied()
            } else {
                prefs.putBoolean(KEY_WAS_APPROVED, true)
                _state.value = LicenseState.Approved(expiresAt)
                provisionSipAccount(doc)
            }
        } else if (prefs.getBoolean(KEY_WAS_APPROVED, false)) {
            onDenied()
        } else {
            _state.value = LicenseState.Pending(doc["name"] as? String ?: "")
        }
    }

    /** Just approved without a date: expiresAt = now + 30 days (a few minutes short, for clock skew). */
    private suspend fun startSubscription(s: FirebaseRest.Session): Long {
        val expiresAt = System.currentTimeMillis() + LicenseService.DEFAULT_DAYS * DAY_MILLIS - 5 * 60_000
        if (!subscriptionWriteTried) {
            subscriptionWriteTried = true
            runCatching {
                firebase.write(
                    s.idToken, "${LicenseService.COLLECTION}/${s.uid}",
                    fields = mapOf("expiresAt" to Instant.ofEpochMilli(expiresAt)),
                    mask = listOf("expiresAt"),
                    serverTimeFields = listOf("approvedAt"),
                    mustExist = true
                )
            }
        }
        return expiresAt
    }

    private suspend fun onDenied() {
        _state.value = LicenseState.Denied
        removeProvisionedAccount()
        markExpired()
    }

    /** After expiry: approved = false and no expiresAt, so the next approval starts 30 new days. */
    private suspend fun markExpired() {
        if (expireWriteTried) return
        expireWriteTried = true
        val s = session ?: return
        runCatching {
            firebase.write(
                s.idToken, "${LicenseService.COLLECTION}/${s.uid}",
                fields = mapOf("approved" to false),
                mask = listOf("approved", "expiresAt"),
                mustExist = true
            )
        }
    }

    override fun submitRequest(name: String, phone: String, onResult: (String?) -> Unit) {
        scope.launch {
            val result = try {
                val s = currentSession()
                firebase.write(
                    s.idToken, "${LicenseService.COLLECTION}/${s.uid}",
                    fields = mapOf(
                        "name" to name.trim().take(60),
                        "phone" to phone.trim().take(20),
                        "device" to deviceName(),
                        "approved" to false
                    ),
                    serverTimeFields = listOf("createdAt"),
                    mustExist = false
                )
                null
            } catch (e: Exception) {
                e.message ?: "Request failed"
            }
            onResult(result)
            if (result == null) refresh()
        }
    }

    override fun signInAdmin(email: String, password: String, onResult: (String?) -> Unit) {
        if (!email.trim().equals(LicenseService.ADMIN_EMAIL, ignoreCase = true)) {
            onResult("This email is not the admin account")
            return
        }
        scope.launch {
            try {
                val s = firebase.signInWithPassword(email.trim(), password)
                sessionLock.withLock { save(s) }
                enterAdminMode()
                onResult(null)
            } catch (e: FirebaseRest.FirebaseException) {
                onResult(
                    when {
                        e.message.orEmpty().contains("INVALID") -> "Wrong email or password"
                        else -> e.message ?: "Sign-in failed"
                    }
                )
            } catch (e: Exception) {
                onResult("No internet connection")
            }
        }
    }

    override fun signOutAdmin() {
        pollJob?.cancel()
        session = null
        prefs.remove(KEY_REFRESH_TOKEN)
        prefs.remove(KEY_EMAIL)
        prefs.remove(KEY_UID)
        prefs.remove(KEY_WAS_APPROVED)
        _isAdmin.value = false
        _state.value = LicenseState.Checking
        start()
    }

    private fun enterAdminMode() {
        pollJob?.cancel()
        _isAdmin.value = true
        _state.value = LicenseState.Approved(null)
    }

    /** A valid admin ID token for the admin panel. */
    suspend fun adminToken(): String? =
        if (_isAdmin.value) runCatching { currentSession().idToken }.getOrNull() else null

    private fun provisionSipAccount(doc: Map<String, Any?>) {
        val username = (doc["sipUsername"] as? String)?.trim().orEmpty()
        val password = (doc["sipPassword"] as? String).orEmpty()
        val domain = (doc["sipDomain"] as? String)?.trim().orEmpty()
        if (username.isEmpty() || password.isEmpty() || domain.isEmpty()) return
        val port = (doc["sipPort"] as? Number)?.toInt() ?: 5060
        val displayName = (doc["sipDisplayName"] as? String).orEmpty()
        scope.launch {
            val existingId = prefs.getLong(KEY_PROVISIONED_ID, -1L)
            val current = accountRepository.getActiveAccountOneShot()
            val unchanged = current != null && current.id == existingId && current.username == username &&
                current.password == password && current.domain == domain && current.port == port &&
                current.displayName == displayName
            if (unchanged) return@launch
            val id = accountRepository.saveAccount(
                SipAccount(
                    id = if (existingId > 0) existingId else 0L,
                    username = username,
                    password = password,
                    domain = domain,
                    port = port,
                    transport = SipTransport.UDP,
                    displayName = displayName
                ),
                makeActive = true
            )
            prefs.putLong(KEY_PROVISIONED_ID, if (existingId > 0) existingId else id)
        }
    }

    private suspend fun removeProvisionedAccount() {
        val id = prefs.getLong(KEY_PROVISIONED_ID, -1L)
        if (id <= 0) return
        accountRepository.deleteAccountById(id)
        prefs.remove(KEY_PROVISIONED_ID)
    }

    private fun deviceName(): String {
        val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
        return ("Windows PC" + (host?.let { " ($it)" } ?: "")).take(60)
    }

    companion object {
        private const val KEY_REFRESH_TOKEN = "license_refresh_token"
        private const val KEY_UID = "license_uid"
        private const val KEY_EMAIL = "license_email"
        private const val KEY_WAS_APPROVED = "license_was_approved"
        private const val KEY_PROVISIONED_ID = "license_provisioned_account_id"
        private const val POLL_MILLIS = 30_000L
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
