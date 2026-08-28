package com.zucham.qbsmarter.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zucham.qbsmarter.domain.stats.Ao5
import com.zucham.qbsmarter.util.formatDuration
import org.jetbrains.compose.resources.stringResource
import qbsmarter.shared.generated.resources.Res
import qbsmarter.shared.generated.resources.solve_dnf

/**
 * The five times an Ao5 was computed from, oldest first, with the two
 * that did not count in brackets.
 *
 * Shared by the History detail dialog and the record celebration, which
 * is why it lives here rather than beside either of them. The bracket
 * convention has to mean the same thing in both places — a user who
 * learns it from the celebration must not find it reversed in History.
 *
 * Which two are bracketed comes from [Ao5.trimmedIndices], not from a
 * `min`/`max` here, so the brackets can never disagree with the average
 * printed next to them. That matters because the obvious implementation
 * is wrong in three ways that all occur in real data:
 *
 *  * a **DNF is the slowest** result (WCA 9f9), not a value to skip — it
 *    is the bracketed-slowest, and the real slowest time still counts;
 *  * **ties bracket one entry, not both** — two identical fastest times
 *    drop one of themselves, and bracketing both would show four
 *    brackets around a three-solve average;
 *  * **two DNFs mean no average at all**, so nothing is bracketed;
 *    brackets meaning "excluded from the average" need an average.
 *
 * Rendered as a wrapping [FlowRow] of monospace tokens rather than one
 * string: five times plus brackets overflow a narrow phone, and a plain
 * `Text` would either clip them or break a line mid-number. This way each
 * time stays whole and the row wraps between them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Ao5TimesRow(encoded: String, modifier: Modifier = Modifier) {
    val times = remember(encoded) { Ao5.parseTimes(encoded) }
    val trimmed = remember(times) { Ao5.trimmedIndices(times) }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        times.forEachIndexed { index, ms ->
            val text = ms?.let(::formatDuration) ?: stringResource(Res.string.solve_dnf)
            val dropped = index in trimmed
            Text(
                text = if (dropped) "($text)" else text,
                // Dimmed as well as bracketed. The brackets carry the
                // meaning for anyone who knows the convention; the
                // contrast carries it for everyone else, and the two
                // always agree.
                color = if (dropped) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}
