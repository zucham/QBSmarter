package com.zucham.qbsmarter.ui.theme

import androidx.compose.runtime.Composable

/**
 * Apply system-bar tinting to match the active theme. On Android this sets
 * the status/navigation bar icon color (light icons on dark backgrounds and
 * vice-versa) so the bars look like an extension of the app surface rather
 * than a pasted-on white strip on top of a dark UI.
 *
 * No-op on platforms that have no status bar concept.
 */
@Composable
expect fun ApplySystemBarsTheme(darkTheme: Boolean)
