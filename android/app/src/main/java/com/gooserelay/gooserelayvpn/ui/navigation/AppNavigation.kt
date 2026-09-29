package com.gooserelay.gooserelayvpn.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gooserelay.gooserelayvpn.R
import com.gooserelay.gooserelayvpn.ui.home.HomeScreen
import com.gooserelay.gooserelayvpn.ui.info.InfoScreen
import com.gooserelay.gooserelayvpn.ui.logs.LogsScreen
import com.gooserelay.gooserelayvpn.ui.profiles.ProfilesScreen
import com.gooserelay.gooserelayvpn.ui.settings.GlobalSettingsScreen
import com.gooserelay.gooserelayvpn.ui.settings.SettingsScreen
import com.gooserelay.gooserelayvpn.ui.theme.MdvColor

sealed class Screen(val route: String, @StringRes val titleRes: Int) {
    data object Home : Screen("home", R.string.title_home)
    data object Profiles : Screen("profiles", R.string.title_profiles)
    data object Logs : Screen("logs", R.string.title_logs)
    data object Settings : Screen("settings", R.string.settings_title)
    data object Info : Screen("info", R.string.title_info)
    data object ProfileSettings : Screen("profile_settings/{profileId}", R.string.profile_settings_title)
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val bottomBarScreens = listOf(Screen.Home, Screen.Profiles, Screen.Logs, Screen.Settings)
    fun navigateToRoot(screen: Screen) {
        val currentRoute = currentDestination?.route
        if (currentRoute == screen.route) {
            return
        }
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }
    val icons = mapOf(
        Screen.Home.route to Icons.Filled.Home,
        Screen.Profiles.route to Icons.Filled.Person,
        Screen.Logs.route to Icons.Filled.Terminal,
        Screen.Settings.route to Icons.Filled.Settings
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        bottomBar = {
            NavigationBar(
                containerColor = MdvColor.Background.copy(alpha = 0.95f),
                tonalElevation = 0.dp
            ) {
                bottomBarScreens.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    val title = stringResource(screen.titleRes)
                    NavigationBarItem(
                        icon = {
                            Icon(
                                icons[screen.route] ?: Icons.Filled.Home,
                                contentDescription = title,
                                tint = if (selected) MdvColor.PrimaryContainer else MdvColor.Secondary.copy(alpha = 0.65f)
                            )
                        },
                        label = {
                            Text(
                                title.uppercase(),
                                fontWeight = FontWeight.SemiBold,
                                color = if (selected) MdvColor.Primary else MdvColor.Secondary.copy(alpha = 0.65f)
                            )
                        },
                        selected = selected,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MdvColor.PrimaryContainer,
                            selectedTextColor = MdvColor.Primary,
                            unselectedIconColor = MdvColor.Secondary.copy(alpha = 0.65f),
                            unselectedTextColor = MdvColor.Secondary.copy(alpha = 0.65f),
                            indicatorColor = MdvColor.PrimaryContainer.copy(alpha = 0.12f)
                        ),
                        onClick = {
                            navigateToRoot(screen)
                        },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToProfiles = {
                        navController.navigate(Screen.Profiles.route)
                    },
                    onOpenInfo = { navController.navigate(Screen.Info.route) }
                )
            }
            composable(Screen.Profiles.route) {
                ProfilesScreen(
                    onBack = { navigateToRoot(Screen.Home) }
                )
            }
            composable(Screen.Logs.route) {
                LogsScreen(
                    onBack = { navigateToRoot(Screen.Home) }
                )
            }
            composable(Screen.Settings.route) {
                GlobalSettingsScreen()
            }
            composable(Screen.ProfileSettings.route) {
                SettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Info.route) {
                InfoScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
