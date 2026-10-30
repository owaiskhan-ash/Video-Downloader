package com.teal.downloader.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.teal.downloader.IncomingLink
import com.teal.downloader.ui.MainViewModel
import com.teal.downloader.ui.screen.HistoryScreen
import com.teal.downloader.ui.screen.HomeScreen
import com.teal.downloader.ui.screen.QueueScreen
import com.teal.downloader.ui.screen.SettingsScreen
import com.teal.downloader.ui.screen.TemplatesScreen

enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Download", Icons.Rounded.Downloading),
    QUEUE("queue", "Queue", Icons.Rounded.VideoLibrary),
    HISTORY("history", "History", Icons.Rounded.History),
    TEMPLATES("templates", "Commands", Icons.Rounded.Terminal),
    SETTINGS("settings", "Settings", Icons.Rounded.Settings),
}

@Composable
fun TealAppRoot(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // A link shared in from another app arrives through IncomingLink and can
    // land while any tab is showing, so route back to home before applying it.
    val sharedLink by IncomingLink.url.collectAsStateWithLifecycle()
    LaunchedEffect(sharedLink) {
        val url = sharedLink ?: return@LaunchedEffect
        IncomingLink.consume()
        if (currentRoute != Destination.HOME.route) {
            navController.navigate(Destination.HOME.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
        }
        viewModel.onSharedLink(url)
    }

    // Five tabs is the practical limit for a bottom bar; Commands is the one that
    // can live behind Settings on a narrow screen, but keeping it here makes the
    // yt-dlp templates discoverable, which is the point of the feature.
    val tabs = listOf(
        Destination.HOME,
        Destination.QUEUE,
        Destination.HISTORY,
        Destination.TEMPLATES,
        Destination.SETTINGS,
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                tabs.forEach { destination ->
                    val selected = currentRoute == destination.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(destination.icon, contentDescription = destination.label)
                        },
                        label = { Text(destination.label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.HOME.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.HOME.route) { HomeScreen(viewModel) }
            composable(Destination.QUEUE.route) { QueueScreen(viewModel) }
            composable(Destination.HISTORY.route) { HistoryScreen(viewModel) }
            composable(Destination.TEMPLATES.route) { TemplatesScreen(viewModel) }
            composable(Destination.SETTINGS.route) {
                SettingsScreen(viewModel, onOpenTemplates = {
                    navController.navigate(Destination.TEMPLATES.route) { launchSingleTop = true }
                })
            }
        }
    }
}
