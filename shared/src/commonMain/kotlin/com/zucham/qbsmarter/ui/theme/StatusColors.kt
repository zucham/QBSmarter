package com.zucham.qbsmarter.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Semantic status colors that intentionally don't follow the active theme
 * seed.
 *
 * Material3 doesn't ship a "success" color role; the closest tertiary or
 * primary would change with each [ThemeSeed], which would defeat the point
 * – a "connected" green needs to read the same in BLUE theme as it does in
 * ORANGE. Same logic for the inspection countdown's amber/red urgency cues:
 * those are universal road-sign colors, not brand colors.
 *
 * Defining them as named constants instead of inline `Color(0x...)` literals
 * means call sites stay readable AND if we later decide to lift them into
 * a theme extension (per-mode light/dark variants), we only have to change
 * them in one place.
 *
 * Neutral gray for "less-important text" (Cancel buttons, secondary labels)
 * is NOT here: that role belongs to `MaterialTheme.colorScheme.onSurfaceVariant`,
 * which IS the Material3 token for it.
 */
object StatusColors {
    /** Connection indicator dot when CONNECTED. Reads as a "go" / "OK" green. */
    val ConnectedGreen = Color(0xFF2ECC71)

    /** Connection indicator dot when not connected. Neutral gray, not theme-derived. */
    val DisconnectedGray = Color(0xFF9E9E9E)

    /**
     * Inspection countdown color for the final 3 seconds (12-15 s elapsed
     * out of 15). Universal "you must act now" red.
     */
    val UrgencyRed = Color(0xFFE53935)

    /**
     * Inspection countdown color for 8-12 s elapsed. Universal "warning"
     * amber/yellow that's distinguishable from both the neutral "fine"
     * shade and the "act now" red.
     */
    val UrgencyAmber = Color(0xFFFFB300)
}

/** Shared diameter for the connection-status dot used on Solve and Devices screens. */
val ConnectionDotSize = 10.dp

