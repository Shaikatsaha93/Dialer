package com.example.data.repository

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Call recordings, stored only on this phone in the app's folder
 * (Android/data/<package>/files/Recordings). They are deleted when the app is uninstalled.
 */
object CallRecordings {

    fun directory(context: Context): File =
        (context.getExternalFilesDir("Recordings") ?: File(context.filesDir, "Recordings")).apply { mkdirs() }

    /** New file for a call, e.g. 2026-09-27_14-30-05_01712345678_OUT.wav */
    fun newFile(context: Context, number: String, incoming: Boolean): File {
        val time = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val safeNumber = number.removePrefix("sip:").substringBefore("@").replace(Regex("[^0-9A-Za-z+*#]"), "")
        return File(directory(context), "${time}_${safeNumber.ifEmpty { "unknown" }}_${if (incoming) "IN" else "OUT"}.wav")
    }

    /** Newest first; skips empty files left by calls that never connected. */
    fun list(context: Context): List<File> =
        directory(context).listFiles { f -> f.isFile && f.extension == "wav" && f.length() > 44 }
            .orEmpty()
            .sortedByDescending { it.lastModified() }

    /** Removes files of calls that ended before any audio was recorded. */
    fun cleanUpEmpty(context: Context) {
        directory(context).listFiles { f -> f.isFile && f.length() <= 44 }?.forEach { it.delete() }
    }
}
