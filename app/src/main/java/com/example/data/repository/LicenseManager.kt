package com.example.data.repository

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.data.model.SipAccount
import com.example.data.model.SipTransport
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.TimeUnit

/** Whether this install may use the app; decided by the admin in Firestore devices/{uid}. */
sealed class LicenseState {
    data object Checking : LicenseState()
    /** No request sent yet: show the request form. */
    data object NeedsRequest : LicenseState()
    data class Pending(val name: String) : LicenseState()
    /** [expiresAt] null = no expiry. */
    data class Approved(val expiresAt: Date?) : LicenseState()
    /** Blocked or expired (Firestore rules then refuse to return the record). */
    data object Denied : LicenseState()
    /** Could not reach Firebase before we knew anything (first start offline, sign-in failed). */
    data class Error(val message: String) : LicenseState()
}

/**
 * Admin approval ("subscription") for the app.
 *
 * Every install signs in anonymously and gets its own uid. The app may only create
 * devices/{uid} with approved = false; the admin flips the boolean "approved" (a true/false
 * dropdown in the Firebase console, so no typos). The security rules deny reading the record once
 * it is expired, or unapproved while it holds SIP details, so a permission error means "no access".
 *
 * Subscription without a server: when the admin approves a record that has no expiresAt, the app
 * writes expiresAt = now + 30 days. When that date has passed, the app sets approved back to false
 * and removes expiresAt, so the next approval starts a new 30 days. The rules allow exactly these
 * two writes and nothing else. The admin can still set or extend expiresAt by hand.
 *
 * Optional SIP provisioning: when the approved record has sipUsername, sipPassword and
 * sipDomain (plus optional sipPort, sipDisplayName), that account is saved and made active, and
 * deleted again when access ends.
 */
