package com.example.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer build published by the GitHub "Release APK" workflow (its update.json). */
data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String
)

sealed class UpdateState {
    data object None : UpdateState()
    data class Available(val update: AppUpdate) : UpdateState()
    data class Downloading(val update: AppUpdate, val progress: Float) : UpdateState()
    data class Failed(val update: AppUpdate, val message: String) : UpdateState()
}

/**
 * In-app updates without the Play Store: every push to main builds a signed APK on GitHub
 * (.github/workflows/release.yml) and publishes it as the latest release together with
 * update.json. The app reads that file, and when its versionCode is higher than this build's,
 * offers to download the APK and hands it to Android's installer. Android keeps the app's data,
 * because every build is signed with the same key.
 */
class AppUpdater(private val context: Context) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var lastCheck = 0L

    /**
     * Looks for a newer release, at most once an hour (called each time the app is opened);
     * [force] checks right away. Returns false when the check could not reach GitHub.
     */
    suspend fun check(force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < CHECK_INTERVAL_MS) return true
        if (_state.value is UpdateState.Downloading) return true
        lastCheck = now
        try {
            val json = withContext(Dispatchers.IO) { download(UPDATE_JSON_URL) }
            val o = JSONObject(json)
            val update = AppUpdate(
                versionCode = o.getInt("versionCode"),
                versionName = o.optString("versionName"),
                apkUrl = apkForThisPhone(o),
                notes = o.optString("notes")
            )
            Log.i(TAG, "Latest release ${update.versionName} (${update.versionCode}), installed ${BuildConfig.VERSION_CODE}")
            if (update.versionCode > BuildConfig.VERSION_CODE) _state.value = UpdateState.Available(update)
            return true
        } catch (e: Exception) {
            // No release yet, offline, GitHub unreachable: try again next time
            Log.w(TAG, "Update check failed: ${e.message}")
            return false
        }
    }

    fun dismiss() {
        _state.value = UpdateState.None
    }

    /** Downloads the APK and opens Android's install screen. */
    suspend fun downloadAndInstall(update: AppUpdate) {
        // Android 8+: the user must allow this app to install apps once
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            _state.value = UpdateState.Available(update)
            return
        }
        _state.value = UpdateState.Downloading(update, 0f)
        try {
            val dir = File(context.externalCacheDir ?: context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, "Dialer-${update.versionCode}.apk")
            withContext(Dispatchers.IO) {
                val conn = open(update.apkUrl)
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    apk.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            if (total > 0) _state.value = UpdateState.Downloading(update, done.toFloat() / total)
                        }
                    }
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            _state.value = UpdateState.Available(update)
        } catch (e: Exception) {
            Log.w(TAG, "Update download failed: ${e.message}")
            _state.value = UpdateState.Failed(update, e.message ?: "Download failed")
        }
    }

    /**
     * Releases carry one APK per CPU type ("apks": {"arm64-v8a": url, ...}), about half the size
     * of the universal one; take the first this phone runs. "apkUrl" (universal) is the fallback.
     */
    private fun apkForThisPhone(o: JSONObject): String {
        val apks = o.optJSONObject("apks")
        if (apks != null) {
            for (abi in Build.SUPPORTED_ABIS) {
                apks.optString(abi).takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return o.getString("apkUrl")
    }

    private fun open(url: String): HttpURLConnection {
        // GitHub answers release downloads with a redirect to its file servers
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Dialer/${BuildConfig.VERSION_NAME}")
        if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
        return conn
    }

    private fun download(url: String): String = open(url).inputStream.use { it.readBytes().decodeToString() }

    companion object {
        private const val TAG = "AppUpdater"
        private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L
        /** Always points at the newest release's update.json */
        const val UPDATE_JSON_URL = "https://github.com/Shaikatsaha93/Dialer/releases/latest/download/update.json"
    }
}
