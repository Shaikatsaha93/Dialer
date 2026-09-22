package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector? = null) {
    object Dialer : Screen("dialer", "Dialer", Icons.Default.Dialpad)
    object History : Screen("history", "History", Icons.Default.History)
    object Accounts : Screen("accounts", "Settings", Icons.Default.Settings)
    object ActiveCall : Screen("active_call", "Active Call")
}

val BottomNavItems = listOf(
    Screen.Dialer,
    Screen.History,
    Screen.Accounts
)
