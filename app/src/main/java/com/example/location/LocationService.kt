package com.example.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.location.LocationManagerCompat
import com.example.MainActivity
import com.example.SoftphoneApp
import com.example.data.repository.DutyState
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs while a delivery rider is on duty (see [DutyManager]). Takes a location fix about every
 * 15 seconds and sends it to live/{company}/{uid} when the rider moved 15 m or 2 minutes passed,
 * so the dashboard shows the rider moving and still knows a standing rider is online. Every
 * 30 m (or 5 minutes) a point is also added to the day's route, tracks/{company}/{uid}/{day}.
 */
class LocationService : Service() {

    private val duty get() = SoftphoneApp.instance.dutyManager
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var lastSent: Location? = null
    private var lastSentAt = 0L
    private var lastTrack: Location? = null
    private var lastTrackAt = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onLocation)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_GO_OFF_DUTY) {
            duty.setOnDuty(false)
            return START_NOT_STICKY
        }
        val rider = duty.rider
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(rider?.companyId),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            )
        } catch (e: Exception) {
            // No location permission, or started while the app was in the background (Android
            // allows location only from a service started while the app is open)
            Log.w(TAG, "Cannot run in the foreground: ${e.message}")
            duty.serviceStatus.value = DutyState.Problem(
                if (duty.hasLocationPermission()) "Open Dialer to resume sharing your location"
                else "Allow location for Dialer to go on duty"
            )
            stopSelf()
            return START_NOT_STICKY
        }
        if (rider == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isLocationOn()) {
            duty.serviceStatus.value = DutyState.Problem("Turn on Location (GPS) in the phone settings")
        }
        startUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission") // checked by DutyManager before starting; failure is caught
    private fun startUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .build()
        try {
            client.removeLocationUpdates(callback)
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            duty.serviceStatus.value = DutyState.Problem("Allow location for Dialer to go on duty")
            stopSelf()
        }
    }

    private fun onLocation(location: Location) {
        val rider = duty.rider ?: return stopSelf()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val now = System.currentTimeMillis()
        val db = FirebaseDatabase.getInstance()
        duty.lastFix = location

        val moved = lastSent?.distanceTo(location) ?: Float.MAX_VALUE
        Log.d(TAG, "Fix ±${location.accuracy.toInt()} m, moved ${if (lastSent == null) "-" else moved.toInt()} m")
        if (moved >= SEND_DISTANCE_M || now - lastSentAt >= SEND_INTERVAL_MS) {
            lastSent = location
            lastSentAt = now
            val battery = batteryStatus()
            val data = mutableMapOf<String, Any>(
                "lat" to location.latitude,
                "lng" to location.longitude,
                "ts" to ServerValue.TIMESTAMP,
                "onDuty" to true,
                "name" to rider.name.take(60),
                "phone" to rider.phone.take(20)
            )
            if (location.hasAccuracy()) data["accuracy"] = location.accuracy.toDouble()
            if (location.hasSpeed()) data["speed"] = location.speed.toDouble()
            if (location.hasBearing()) data["bearing"] = location.bearing.toDouble()
            battery?.let { (level, charging) ->
                data["battery"] = level
                data["charging"] = charging
            }
            db.getReference("live/${rider.companyId}/$uid").setValue(data) { error, _ ->
                duty.serviceStatus.value = if (error == null) {
                    DutyState.On(System.currentTimeMillis())
                } else {
                    Log.w(TAG, "Location not saved: ${error.message}")
                    DutyState.Problem(
                        if (error.code == DatabaseError.PERMISSION_DENIED) {
                            "Your company admin has not allowed location sharing for you yet"
                        } else {
                            "Cannot send the location: ${error.message}"
                        }
                    )
                }
            }
        }

        val trackMoved = lastTrack?.distanceTo(location) ?: Float.MAX_VALUE
        if (trackMoved >= TRACK_DISTANCE_M || now - lastTrackAt >= TRACK_INTERVAL_MS) {
            lastTrack = location
            lastTrackAt = now
            val point = mutableMapOf<String, Any>("lat" to location.latitude, "lng" to location.longitude, "t" to now)
            if (location.hasSpeed()) point["speed"] = location.speed.toDouble()
            db.getReference("tracks/${rider.companyId}/$uid/${dayKey(now)}").push().setValue(point)
        }
    }

    /** Battery percent and whether it is charging, shown to the dispatcher */
    private fun batteryStatus(): Pair<Int, Boolean>? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return (level * 100 / scale) to charging
    }

    private fun isLocationOn(): Boolean {
        val manager = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return true
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    private fun buildNotification(company: String?): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "On duty (location sharing)", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while your location is shared with your company"
                    setShowBadge(false)
                }
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 30,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val goOffDuty = PendingIntent.getService(
            this, 31,
            Intent(this, LocationService::class.java).setAction(ACTION_GO_OFF_DUTY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("On duty")
            .setContentText("Sharing your location" + (company?.let { " with $it" } ?: ""))
            .setContentIntent(openApp)
            .addAction(0, "Go off duty", goOffDuty)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LocationService"
        private const val CHANNEL_ID = "duty_location"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_GO_OFF_DUTY = "com.example.location.GO_OFF_DUTY"

        private const val UPDATE_INTERVAL_MS = 15_000L
        private const val MIN_UPDATE_INTERVAL_MS = 10_000L
        private const val SEND_DISTANCE_M = 15f
        private const val SEND_INTERVAL_MS = 2 * 60_000L
        private const val TRACK_DISTANCE_M = 30f
        private const val TRACK_INTERVAL_MS = 5 * 60_000L

        /** The route of one local day: tracks/{company}/{uid}/2026-11-17 */
        fun dayKey(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
    }
}
