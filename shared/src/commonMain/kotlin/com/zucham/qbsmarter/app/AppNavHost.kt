package com.zucham.qbsmarter.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zucham.qbsmarter.ui.components.AppScaffold
import com.zucham.qbsmarter.ui.components.DrawerEntry
import com.zucham.qbsmarter.ui.screens.devices.DevicesScreen
import com.zucham.qbsmarter.ui.screens.guide.GuideScreen
import com.zucham.qbsmarter.ui.screens.history.HistoryScreen
import com.zucham.qbsmarter.ui.screens.settings.SettingsScreen
import com.zucham.qbsmarter.ui.screens.settings.SettingsViewModel
import com.zucham.qbsmarter.ui.screens.solve.SolveScreen
import com.zucham.qbsmarter.util.UrlOpener
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import qbsmarter.shared.generated.resources.Res
import qbsmarter.shared.generated.resources.app_name
import qbsmarter.shared.generated.resources.nav_devices
import qbsmarter.shared.generated.resources.nav_guide
import qbsmarter.shared.generated.resources.nav_history
import qbsmarter.shared.generated.resources.nav_settings
import qbsmarter.shared.generated.resources.nav_solve
import qbsmarter.shared.generated.resources.profile_default_name

object Routes {
    const val SOLVE = "solve"
    const val DEVICES = "devices"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val GUIDE = "guide"
}

/**
 * Single NavHost rooted at the AppScaffold. The scaffold owns drawer + top
 * bar; per-screen scaffolds would mean the chrome flickers on navigation.
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val urlOpener: UrlOpener = koinInject()

    // Resolve labels at composition time so they update on locale change.
    val solveLabel = stringResource(Res.string.nav_solve)
    val devicesLabel = stringResource(Res.string.nav_devices)
    val historyLabel = stringResource(Res.string.nav_history)
    val settingsLabel = stringResource(Res.string.nav_settings)
    val guideLabel = stringResource(Res.string.nav_guide)
    val appName = stringResource(Res.string.app_name)
    val vm: SettingsViewModel = koinViewModel()
    val user by vm.user.collectAsState()

    // Theme-tinted Solve entry. We resolve `primary` here (inside the
    // AppTheme scope) and pass it through DrawerEntry so the drawer
    // component itself stays theme-agnostic at the data-class level.
    // The tint refreshes whenever the seed/mode change because
    // MaterialTheme.colorScheme is a CompositionLocal read.
    val solveTint = MaterialTheme.colorScheme.primary

    val drawerEntries = remember(
        solveLabel, devicesLabel, historyLabel, settingsLabel, guideLabel, solveTint,
    ) {
        listOf(
            // Solve is the primary destination – tinting it with the
            // theme primary color makes it visually pop in the drawer
            // so the user's eye lands on it first.
            DrawerEntry("nav.solve", solveLabel, Routes.SOLVE, tint = solveTint),
            DrawerEntry("nav.devices", devicesLabel, Routes.DEVICES),
            DrawerEntry("nav.history", historyLabel, Routes.HISTORY),
            DrawerEntry("nav.settings", settingsLabel, Routes.SETTINGS),
            DrawerEntry("nav.guide", guideLabel, Routes.GUIDE),
        )
    }

    val activeProfileName = user?.displayName?.takeIf { it.isNotBlank() } ?: ""

    val routeTitle = when (currentRoute) {
        Routes.SOLVE -> solveLabel
        Routes.DEVICES -> devicesLabel
        Routes.HISTORY -> if (activeProfileName.isNotBlank()) {
            "$historyLabel – $activeProfileName"
        } else {
            historyLabel
        }
        Routes.SETTINGS -> settingsLabel
        Routes.GUIDE -> guideLabel
        else -> appName
    }

    AppScaffold(
        title = routeTitle,
        drawerEntries = drawerEntries,
        currentRoute = currentRoute,
        appVersion = "v1.0.0",
        currentProfileName = activeProfileName,
        onSelectRoute = { route ->
            if (currentRoute != route) {
                navController.navigate(route) {
                    launchSingleTop = true
                    popUpTo(Routes.SOLVE) { saveState = true }
                    restoreState = true
                }
            }
        },
        // Tapping the active-profile pill in the drawer jumps to Settings.
        // Same nav pattern as the regular drawer entries (saveState,
        // restoreState, single-top) so coming back from Settings to the
        // previous screen restores its scroll position and other state.
        // No-op when already on Settings.
        onProfileTap = {
            if (currentRoute != Routes.SETTINGS) {
                navController.navigate(Routes.SETTINGS) {
                    launchSingleTop = true
                    popUpTo(Routes.SOLVE) { saveState = true }
                    restoreState = true
                }
            }
        },
        onReportBug = { urlOpener.open("mailto:zucham@duck.com?subject=QBSmarter%20bug") },
    ) {
        NavHost(navController = navController, startDestination = Routes.SOLVE) {
            composable(Routes.SOLVE) {
                SolveScreen(
                    onNavigateToDevices = {
                        // Same navigation pattern as the drawer entries:
                        // swap to Devices on top of Solve, save state for
                        // restoration when the user comes back.
                        if (currentRoute != Routes.DEVICES) {
                            navController.navigate(Routes.DEVICES) {
                                launchSingleTop = true
                                popUpTo(Routes.SOLVE) { saveState = true }
                                restoreState = true
                            }
                        }
                    },
                )
            }
            composable(Routes.DEVICES) { DevicesScreen(
                onNavigateToSolve = {
                    // Same navigation pattern as the drawer entries:
                    // swap to Solve on top of Devices, save state for
                    // restoration when the user comes back.
                    if (currentRoute != Routes.SOLVE) {
                        navController.navigate(Routes.SOLVE) {
                            launchSingleTop = true
                            popUpTo(Routes.SOLVE) { saveState = true }
                            restoreState = true
                        }
                    }
                },
            ) }
            composable(Routes.HISTORY) { HistoryScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.GUIDE) { GuideScreen() }
        }
    }
}
