package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.model.CallState
import com.example.ui.navigation.BottomNavItems
import com.example.ui.navigation.Screen
import com.example.ui.theme.GlassBackground
import com.example.ui.theme.LocalGlassColors
import com.example.ui.theme.glass
import com.example.ui.viewmodel.SoftphoneViewModel
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import com.example.ui.platform.RequestStartupPermissions
import com.example.platform.urlDecode
import com.example.AppGraph

@Composable
fun SoftphoneMainScreen(
    viewModel: SoftphoneViewModel,
    modifier: Modifier = Modifier,
    openAdminRequest: Boolean = false,
    onAdminOpened: () -> Unit = {},
    openChatRequest: String? = null,
    onChatOpened: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val isAdmin by AppGraph.license.isAdmin.collectAsStateWithLifecycle()
    val navItems = if (isAdmin) BottomNavItems + Screen.Admin else BottomNavItems

    val unreadChats by AppGraph.chatRepository.totalUnread.collectAsStateWithLifecycle(initialValue = 0)

    // Opened from a chat message notification
    LaunchedEffect(openChatRequest) {
        if (openChatRequest != null) {
            navController.navigate(Screen.ChatThread.route(openChatRequest)) { launchSingleTop = true }
            onChatOpened()
        }
    }

    // Opened from a "new access request" notification
    LaunchedEffect(openAdminRequest, isAdmin) {
        if (openAdminRequest && isAdmin) {
            navController.navigate(Screen.Admin.route) { launchSingleTop = true }
            onAdminOpened()
        }
    }

    val callState by viewModel.callState.collectAsStateWithLifecycle()

    RequestStartupPermissions(onContactsGranted = { viewModel.loadDeviceContacts() })

    // Auto-navigate to ActiveCall screen when a call becomes incoming or connected
    LaunchedEffect(callState) {
        if (callState !is CallState.Idle && callState !is CallState.Disconnected) {
            if (currentRoute != Screen.ActiveCall.route) {
                navController.navigate(Screen.ActiveCall.route) {
                    launchSingleTop = true
                }
            }
        }
    }

    val glassColors = LocalGlassColors.current

    GlassBackground(modifier = modifier) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 600.dp
        val isCallScreen = currentRoute == Screen.ActiveCall.route

        if (isWideScreen && !isCallScreen) {
            // Adaptive Tablet / Foldable Layout with NavigationRail
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(8.dp)
                        .glass(RoundedCornerShape(28.dp), glassColors)
                        .testTag("softphone_navigation_rail"),
                    containerColor = Color.Transparent,
                    header = {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                ) {
                    navItems.forEach { screen ->
                        val selected = currentRoute == screen.route
                        NavigationRailItem(
                            selected = selected,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                screen.icon?.let {
                                    Icon(
                                        imageVector = it,
                                        contentDescription = screen.title
                                    )
                                }
                            },
                            label = {
                                Text(
                                    text = screen.title,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            modifier = Modifier.testTag("rail_nav_${screen.route}")
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 1200.dp)
                    ) {
                        NavHostContent(
                            navController = navController,
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        } else {
            // Mobile Compact Layout with Bottom NavigationBar
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    // The chat thread has its own input bar at the bottom
                    val isChatThread = currentRoute == Screen.ChatThread.route
                    AnimatedVisibility(visible = !isCallScreen && !isChatThread) {
                        // Floating glass tab bar
                        NavigationBar(
                            modifier = Modifier
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                .glass(RoundedCornerShape(30.dp), glassColors)
                                .testTag("softphone_bottom_navigation"),
                            containerColor = Color.Transparent,
                            tonalElevation = 0.dp,
                            windowInsets = WindowInsets(0, 0, 0, 0)
                        ) {
                            navItems.forEach { screen ->
                                val selected = currentRoute == screen.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        if (currentRoute != screen.route) {
                                            navController.navigate(screen.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                    icon = {
                                        screen.icon?.let {
                                            BadgedBox(badge = {
                                                if (screen == Screen.Chat && unreadChats > 0) Badge { Text(unreadChats.toString()) }
                                            }) {
                                                Icon(
                                                    imageVector = it,
                                                    contentDescription = screen.title
                                                )
                                            }
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = screen.title,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    modifier = Modifier.testTag("bottom_nav_${screen.route}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 540.dp)
                    ) {
                        NavHostContent(
                            navController = navController,
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun NavHostContent(
    navController: androidx.navigation.NavHostController,
    viewModel: SoftphoneViewModel,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Dialer.route,
        modifier = modifier
    ) {
        composable(Screen.Dialer.route) {
            DialerScreen(
                viewModel = viewModel,
                onNavigateToActiveCall = {
                    navController.navigate(Screen.ActiveCall.route) {
                        launchSingleTop = true
                    }
                },
                onNavigateToAccounts = {
                    navController.navigate(Screen.Accounts.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }

        composable(Screen.Contacts.route) {
            ContactsScreen(
                viewModel = viewModel,
                onNavigateToActiveCall = {
                    navController.navigate(Screen.ActiveCall.route) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.History.route) {
            CallHistoryScreen(
                viewModel = viewModel,
                onNavigateToActiveCall = {
                    navController.navigate(Screen.ActiveCall.route) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.Accounts.route) {
            AccountConfigScreen(viewModel = viewModel)
        }

        composable(Screen.Chat.route) {
            ChatListScreen(
                onOpenConversation = { id -> navController.navigate(Screen.ChatThread.route(id)) }
            )
        }

        composable(Screen.ChatThread.route) { entry ->
            val id = urlDecode(entry.savedStateHandle.get<String>("id").orEmpty())
            ChatThreadScreen(
                conversationId = id,
                onBack = { navController.popBackStack() },
                onCall = { number ->
                    viewModel.setDialerInput(number)
                    viewModel.initiateCall()
                    navController.navigate(Screen.ActiveCall.route) { launchSingleTop = true }
                }
            )
        }

        composable(Screen.Admin.route) {
            AdminScreen()
        }

        composable(Screen.ActiveCall.route) {
            ActiveCallScreen(
                viewModel = viewModel,
                onBackToDialer = {
                    navController.popBackStack(Screen.Dialer.route, inclusive = false)
                }
            )
        }
    }
}
