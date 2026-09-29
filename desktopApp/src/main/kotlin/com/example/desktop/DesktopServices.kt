package com.example.desktop

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.data.local.AppDatabase
import com.example.data.model.PhoneContact
import com.example.data.model.PushMessageItem
import com.example.data.repository.ContactsSource
import com.example.data.repository.KeyValueStore
import com.example.data.repository.PushSource
import com.example.desktop.sip.DesktopSipManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.prefs.Preferences

/** Accounts, call history and chat in %APPDATA%\Dialer\softphone.db. */
fun openDesktopDatabase(): AppDatabase =
    Room.databaseBuilder<AppDatabase>(File(DesktopSipManager.appDataDir(), AppDatabase.FILE_NAME).absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

/** [KeyValueStore] in the Windows registry (HKCU\Software\JavaSoft\Prefs\com\example\dialer\...). */
class PreferencesStore(name: String) : KeyValueStore {
    private val prefs = Preferences.userRoot().node("com/example/dialer/$name")

    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun getString(key: String, default: String?): String? = prefs.get(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.putBoolean(key, value).also { prefs.flush() }
    override fun putInt(key: String, value: Int) = prefs.putInt(key, value).also { prefs.flush() }
    override fun putLong(key: String, value: Long) = prefs.putLong(key, value).also { prefs.flush() }
    override fun putString(key: String, value: String?) {
        if (value == null) prefs.remove(key) else prefs.put(key, value)
        prefs.flush()
    }
    override fun remove(key: String) = prefs.remove(key).also { prefs.flush() }
}

/** Windows has no phone address book: the Contacts tab stays empty; numbers are dialed directly. */
class NoContacts : ContactsSource {
    override val contacts: StateFlow<List<PhoneContact>> = MutableStateFlow(emptyList())
    override val isLoading: StateFlow<Boolean> = MutableStateFlow(false)
    override fun hasContactsPermission() = true
    override suspend fun loadContacts() {}
    override fun findContactName(rawNumberOrUri: String): String? = null
}

/** No push on Windows: the app stays registered while it runs (also minimized to the tray). */
class NoPush : PushSource {
    override val fcmToken: StateFlow<String?> = MutableStateFlow(null)
    override val tokenStatus: StateFlow<String> = MutableStateFlow("Not used on Windows")
    override val receivedPushes: StateFlow<List<PushMessageItem>> = MutableStateFlow(emptyList())
    override fun refreshToken() {}
    override fun clearPushHistory() {}
    override fun recordPushMessage(item: PushMessageItem) {}
}
