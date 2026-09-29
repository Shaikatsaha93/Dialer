package com.example.data.repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Admin panel data: watches all access records while the admin is signed in, performs
 * approve / renew / block / delete, and notifies about new requests. Firestore rules allow all of
 * this only for the admin email.
 */
class AdminManager(private val context: Context) : AdminService {

    private val firestore get() = FirebaseFirestore.getInstance()
    private val prefs = context.getSharedPreferences("admin", Context.MODE_PRIVATE)

    private val _devices = MutableStateFlow<List<DeviceRecord>>(emptyList())
    override val devices: StateFlow<List<DeviceRecord>> = _devices.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    private var listener: ListenerRegistration? = null

    override fun startWatching() {
        if (listener != null) return
        createNotificationChannel()
        listener = firestore.collection(LicenseManager.COLLECTION)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w(TAG, "Device list failed: ${e.message}")
                    _error.value = "Cannot load requests: ${e.localizedMessage}"
                    return@addSnapshotListener
                }
                _error.value = null
                val records = snapshot?.documents.orEmpty().map { it.toRecord() }
                    // Pending first, then newest
                    .sortedWith(
                        compareBy<DeviceRecord> { it.status != DeviceStatus.PENDING }
                            .thenByDescending { it.createdAt ?: 0L }
                    )
                _devices.value = records
                notifyNewRequests(records)
            }
    }

    override fun stopWatching() {
        listener?.remove()
        listener = null
        _devices.value = emptyList()
    }

    /** Approves for [days] days from today. */
    override fun approve(id: String, days: Int) {
        update(
            id,
            mapOf(
                "approved" to true,
                "expiresAt" to Timestamp(daysFrom(Date(), days)),
                "approvedAt" to FieldValue.serverTimestamp()
            )
        )
    }

    /** Adds [days] to the current expiry, or to today if it already passed. */
    override fun extend(record: DeviceRecord, days: Int) {
        val now = Date()
        val base = record.expiresAt?.let { Date(it) }?.takeIf { it.after(now) } ?: now
        update(
            record.id,
            mapOf(
                "approved" to true,
                "expiresAt" to Timestamp(daysFrom(base, days)),
                "approvedAt" to (record.approvedAt?.let { Timestamp(Date(it)) } ?: FieldValue.serverTimestamp())
            )
        )
    }

    /** No time limit (far-future date, because a missing expiresAt starts a new 30 days). */
    override fun approveWithoutLimit(id: String) {
        val farFuture = Calendar.getInstance().apply { set(2099, Calendar.DECEMBER, 31) }.time
        update(
            id,
            mapOf(
                "approved" to true,
                "expiresAt" to Timestamp(farFuture),
                "approvedAt" to FieldValue.serverTimestamp()
            )
        )
    }

    override fun block(id: String) = update(id, mapOf("approved" to false))

    override fun delete(id: String) {
        firestore.collection(LicenseManager.COLLECTION).document(id).delete()
            .addOnFailureListener { e -> _error.value = "Delete failed: ${e.localizedMessage}" }
    }

    private fun update(id: String, data: Map<String, Any>) {
        firestore.collection(LicenseManager.COLLECTION).document(id).update(data)
            .addOnFailureListener { e ->
                Log.w(TAG, "Update failed: ${e.message}")
                _error.value = "Update failed: ${e.localizedMessage}"
            }
    }

    private fun daysFrom(base: Date, days: Int) = Date(base.time + TimeUnit.DAYS.toMillis(days.toLong()))

    private fun DocumentSnapshot.toRecord() = DeviceRecord(
        id = id,
        name = getString("name").orEmpty(),
        phone = getString("phone").orEmpty(),
        device = getString("device").orEmpty(),
        approved = getBoolean("approved") == true,
        expiresAt = getTimestamp("expiresAt")?.toDate()?.time,
        createdAt = getTimestamp("createdAt")?.toDate()?.time,
        approvedAt = getTimestamp("approvedAt")?.toDate()?.time,
        hasSipAccount = contains("sipUsername")
    )

    /** One notification per new pending request (remembered across restarts). */
    private fun notifyNewRequests(records: List<DeviceRecord>) {
        val notified = prefs.getStringSet(KEY_NOTIFIED, emptySet()).orEmpty()
        val fresh = records.filter { it.status == DeviceStatus.PENDING && it.id !in notified }
        if (fresh.isEmpty()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openApp = PendingIntent.getActivity(
            context,
            20,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_ADMIN, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fresh.forEach { record ->
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("New access request")
                .setContentText("${record.name.ifBlank { "Unknown" }} · ${record.phone} · ${record.device}")
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            try {
                manager.notify(record.id.hashCode(), notification)
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification permission missing: ${e.message}")
            }
        }
        prefs.edit().putStringSet(KEY_NOTIFIED, notified + fresh.map { it.id }).apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Access requests (admin)", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "A new device asks for access to the app" }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "AdminManager"
        private const val CHANNEL_ID = "admin_access_requests"
        private const val KEY_NOTIFIED = "notified_request_ids"
        const val DEFAULT_DAYS = AdminService.DEFAULT_DAYS
    }
}
