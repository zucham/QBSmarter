package com.zucham.qbsmarter.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Android implementation: tells the system to use light or dark icons in
 * the status and nav bars based on whether our theme is dark.
 *
 * The Activity must extend AppCompatActivity (or call enableEdgeToEdge())
 * for these flags to take effect. We're already doing both.
 *
 * `isAppearanceLightStatusBars = true` ⇢ icons are dark (good on a light
 * background); `false` ⇢ icons are light (good on dark). Same for nav bar.
 */
@Composable
actual fun ApplySystemBarsTheme(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return  // no Window in @Preview
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowInsetsControllerCompat(window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme

        // On API < 35, Android draws an opaque scrim behind the nav bar by
        // default; setting it transparent (combined with enableEdgeToEdge)
        // lets the surface color show through. WindowCompat takes care of
        // the right flags across versions.
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }
}
