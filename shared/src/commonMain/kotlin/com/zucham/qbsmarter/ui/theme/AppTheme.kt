package com.zucham.qbsmarter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.koin.compose.koinInject

/**
 * App-wide theme wrapper. Wrap once at the root; never re-wrap per screen.
 * Side-effect: also tells the platform to color its system bars to match,
 * so the status bar doesn't stay white on top of a dark surface.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val controller: ThemeController = koinInject()
    val seed by controller.seed.collectAsState()
    val mode by controller.mode.collectAsState()

    val systemDark = isSystemInDarkTheme()
    val useDark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = if (useDark) AppColorSchemes.dark(seed) else AppColorSchemes.light(seed)
    ApplySystemBarsTheme(darkTheme = useDark)
    MaterialTheme(colorScheme = scheme, content = content)
}
