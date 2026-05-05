package com.zucham.qbsmarter.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * Single top-level scaffold for every screen. Owns the drawer + top bar so
 * individual screens can stay dumb. Per-screen actions are passed through
 * the [actions] slot.
 *
 * The TopAppBar uses the theme's primary container colors so it picks up
 * the seed color the user chose (Settings → Theme → Color). On Android the
 * TopAppBar paints into the status-bar inset area as well, so the system
 * bar reads as an extension of our chrome rather than a white strip on top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    drawerEntries: List<DrawerEntry>,
    currentRoute: String?,
    appVersion: String,
    currentProfileName: String?,
    onSelectRoute: (String) -> Unit,
    onProfileTap: (() -> Unit)?,
    onReportBug: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Gesture policy: enable as soon as the drawer is animating toward
        // open OR is open. While fully closed, the Solve screen's 3D cube
        // needs the full horizontal surface for orientation drags; an
        // edge-swipe-to-open would steal those. Once the drawer is moving
        // up, every dismiss gesture is welcome – system Back, swipe-close,
        // scrim tap. We use `targetValue` (instead of `currentValue`) so
        // gestures unlock the instant the user taps the hamburger, not
        // only when the open animation finishes.
        // BackHandler activation is internally tied to drawerState.isOpen
        // by Material, so the back button works as long as the drawer is
        // visible.
        gesturesEnabled = drawerState.targetValue == DrawerValue.Open,
        drawerContent = {
            AppDrawerContent(
                entries = drawerEntries,
                currentRoute = currentRoute,
                appVersion = appVersion,
                currentProfileName = currentProfileName,
                onSelectRoute = { route ->
                    scope.launch { drawerState.close() }
                    onSelectRoute(route)
                },
                // Wrap the host's onProfileTap so the drawer closes
                // alongside navigation – same pattern as onSelectRoute /
                // onReportBug above, keeps the drawer-close logic in
                // one place rather than duplicating it at every call
                // site upstream.
                onProfileTap = onProfileTap?.let { tap ->
                    {
                        scope.launch { drawerState.close() }
                        tap()
                    }
                },
                onReportBug = {
                    scope.launch { drawerState.close() }
                    onReportBug()
                },
                onClose = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = { actions() },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) { content() }
        }
    }
}
