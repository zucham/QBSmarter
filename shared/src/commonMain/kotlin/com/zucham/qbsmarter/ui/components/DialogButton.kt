package com.zucham.qbsmarter.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The tone of a dialog button, which decides its colour rather than its
 * shape - every dialog button looks the same apart from the colour it is
 * drawn in.
 *
 * Three roles cover every dialog in the app; adding a fourth should be a
 * deliberate decision, not a one-off colour at a call site.
 */
enum class DialogButtonEmphasis {
    /** The action the user came to the dialog for. */
    PRIMARY,

    /** An action that destroys data: delete, forget, wipe. */
    DESTRUCTIVE,

    /** Cancel / Close. Present but visually stepping back. */
    NEUTRAL,
}

/**
 * The button used by every confirming dialog and modal in the app.
 *
 * Dialog actions used to be bare [androidx.compose.material3.TextButton]s
 * - coloured text with no container. That reads well in Material's own
 * specimens but poorly here: on a dialog surface with body text directly
 * above, a coloured word is not obviously a *button*, and the tap target
 * has no visible edge. Every one of them is now an [OutlinedButton] with
 * a hairline border in its own content colour, so the affordance is
 * unmistakable while the buttons still sit lighter than the filled
 * buttons used for primary actions on the screens behind them.
 *
 * The border is drawn at the button's content colour rather than the
 * theme `outline`, at [BORDER_ALPHA] so it frames the label without
 * competing with it. A destructive action therefore gets a red outline
 * as well as red text, which is the point: the warning is carried by the
 * whole control, not just the word.
 *
 * Centralised deliberately. The alternative - an `OutlinedButton` spelled
 * out at each of the dozen dialog call sites - is how the previous
 * inconsistency (three different shades of "cancel") happened in the
 * first place.
 */
@Composable
fun DialogButton(
    label: String,
    onClick: () -> Unit,
    emphasis: DialogButtonEmphasis = DialogButtonEmphasis.PRIMARY,
    modifier: Modifier = Modifier,
) {
    val contentColor: Color = when (emphasis) {
        DialogButtonEmphasis.PRIMARY -> MaterialTheme.colorScheme.primary
        DialogButtonEmphasis.DESTRUCTIVE -> MaterialTheme.colorScheme.error
        DialogButtonEmphasis.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        border = BorderStroke(BORDER_WIDTH, contentColor.copy(alpha = BORDER_ALPHA)),
        contentPadding = CONTENT_PADDING,
    ) {
        Text(
            text = label,
            // Neutral actions stay at normal weight so that in a
            // two-button dialog the eye lands on the action first and
            // the way out second.
            fontWeight = if (emphasis == DialogButtonEmphasis.NEUTRAL) {
                FontWeight.Normal
            } else {
                FontWeight.Bold
            },
        )
    }
}

/** "Thin visible border" - one device pixel at typical densities. */
private val BORDER_WIDTH = 1.dp

/**
 * Border opacity. Full-strength would make the outline as loud as the
 * label; this keeps it a frame.
 */
private const val BORDER_ALPHA = 0.5f

/**
 * Tighter than [ButtonDefaults.ContentPadding] (24 dp horizontal), which
 * makes two buttons in an [androidx.compose.material3.AlertDialog]'s
 * action row wide enough to wrap on narrow phones.
 */
private val CONTENT_PADDING = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
