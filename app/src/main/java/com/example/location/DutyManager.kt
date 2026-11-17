package com.example.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.repository.DutyService
import com.example.data.repository.DutyState
import com.example.data.repository.LicenseManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * "On duty" for delivery riders: while on, [LocationService] sends the phone's location to the
 * Realtime Database (live/{company}/{uid}, plus the day's route in tracks/), where the company's
 * dispatcher sees it on the web dashboard. Only riders of a company (who joined with its code and
 * were approved) get the switch; the database rules accept their locations only while the
 * company admin keeps them in members/{company}.
 *
 * The service is started from the app in the foreground, so it needs only the "while using the
 * app" location permission. On duty is remembered and resumed when the app is opened again
 * (e.g. after a reboot).
 */
class DutyManager(
    private val context: Context,
    private val license: LicenseManager
) : DutyService {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences("duty", Context.MODE_PRIVATE)

    private val onDuty = MutableStateFlow(prefs.getBoolean(KEY_ON_DUTY, false))

    /** Reported by the running service: last location sent, or why it cannot send */
    internal val serviceStatus = MutableStateFlow<DutyState>(DutyState.On(null))

    override val state: StateFlow<DutyState> =
        combine(license.rider, onDuty, serviceStatus) { rider, on, status ->
            when {
                rider == null -> DutyState.Unavailable
                !on -> DutyState.Off
                else -> status
            }
        }.stateIn(scope, SharingStarted.Eagerly, DutyState.Unavailable)

    val rider get() = license.rider.value

    init {
        try {
            // Locations taken offline are kept on the phone and sent when the network is back
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
        } catch (e: Exception) {
            Log.w(TAG, "Database persistence: ${e.message}")
        }
        // Access ended (blocked, expired, left the company): stop sharing. Approved (again), or
        // known only now after the app started: carry on if the rider was on duty.
        scope.launch {
            license.rider.collect { rider ->
                if (rider == null) {
                    context.stopService(Intent(context, LocationService::class.java))
                } else if (onDuty.value) {
                    startService()
                }
            }
        }
    }

    override fun setOnDuty(on: Boolean) {
        onDuty.value = on
        prefs.edit().putBoolean(KEY_ON_DUTY, on).apply()
        if (on) {
            startService()
        } else {
            context.stopService(Intent(context, LocationService::class.java))
            markOffDuty()
        }
    }

    /** Called when the app comes to the foreground: picks up "on duty" after a restart. */
    fun resumeIfOnDuty() {
        if (onDuty.value && license.rider.value != null) startService()
    }

    private fun startService() {
        if (license.rider.value == null) return
        if (!hasLocationPermission()) {
            serviceStatus.value = DutyState.Problem("Allow location for Dialer to go on duty")
            return
        }
        serviceStatus.value = DutyState.On(null)
        try {
            ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))
        } catch (e: Exception) {
            // Android refuses a location service started from the background
            Log.w(TAG, "Cannot start location service: ${e.message}")
            serviceStatus.value = DutyState.Problem("Open Dialer to resume sharing the location")
        }
    }

    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Shows the rider as off duty on the dashboard (keeps the last position). */
    private fun markOffDuty() {
        val rider = license.rider.value ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseDatabase.getInstance().getReference("live/${rider.companyId}/$uid")
            .updateChildren(mapOf("onDuty" to false, "ts" to ServerValue.TIMESTAMP))
            .addOnFailureListener { Log.d(TAG, "Off duty not recorded: ${it.message}") }
    }

    companion object {
        private const val TAG = "DutyManager"
        private const val KEY_ON_DUTY = "on_duty"
    }
}
