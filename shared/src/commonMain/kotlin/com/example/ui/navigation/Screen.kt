package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.platform.urlEncode

sealed class Screen(val route: String, val title: String, val icon: ImageVector? = null) {
    object Dialer : Screen("dialer", "Dialer", Icons.Default.Dialpad)
    object Contacts : Screen("contacts", "Contacts", Icons.Default.Contacts)
    object Chat : Screen("chat", "Chat", Icons.AutoMirrored.Filled.Chat)
    object History : Screen("history", "History", Icons.Default.History)
    object Accounts : Screen("accounts", "Settings", Icons.Default.Settings)
    object ActiveCall : Screen("active_call", "Active Call")
    /** One chat conversation; the id is URL-encoded into the route. */
    object ChatThread : Screen("chat_thread/{id}", "Chat") {
        fun route(conversationId: String) = "chat_thread/" + urlEncode(conversationId)
    }
    /** Only shown when signed in as admin. */
    object Admin : Screen("admin", "Admin", Icons.Default.AdminPanelSettings)
}

val BottomNavItems = listOf(
    Screen.Dialer,
    Screen.Contacts,
    Screen.Chat,
    Screen.History,
    Screen.Accounts
)
