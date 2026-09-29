package com.example.data.repository

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Call recordings on this computer, in Documents\Dialer\Recordings. */
object CallRecordings {

    fun directory(): File =
        File(System.getProperty("user.home"), "Documents${File.separator}Dialer${File.separator}Recordings").apply { mkdirs() }

    /** New file for a call, e.g. 2026-09-27_14-30-05_01712345678_OUT.wav */
    fun newFile(number: String, incoming: Boolean): File {
        val time = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val safeNumber = number.removePrefix("sip:").substringBefore("@").replace(Regex("[^0-9A-Za-z+*#]"), "")
        return File(directory(), "${time}_${safeNumber.ifEmpty { "unknown" }}_${if (incoming) "IN" else "OUT"}.wav")
    }

    /** Newest first; skips empty files left by calls that never connected. */
    fun list(): List<File> =
        directory().listFiles { f -> f.isFile && f.extension == "wav" && f.length() > 44 }
            .orEmpty()
            .sortedByDescending { it.lastModified() }

    /** Removes files of calls that ended before any audio was recorded. */
    fun cleanUpEmpty() {
        directory().listFiles { f -> f.isFile && f.length() <= 44 }?.forEach { it.delete() }
    }
}
