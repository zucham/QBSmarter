package com.zucham.qbsmarter.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zucham.qbsmarter.ui.theme.AppTheme

/**
 * Root composable. Wrap once at MainActivity.setContent { App() }; never
 * re-wrap per screen.
 *
 * No safeContentPadding here on purpose: Material3's TopAppBar applies its
 * own status-bar inset and paints the bar's container color into that
 * zone. With edge-to-edge enabled in MainActivity, this gives us a tinted
 * status bar that follows our theme. Adding safeContentPadding back would
 * create a transparent (Window-default-colored) gap above the TopAppBar,
 * which on Android 12 shows as a white strip even in the dark theme.
 */
@Composable
fun App() {
    AppTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            AppNavHost()
        }
    }
}
