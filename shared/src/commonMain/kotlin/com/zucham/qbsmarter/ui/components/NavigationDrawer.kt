package com.zucham.qbsmarter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import qbsmarter.shared.generated.resources.Res
import qbsmarter.shared.generated.resources.app_name
import qbsmarter.shared.generated.resources.report_bug

data class DrawerEntry(
    val labelKey: String,
    val displayLabel: String,
    val route: String,
    /**
     * Optional override color for this entry. When non-null, the entry's
     * stripe + label both render in this color regardless of selection
     * state. Used by the Solve route to make the primary destination
     * stand out in the drawer at a glance.
     *
     * Color.Unspecified (the default) means "use the standard
     * selected/unselected colors" – everything else stays neutral.
     */
    val tint: Color = Color.Unspecified,
)

/**
 * Drawer body. Header (app name + close X), entries as a list in the
 * middle, copyright + bug-report anchored at the bottom via Spacer-with-
 * weight.
 *
 * Style: each entry is a flat list row with a leading 3 dp colored
 * stripe that fills with `primary` when the entry is the active route
 * and is invisible otherwise. The list-with-stripe pattern combined with
 * a heavier text weight communicates "this is the current screen".
 *
 * Width: the standard Material `ModalDrawerSheet` is 360 dp; we override
 * to 280 dp – still comfortable for tap targets, leaves more of the
 * underlying screen visible while the drawer is open.
 *
 * The close X is the explicit exit affordance; selecting an option also
 * dismisses the drawer.
 */
@Composable
fun AppDrawerContent(
    entries: List<DrawerEntry>,
    currentRoute: String?,
    appVersion: String,
    currentProfileName: String?,
    onSelectRoute: (String) -> Unit,
    onProfileTap: (() -> Unit)?,
    onReportBug: () -> Unit,
    onClose: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.width(280.dp),
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            // Header: app name + close X.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close menu")
                }
            }
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))

            // Entry list: flat rows, no rounded pills.
            for (entry in entries) {
                DrawerListRow(
                    label = entry.displayLabel,
                    selected = currentRoute == entry.route,
                    tint = entry.tint,
                    onClick = { onSelectRoute(entry.route) },
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Active profile pill. Sits between the navigation
            // list and the bottom attribution block, above the divider.
            // Centered, primaryContainer-colored, rounded – reads as
            // "current profile context" rather than another nav row.
            //
            // Tapping the pill jumps to Settings (rather than re-routing
            // through the drawer entries) when [onProfileTap] is
            // provided. Settings is the natural destination because
            // that's where profile management lives – rename, switch,
            // export, etc. The interaction matches the user's mental
            // model: "tap your name to manage your profile".
            currentProfileName?.takeIf { it.isNotBlank() }?.let { name ->
                val pillModifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .let { m ->
                        if (onProfileTap != null) m.clickable(onClick = onProfileTap) else m
                    }
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = pillModifier,
                    )
                }
            }

            HorizontalDivider()
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "QBSmarter $appVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Text(
                    text = "© 2026 Matěj Žucha",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                TextButton(onClick = onReportBug, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(Res.string.report_bug))
                }
            }
        }
    }
}

/**
 * Single drawer entry. Layout: leading 3 dp colored stripe, then label.
 * The stripe is `primary`-colored when [selected]; transparent otherwise
 * (the column space is preserved so non-selected rows align with the
 * selected one – no horizontal jump on selection change).
 *
 * The label uses [FontWeight.SemiBold] when selected so the active
 * destination reads as a stronger anchor even when the eye is scanning
 * past the leading stripe.
 *
 * If [tint] is supplied (non-[Color.Unspecified]), it overrides both the
 * label color and the selected-state stripe color. Used by the Solve
 * entry to make the primary destination read as more visually weighted
 * in the drawer regardless of which screen is currently active. The
 * stripe is still hidden when the entry isn't selected so the existing
 * "filled stripe = active row" visual language is preserved.
 */
@Composable
private fun DrawerListRow(
    label: String,
    selected: Boolean,
    tint: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    val hasTint = tint != Color.Unspecified
    val stripeColor = when {
        selected && hasTint -> tint
        selected -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    val labelColor = when {
        hasTint -> tint
        selected -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading 3 dp selection stripe.
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(stripeColor),
        )
        Spacer(Modifier.width(13.dp))  // 16 dp leading edge minus the stripe
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            // SemiBold for both the selected case AND the tinted case
            // (Solve entry). The tint already raises the row visually,
            // and bold reinforces the "this is the primary destination"
            // intent without making other selected rows look weaker.
            fontWeight = if (selected || hasTint) FontWeight.SemiBold else FontWeight.Normal,
            color = labelColor,
        )
    }
}
