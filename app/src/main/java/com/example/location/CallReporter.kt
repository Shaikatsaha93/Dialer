package com.example.location

import android.util.Log
import com.example.sip.CallEvent
import com.example.sip.result
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sends each call of a delivery company's rider to the company's call report:
 * calls/{company}/{uid}/{yyyy-MM-dd}/{id} in the Realtime Database, read on the web dashboard.
 * Number, direction, result, start, talk time and whether the rider was on duty; the location
 * only while on duty (riders are told that off duty their location is not shared).
 *
 * Other users (no company code) report nothing. Calls made offline are queued by the database
 * and sent when the network is back.
 */
class CallReporter(private val duty: DutyManager) {

    private var startedAt = 0L

    fun onCallStarted() {
        startedAt = System.currentTimeMillis()
    }

    fun onCallEnded(event: CallEvent.CallEnded, displayName: String) {
        val rider = duty.rider ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val now = System.currentTimeMillis()
        val start = startedAt.takeIf { it in 1..now } ?: (now - event.durationSeconds * 1000)
        startedAt = 0L

        val number = event.remoteUri.removePrefix("sip:").removePrefix("sips:")
            .substringBefore("@").substringBefore(";").take(40)
        val data = mutableMapOf<String, Any>(
            "number" to number,
            "dir" to if (event.isIncoming) "in" else "out",
            "result" to event.result.key,
            "start" to start,
            "talk" to event.durationSeconds.coerceIn(0, 24 * 3600),
            "onDuty" to duty.isOnDuty
        )
        displayName.takeIf { it.isNotBlank() && it != number && !it.startsWith("sip:") }?.let { data["name"] = it.take(60) }
        if (event.sipCode in 300..699) data["code"] = event.sipCode
        // Where the rider was: the service's latest fix, if recent
        val fix = duty.lastFix
        if (duty.isOnDuty && fix != null && now - fix.time < MAX_FIX_AGE_MS) {
            data["lat"] = fix.latitude
            data["lng"] = fix.longitude
            if (fix.hasAccuracy()) data["accuracy"] = fix.accuracy.toDouble()
        }

        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(start))
        FirebaseDatabase.getInstance().getReference("calls/${rider.companyId}/$uid/$day").push().setValue(data)
            .addOnFailureListener { Log.w(TAG, "Call not reported: ${it.message}") }
    }

    companion object {
        private const val TAG = "CallReporter"
        private const val MAX_FIX_AGE_MS = 3 * 60 * 1000L
    }
}
