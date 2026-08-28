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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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

    // One guard per scaffold, published to the whole content tree. Any
    // region that conflicts with the drawer's swipe – today only the
    // Solve screen's 3D cube – claims it for the duration of a touch.
    val gestureGuard = remember { DrawerGestureGuard() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Gesture policy, in two parts.
        //
        // Once the drawer is open (or animating toward open) every
        // gesture is welcome – swipe-close, scrim tap, system Back. We
        // read `targetValue` rather than `currentValue` so gestures
        // unlock the instant the user taps the hamburger, not only when
        // the open animation finishes. (BackHandler activation is tied
        // to drawerState.isOpen internally by Material, so the back
        // button works whenever the drawer is visible either way.)
        //
        // While closed, swipe-to-open is on everywhere *except* inside a
        // region that has claimed [gestureGuard]. Material's drawer
        // applies its drag detection to the entire content area rather
        // than a screen-edge strip, so a horizontal drag on the Solve
        // screen's 3D cube would otherwise be read as "open the menu"
        // instead of "rotate the cube". Scoping the exception to the
        // cube itself keeps the swipe available on every other screen –
        // and on the rest of the Solve screen. See DrawerGestures.kt.
        gesturesEnabled = drawerState.targetValue == DrawerValue.Open ||
            !gestureGuard.isSuppressed,
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
            CompositionLocalProvider(LocalDrawerGestureGuard provides gestureGuard) {
                Box(modifier = Modifier.padding(padding)) { content() }
            }
        }
    }
}
