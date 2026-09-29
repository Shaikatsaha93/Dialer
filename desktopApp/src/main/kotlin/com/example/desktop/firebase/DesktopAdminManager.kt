package com.example.desktop.firebase

import com.example.data.repository.AdminService
import com.example.data.repository.DeviceRecord
import com.example.data.repository.DeviceStatus
import com.example.data.repository.KeyValueStore
import com.example.data.repository.LicenseService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Admin panel for Windows: reads all access records every 15 seconds while the admin is signed
 * in, and approves / renews / blocks / deletes them (the rules allow this only for the admin).
 */
class DesktopAdminManager(
    private val firebase: FirebaseRest,
    private val license: DesktopLicenseManager,
    private val prefs: KeyValueStore,
    private val scope: CoroutineScope,
    /** Shows a Windows notification (title, text) for a new access request. */
    private val notify: (String, String) -> Unit
) : AdminService {

    private val _devices = MutableStateFlow<List<DeviceRecord>>(emptyList())
    override val devices: StateFlow<List<DeviceRecord>> = _devices.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    private var watchJob: Job? = null

    override fun startWatching() {
        if (watchJob != null) return
        watchJob = scope.launch {
            while (isActive) {
                reload()
                delay(15_000)
            }
        }
    }

    override fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
        _devices.value = emptyList()
    }

    private suspend fun reload() {
        val token = license.adminToken() ?: return
        try {
            val records = firebase.listDocuments(token, LicenseService.COLLECTION).map { (id, f) ->
                DeviceRecord(
                    id = id,
                    name = f["name"] as? String ?: "",
                    phone = f["phone"] as? String ?: "",
                    device = f["device"] as? String ?: "",
                    approved = f["approved"] == true,
                    expiresAt = (f["expiresAt"] as? Instant)?.toEpochMilli(),
                    createdAt = (f["createdAt"] as? Instant)?.toEpochMilli(),
                    approvedAt = (f["approvedAt"] as? Instant)?.toEpochMilli(),
                    hasSipAccount = !(f["sipUsername"] as? String).isNullOrBlank()
                )
            }.sortedWith(
                compareBy<DeviceRecord> { it.status != DeviceStatus.PENDING }.thenByDescending { it.createdAt ?: 0L }
            )
            _error.value = null
            _devices.value = records
            notifyNewRequests(records)
        } catch (e: Exception) {
            _error.value = "Cannot load requests: ${e.message}"
        }
    }

    override fun approve(id: String, days: Int) = update(
        id,
        mapOf("approved" to true, "expiresAt" to Instant.ofEpochMilli(System.currentTimeMillis() + days * AdminService.DAY_MILLIS)),
        serverTime = listOf("approvedAt")
    )

    override fun extend(record: DeviceRecord, days: Int) {
        val now = System.currentTimeMillis()
        val base = record.expiresAt?.takeIf { it > now } ?: now
        val fields = mutableMapOf<String, Any?>("approved" to true, "expiresAt" to Instant.ofEpochMilli(base + days * AdminService.DAY_MILLIS))
        record.approvedAt?.let { fields["approvedAt"] = Instant.ofEpochMilli(it) }
        update(record.id, fields, serverTime = if (record.approvedAt == null) listOf("approvedAt") else emptyList())
    }

    override fun approveWithoutLimit(id: String) = update(
        id,
        mapOf("approved" to true, "expiresAt" to LocalDate.of(2099, 12, 31).atStartOfDay().toInstant(ZoneOffset.UTC)),
        serverTime = listOf("approvedAt")
    )

    override fun block(id: String) = update(id, mapOf("approved" to false))

    override fun delete(id: String) {
        scope.launch {
            val token = license.adminToken() ?: return@launch
            try {
                firebase.deleteDocument(token, "${LicenseService.COLLECTION}/$id")
                reload()
            } catch (e: Exception) {
                _error.value = "Delete failed: ${e.message}"
            }
        }
    }

    private fun update(id: String, fields: Map<String, Any?>, serverTime: List<String> = emptyList()) {
        scope.launch {
            val token = license.adminToken() ?: return@launch
            try {
                firebase.write(
                    token, "${LicenseService.COLLECTION}/$id",
                    fields = fields,
                    mask = fields.keys.toList(),
                    serverTimeFields = serverTime,
                    mustExist = true
                )
                reload()
            } catch (e: Exception) {
                _error.value = "Update failed: ${e.message}"
            }
        }
    }

    private fun notifyNewRequests(records: List<DeviceRecord>) {
        val notified = prefs.getString(KEY_NOTIFIED, "").orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
        val fresh = records.filter { it.status == DeviceStatus.PENDING && it.id !in notified }
        if (fresh.isEmpty()) return
        fresh.forEach { notify("New access request", "${it.name.ifBlank { "Someone" }} (${it.phone}) · ${it.device}") }
        prefs.putString(KEY_NOTIFIED, (notified + fresh.map { it.id }).joinToString(","))
    }

    companion object {
        private const val KEY_NOTIFIED = "admin_notified_requests"
    }
}
