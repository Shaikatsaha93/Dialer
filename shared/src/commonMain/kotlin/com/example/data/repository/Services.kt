package com.example.data.repository

import com.example.data.model.PhoneContact
import com.example.data.model.PushMessageItem
import com.example.platform.currentTimeMillis
import kotlinx.coroutines.flow.StateFlow

/*
 * Platform services the shared UI talks to. Each app provides its own implementation:
 * Android uses the Firebase SDKs and the phone's contacts, Windows uses the Firebase REST API.
 */

/** Whether this install may use the app; decided by the admin in Firestore devices/{uid}. */
sealed class LicenseState {
    data object Checking : LicenseState()
    /** No request sent yet: show the request form. */
    data object NeedsRequest : LicenseState()
    data class Pending(val name: String) : LicenseState()
    /** [expiresAt] in epoch millis; null = no expiry. */
    data class Approved(val expiresAt: Long?) : LicenseState()
    /** Blocked or expired (Firestore rules then refuse to return the record). */
    data object Denied : LicenseState()
    /** Could not reach Firebase before we knew anything (first start offline, sign-in failed). */
    data class Error(val message: String) : LicenseState()
}

/** Admin approval of this install (see LicenseManager on Android for the full model). */
interface LicenseService {
    val state: StateFlow<LicenseState>
    val isAdmin: StateFlow<Boolean>
    /** This install's id (Firebase uid), shown to the user so the admin can find the request. */
    val installId: String?
    fun refresh()
    /** Sends an access request; [onResult] gets null on success or an error text. */
    fun submitRequest(name: String, phone: String, onResult: (String?) -> Unit)
    fun signInAdmin(email: String, password: String, onResult: (String?) -> Unit)
    fun signOutAdmin()

    companion object {
        const val COLLECTION = "devices"
        const val ADMIN_EMAIL = "shaikatsaha93@gmail.com"
        const val DEFAULT_DAYS = 30L
    }
}

enum class DeviceStatus { PENDING, ACTIVE, EXPIRED, DISABLED }

/** One install's access record (devices/{id}) as the admin sees it. Times are epoch millis. */
data class DeviceRecord(
    val id: String,
    val name: String,
    val phone: String,
    val device: String,
    val approved: Boolean,
    val expiresAt: Long?,
    val createdAt: Long?,
    val approvedAt: Long?,
    val hasSipAccount: Boolean
) {
    val status: DeviceStatus
        get() = when {
            approved && expiresAt != null && expiresAt < currentTimeMillis() -> DeviceStatus.EXPIRED
            approved -> DeviceStatus.ACTIVE
            // Never approved before = a new request; otherwise switched off or expired
            approvedAt == null -> DeviceStatus.PENDING
            else -> DeviceStatus.DISABLED
        }
}

/** Admin panel: all access records plus approve / renew / block / delete. */
interface AdminService {
    val devices: StateFlow<List<DeviceRecord>>
    val error: StateFlow<String?>
    fun startWatching()
    fun stopWatching()
    fun approve(id: String, days: Int = DEFAULT_DAYS)
    fun extend(record: DeviceRecord, days: Int)
    fun approveWithoutLimit(id: String)
    fun block(id: String)
    fun delete(id: String)

    companion object {
        const val DEFAULT_DAYS = 30
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

/** Address book: the phone's contacts on Android, none on Windows (yet). */
interface ContactsSource {
    val contacts: StateFlow<List<PhoneContact>>
    val isLoading: StateFlow<Boolean>
    fun hasContactsPermission(): Boolean
    suspend fun loadContacts()
    fun findContactName(rawNumberOrUri: String): String?
}

/** Push token (FCM on Android) and the list of received pushes shown in Settings. */
interface PushSource {
    val fcmToken: StateFlow<String?>
    val tokenStatus: StateFlow<String>
    val receivedPushes: StateFlow<List<PushMessageItem>>
    fun refreshToken()
    fun clearPushHistory()
    fun recordPushMessage(item: PushMessageItem)
}

/** Shows a system notification for a new chat message; opening it opens [conversationId]. */
fun interface ChatNotifier {
    fun notify(conversationId: String, title: String, text: String)
}

/** Small persistent key-value storage (SharedPreferences on Android, Preferences on Windows). */
interface KeyValueStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getString(key: String, default: String?): String?
    fun putBoolean(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
    fun putLong(key: String, value: Long)
    fun putString(key: String, value: String?)
    fun remove(key: String)
}
