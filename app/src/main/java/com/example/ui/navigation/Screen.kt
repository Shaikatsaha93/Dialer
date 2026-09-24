package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector? = null) {
    object Dialer : Screen("dialer", "Dialer", Icons.Default.Dialpad)
    object Contacts : Screen("contacts", "Contacts", Icons.Default.Contacts)
    object History : Screen("history", "History", Icons.Default.History)
    object Accounts : Screen("accounts", "Settings", Icons.Default.Settings)
    object ActiveCall : Screen("active_call", "Active Call")
    /** Only shown when signed in as admin. */
    object Admin : Screen("admin", "Admin", Icons.Default.AdminPanelSettings)
}

val BottomNavItems = listOf(
    Screen.Dialer,
    Screen.Contacts,
    Screen.History,
    Screen.Accounts
)
