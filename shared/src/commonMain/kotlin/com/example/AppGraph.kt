package com.example

import com.example.data.repository.AdminService
import com.example.data.repository.CallLogRepository
import com.example.data.repository.ChatRepository
import com.example.data.repository.ContactsSource
import com.example.data.repository.LicenseService
import com.example.data.repository.PushSource
import com.example.data.repository.SettingsRepository
import com.example.data.repository.SipAccountRepository
import com.example.sip.SipManager

/**
 * The app's services, filled in once at start-up by the platform app (SoftphoneApp on Android,
 * Main.kt on Windows) and used by the shared screens and view model.
 */
object AppGraph {
    lateinit var sipManager: SipManager
    lateinit var accountRepository: SipAccountRepository
    lateinit var callLogRepository: CallLogRepository
    lateinit var settingsRepository: SettingsRepository
    lateinit var contacts: ContactsSource
    lateinit var push: PushSource
    lateinit var license: LicenseService
    lateinit var admin: AdminService
    lateinit var chatRepository: ChatRepository
}