class LicenseManager(
    private val context: Context,
    private val accountRepository: SipAccountRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences("license", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<LicenseState>(LicenseState.Checking)
    val state: StateFlow<LicenseState> = _state.asStateFlow()

    private var listener: ListenerRegistration? = null

    /** Signed in with the admin email: always allowed, and sees the Admin tab. */
    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val auth get() = FirebaseAuth.getInstance()
    private val firestore get() = FirebaseFirestore.getInstance()

    /** Starts (or restarts) watching this install's record. Safe to call on every app start. */
    fun start() {
        if (FirebaseApp.getApps(context).isEmpty()) {
            _state.value = LicenseState.Error("Firebase is not initialized")
            return
        }
        val user = auth.currentUser
        if (user != null && user.email.equals(ADMIN_EMAIL, ignoreCase = true)) {
            enterAdminMode()
            return
        }
        if (user != null) {
            listen(user.uid)
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { result -> result.user?.uid?.let { listen(it) } }
            .addOnFailureListener { e ->
                Log.w(TAG, "Anonymous sign-in failed: ${e.message}")
                _state.value = LicenseState.Error("No internet connection. Connect and try again.")
            }
    }

    /** Re-check, e.g. when the app comes to the foreground after being blocked or offline. */
    fun refresh() {
        subscriptionWriteTried = false
        expireWriteTried = false
        listener?.remove()
        listener = null
        start()
    }

    val installId: String? get() = auth.currentUser?.uid

    /**
     * Admin sign-in (Firebase email/password user created in the console). This replaces the
     * install's anonymous identity; signing out later needs a new access request.
     */
    fun signInAdmin(email: String, password: String, onResult: (String?) -> Unit) {
        if (!email.trim().equals(ADMIN_EMAIL, ignoreCase = true)) {
            onResult("This email is not the admin account")
            return
        }
        auth.signInWithEmailAndPassword(email.trim(), password)
            .addOnSuccessListener {
                enterAdminMode()
                onResult(null)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Admin sign-in failed: ${e.message}")
                onResult(e.localizedMessage ?: "Sign-in failed")
            }
    }

    fun signOutAdmin() {
        auth.signOut()
        _isAdmin.value = false
        prefs.edit().remove(KEY_WAS_APPROVED).apply()
        _state.value = LicenseState.Checking
        start()
    }

    private fun enterAdminMode() {
        listener?.remove()
        listener = null
        _isAdmin.value = true
        _state.value = LicenseState.Approved(null)
    }

    private fun listen(uid: String) {
        listener?.remove()
        listener = firestore.collection(COLLECTION).document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        onDenied()
                    } else {
                        Log.w(TAG, "License check failed: ${error.message}")
                        if (_state.value is LicenseState.Checking) {
                            _state.value = LicenseState.Error("No internet connection. Connect and try again.")
                        }
                    }
                    return@addSnapshotListener
                }
                snapshot?.let { onSnapshot(it) }
            }
    }

    private fun onSnapshot(doc: DocumentSnapshot) {
        if (!doc.exists()) {
            _state.value = LicenseState.NeedsRequest
            return
        }
        if (doc.getBoolean("approved") == true) {
            var expiresAt = doc.getTimestamp("expiresAt")?.toDate()
            if (expiresAt == null) {
                // Just approved: start the default subscription period
                expiresAt = startSubscription(doc.reference)
            }
            // Also checked here: an offline (cached) record would not hit the server rules
            if (expiresAt.before(Date())) {
                onDenied()
            } else {
                prefs.edit().putBoolean(KEY_WAS_APPROVED, true).apply()
                _state.value = LicenseState.Approved(expiresAt)
                provisionSipAccount(doc)
            }
        } else if (prefs.getBoolean(KEY_WAS_APPROVED, false)) {
            // Was approved before, now switched off: blocked, not a new request
            onDenied()
        } else {
            _state.value = LicenseState.Pending(doc.getString("name").orEmpty())
        }
    }

    private fun onDenied() {
        _state.value = LicenseState.Denied
        removeProvisionedAccount()
        markExpired()
    }

    private var subscriptionWriteTried = false
    private var expireWriteTried = false

    /** Writes expiresAt = now + [DEFAULT_DAYS] days; returns that date. */
    private fun startSubscription(ref: DocumentReference): Date {
        // A few minutes short of 30 days, so a phone clock slightly ahead still passes the rule
        val expiresAt = Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(DEFAULT_DAYS) - TimeUnit.MINUTES.toMillis(5))
        if (!subscriptionWriteTried) {
            subscriptionWriteTried = true
            ref.update(mapOf("expiresAt" to Timestamp(expiresAt), "approvedAt" to FieldValue.serverTimestamp()))
                .addOnSuccessListener { Log.i(TAG, "Subscription started, expires $expiresAt") }
                .addOnFailureListener { e -> Log.w(TAG, "Could not start subscription: ${e.message}") }
        }
        return expiresAt
    }

    /**
     * After expiry, switch approved back to false and clear expiresAt, so the record shows the
     * real state in the console and the next approval starts a new period. The rules accept this
     * only when expiresAt has really passed; for a blocked record it just fails.
     */
    private fun markExpired() {
        if (expireWriteTried) return
        expireWriteTried = true
        val uid = auth.currentUser?.uid ?: return
        firestore.collection(COLLECTION).document(uid)
            .update(mapOf("approved" to false, "expiresAt" to FieldValue.delete()))
            .addOnSuccessListener {
                Log.i(TAG, "Subscription expired: approved set to false")
                // Keep watching, so a new approval unlocks the app right away
                listen(uid)
            }
            .addOnFailureListener { Log.d(TAG, "No expiry to record (blocked or not yet expired)") }
    }

    /** Sends the access request. Only name, phone, device, approved (false) and createdAt are allowed by the rules. */
    fun submitRequest(name: String, phone: String, onResult: (String?) -> Unit) {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            onResult("Not signed in yet. Check the internet connection.")
            start()
            return
        }
        val data = mapOf(
            "name" to name.trim().take(60),
            "phone" to phone.trim().take(20),
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}".take(60),
            "approved" to false,
            "createdAt" to FieldValue.serverTimestamp()
        )
        firestore.collection(COLLECTION).document(uid).set(data)
            .addOnSuccessListener { onResult(null) }
            .addOnFailureListener { e ->
                Log.w(TAG, "Request failed: ${e.message}")
                onResult(e.localizedMessage ?: "Request failed")
            }
    }

    private fun provisionSipAccount(doc: DocumentSnapshot) {
        val username = doc.getString("sipUsername")?.trim().orEmpty()
        val password = doc.getString("sipPassword").orEmpty()
        val domain = doc.getString("sipDomain")?.trim().orEmpty()
        if (username.isEmpty() || password.isEmpty() || domain.isEmpty()) return
        val port = (doc.get("sipPort") as? Number)?.toInt() ?: 5060
        val displayName = doc.getString("sipDisplayName").orEmpty()

        scope.launch {
            val existingId = prefs.getLong(KEY_PROVISIONED_ID, -1L)
            val account = SipAccount(
                id = if (existingId > 0) existingId else 0L,
                username = username,
                password = password,
                domain = domain,
                port = port,
                transport = SipTransport.UDP,
                displayName = displayName
            )
            val current = accountRepository.getActiveAccountOneShot()
            val unchanged = current != null && current.id == existingId &&
                current.username == username && current.password == password &&
                current.domain == domain && current.port == port && current.displayName == displayName
            if (unchanged) return@launch
            val id = accountRepository.saveAccount(account, makeActive = true)
            prefs.edit().putLong(KEY_PROVISIONED_ID, if (existingId > 0) existingId else id).apply()
            Log.i(TAG, "SIP account provisioned by admin: $username@$domain")
        }
    }

    private fun removeProvisionedAccount() {
        val id = prefs.getLong(KEY_PROVISIONED_ID, -1L)
        if (id <= 0) return
        scope.launch {
            accountRepository.deleteAccountById(id)
            prefs.edit().remove(KEY_PROVISIONED_ID).apply()
            Log.i(TAG, "Provisioned SIP account removed (access ended)")
        }
    }

    companion object {
        private const val TAG = "LicenseManager"
        const val COLLECTION = "devices"
        /** Must match isAdmin() in firestore.rules. */
        const val ADMIN_EMAIL = "shaikatsaha93@gmail.com"
        private const val KEY_PROVISIONED_ID = "provisioned_account_id"
        private const val KEY_WAS_APPROVED = "was_approved"
        /** Subscription length when the admin approves without setting expiresAt. */
        private const val DEFAULT_DAYS = 30L
    }
}
