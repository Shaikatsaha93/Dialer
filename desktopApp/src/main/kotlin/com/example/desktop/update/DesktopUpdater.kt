package com.example.desktop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A newer Windows installer published by the GitHub "Release" workflow (its desktop-update.json). */
data class DesktopUpdate(
    val version: String,
    val installerUrl: String,
    val notes: String
)

sealed class DesktopUpdateState {
    data object None : DesktopUpdateState()
    data class Available(val update: DesktopUpdate) : DesktopUpdateState()
    data class Downloading(val update: DesktopUpdate, val progress: Float) : DesktopUpdateState()
    data class Failed(val update: DesktopUpdate, val message: String) : DesktopUpdateState()
}

/**
 * Updates for the Windows app, like AppUpdater on Android: every push to main also builds the
 * installer on GitHub and adds it, with desktop-update.json, to the latest release. The app reads
 * that file, and when its version is higher than [installedVersion] offers to download the
 * installer, starts it and quits so the installer can replace the files. The installer upgrades
 * the existing install (same upgradeUuid), so settings, accounts and call history stay.
 */
class DesktopUpdater(private val installedVersion: String) {

    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private val _state = MutableStateFlow<DesktopUpdateState>(DesktopUpdateState.None)
    val state: StateFlow<DesktopUpdateState> = _state.asStateFlow()

    /** Why the last check could not reach GitHub (shown by "Check for updates") */
    var lastError: String? = null
        private set

    /** Looks for a newer release. Returns the update, or null when there is none or GitHub is unreachable. */
    suspend fun check(): DesktopUpdate? {
        if (_state.value is DesktopUpdateState.Downloading) return null
        return try {
            val body = withContext(Dispatchers.IO) {
                val request = HttpRequest.newBuilder(URI(UPDATE_JSON_URL)).timeout(Duration.ofSeconds(30)).GET().build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                check(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }
                response.body()
            }
            val o = Json.parseToJsonElement(body).jsonObject
            val update = DesktopUpdate(
                version = o["version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                installerUrl = o["installerUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                notes = o["notes"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
            lastError = null
            println("DesktopUpdater: latest ${update.version}, installed $installedVersion")
            if (update.installerUrl.isNotBlank() && isNewer(update.version, installedVersion)) {
                if (_state.value !is DesktopUpdateState.Failed) _state.value = DesktopUpdateState.Available(update)
                update
            } else {
                null
            }
        } catch (e: Exception) {
            // No release yet, offline, GitHub unreachable: try again next time
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            println("DesktopUpdater: check failed: $lastError")
            null
        }
    }

    fun dismiss() {
        if (_state.value !is DesktopUpdateState.Downloading) _state.value = DesktopUpdateState.None
    }

    /** Downloads the installer to the temp folder and starts it. Returns true when the app should quit now. */
    suspend fun downloadAndInstall(update: DesktopUpdate): Boolean {
        _state.value = DesktopUpdateState.Downloading(update, 0f)
        return try {
            val file = withContext(Dispatchers.IO) { download(update) }
            withContext(Dispatchers.IO) { ProcessBuilder(file.absolutePath).start() }
            true
        } catch (e: Exception) {
            _state.value = DesktopUpdateState.Failed(update, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun download(update: DesktopUpdate): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "DialerUpdate").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "Dialer-Setup-${update.version}.exe")
        val part = File(dir, file.name + ".part")
        val request = HttpRequest.newBuilder(URI(update.installerUrl)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }
        val total = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        response.body().use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var lastShown = -1
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    if (total > 0) {
                        val percent = (done * 100 / total).toInt()
                        if (percent != lastShown) {
                            lastShown = percent
                            _state.value = DesktopUpdateState.Downloading(update, done.toFloat() / total)
                        }
                    }
                }
            }
        }
        check(total <= 0 || part.length() == total) { "Download incomplete" }
        check(part.renameTo(file)) { "Could not save the installer" }
        return file
    }

    companion object {
        const val UPDATE_JSON_URL =
            "https://github.com/Shaikatsaha93/Dialer/releases/latest/download/desktop-update.json"

        /** "1.0.14" > "1.0.9": compares the numbers part by part */
        fun isNewer(latest: String, installed: String): Boolean {
            val a = latest.split('.').map { it.toIntOrNull() ?: 0 }
            val b = installed.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
