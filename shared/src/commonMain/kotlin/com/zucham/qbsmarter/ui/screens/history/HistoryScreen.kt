package com.zucham.qbsmarter.ui.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zucham.qbsmarter.data.db.SolveRow
import com.zucham.qbsmarter.data.db.SolveSort
import com.zucham.qbsmarter.domain.reconstruction.TrackedMove
import com.zucham.qbsmarter.domain.stats.Ao5
import com.zucham.qbsmarter.ui.components.ConfirmationDialog
import com.zucham.qbsmarter.ui.components.DialogButton
import com.zucham.qbsmarter.ui.components.DialogButtonEmphasis
import com.zucham.qbsmarter.ui.components.VerticalScrollbarBox
import com.zucham.qbsmarter.util.formatDuration
import com.zucham.qbsmarter.util.formatTps
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import qbsmarter.shared.generated.resources.Res
import qbsmarter.shared.generated.resources.devices_cancel
import qbsmarter.shared.generated.resources.history_ao5_times
import qbsmarter.shared.generated.resources.history_ao5_trimmed_hint
import qbsmarter.shared.generated.resources.history_close
import qbsmarter.shared.generated.resources.history_cube
import qbsmarter.shared.generated.resources.history_date
import qbsmarter.shared.generated.resources.history_delete
import qbsmarter.shared.generated.resources.history_delete_message
import qbsmarter.shared.generated.resources.history_delete_title
import qbsmarter.shared.generated.resources.history_moves
import qbsmarter.shared.generated.resources.history_moves_none
import qbsmarter.shared.generated.resources.history_scramble_label
import qbsmarter.shared.generated.resources.history_sort_fastest
import qbsmarter.shared.generated.resources.history_sort_newest
import qbsmarter.shared.generated.resources.history_sort_oldest
import qbsmarter.shared.generated.resources.history_sort_worst
import qbsmarter.shared.generated.resources.history_swipe_hint
import qbsmarter.shared.generated.resources.history_total_one
import qbsmarter.shared.generated.resources.history_total_other
import qbsmarter.shared.generated.resources.history_turns
import qbsmarter.shared.generated.resources.solve_dnf
import qbsmarter.shared.generated.resources.stat_ao5
import qbsmarter.shared.generated.resources.stat_fluency

/**
 * History screen. Renders the loaded window of solves into a [LazyColumn]
 * and asks the VM to extend the window when the user scrolls within
 * [PREFETCH_TRIGGER] items of the bottom.
 *
 * Pagination is a plain `StateFlow` window (see [HistoryViewModel]) – no
 * paging library involved.
 */
@Composable
fun HistoryScreen() {
    val vm: HistoryViewModel = koinViewModel()
    val sort by vm.sort.collectAsState()
    val window by vm.window.collectAsState()
    val items = window.rows
    val loading by vm.loading.collectAsState()
    val atEnd by vm.atEnd.collectAsState()
    val total by vm.totalCount.collectAsState()
    val listState = rememberLazyListState()

    // Refresh whenever the screen re-enters composition – picks up rows
    // inserted by the timer while we were elsewhere. `anchorTop = true`
    // makes the reload a new window generation, which the effect below
    // turns into a scroll reset: navigation restores the previous
    // `LazyListState` (the saveState/restoreState pattern wired in
    // AppNavHost), so without it, coming back to History from another
    // screen would land the user mid-list.
    LaunchedEffect(Unit) {
        vm.refresh(anchorTop = true)
    }

    // Every window reset – sort change, profile switch, screen entry –
    // arrives as a new generation published together with its rows, so
    // this effect fires in the same recomposition that first shows the
    // new list, and lands the user at the top of it.
    //
    // This replaces an earlier scheme that waited on `vm.loading` cycling
    // false → true → false before scrolling. That was a race: `setSort`
    // writes to the VM's sort flow synchronously, and the VM's collector
    // frequently flipped `loading` to true *before* the recomposition
    // that started the waiting effect. The effect's `drop(1)` then ate
    // the only `true` this cycle would ever emit and it sat waiting for
    // the next load that might never come, leaving the scroll reset
    // unapplied. That is what left the list parked at the bottom after
    // switching between e.g. Best and Worst: those two sorts are near
    // reverses of each other, so LazyColumn's key-based anchoring found
    // the row that had been at the top now sitting at the far end of the
    // window and dutifully scrolled there.
    LaunchedEffect(window.generation) {
        listState.scrollToItem(0)
    }

    // Watch the visible window; ask for more when close to the bottom.
    // Keying just on listState – the snapshotFlow re-reads layoutInfo
    // and the VM state (items/loading/atEnd) on every emission, so we
    // don't need to relaunch the effect on those changes.
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = layoutInfo.totalItemsCount
            (total - lastVisible).coerceAtLeast(0)
        }
            .collect { distanceFromEnd ->
                if (distanceFromEnd <= PREFETCH_TRIGGER) {
                    // VM coalesces concurrent calls and short-circuits
                    // when already loading or at-end.
                    vm.maybeLoadMore()
                }
            }
    }

    // The open detail dialog lives in the ViewModel, not here: opening it
    // reads the solve's move track from the database, and holding it in
    // composable state would re-run that query on every configuration
    // change and lose it on every process death.
    val detail by vm.detail.collectAsState()
    var pendingDelete by remember { mutableStateOf<SolveRow?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // Total count header. Two keys (one / other) so locales with
        // different plural rules can render the count correctly.
        val totalKey = if (total == 1L) Res.string.history_total_one
                       else Res.string.history_total_other
        Text(
            text = stringResource(totalKey, total.toInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        SortBar(sort, vm::setSort)
        Text(
            stringResource(Res.string.history_swipe_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )

        // VerticalScrollbarBox draws the scrollbar in its own gutter
        // alongside the LazyColumn. The gutterEnd value it hands back is
        // the right padding the LazyColumn applies so its rows don't
        // sit under the scrollbar track.
        VerticalScrollbarBox(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(top = 4.dp),
        ) { gutterEnd ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = gutterEnd),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Keys are scoped to the window generation, so a reset
                // publishes a list whose keys share nothing with the one
                // it replaces. That switches off LazyColumn's key-based
                // scroll anchoring across resets – it can't re-find the
                // previous first-visible row, so it can't chase it to
                // wherever the new sort put it. Within one generation the
                // keys are stable, so appending a page (or optimistically
                // dropping a deleted row) still keeps the user's place.
                items(
                    items = items,
                    key = { row -> "${window.generation}#${row.id}" },
                ) { row ->
                    SwipeableSolveItem(
                        row = row,
                        pendingDeleteId = pendingDelete?.id,
                        onTap = { vm.openDetail(row) },
                        onSwipedToDelete = { pendingDelete = row },
                    )
                }
                if (loading && !atEnd) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                    }
                }
            }
        }
    }

    detail?.let { open ->
        SolveDetailDialog(
            detail = open,
            onDelete = { pendingDelete = open.row },
            onDismiss = vm::closeDetail,
        )
    }

    pendingDelete?.let { row ->
        ConfirmationDialog(
            title = stringResource(Res.string.history_delete_title),
            message = stringResource(Res.string.history_delete_message),
            confirmLabel = stringResource(Res.string.history_delete),
            cancelLabel = stringResource(Res.string.devices_cancel),
            onConfirm = {
                // vm.delete closes the detail dialog itself when it is
                // showing the row being deleted – the dialog's lifetime
                // belongs to whoever owns the data, not to the
                // confirmation that triggered it.
                vm.delete(row.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** Distance-from-bottom (in items) that triggers another window expansion. */
private const val PREFETCH_TRIGGER = 10

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SortBar(sort: SolveSort, onChange: (SolveSort) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SortChip(sort == SolveSort.DATE_DESC, stringResource(Res.string.history_sort_newest)) {
            onChange(SolveSort.DATE_DESC)
        }
        SortChip(sort == SolveSort.DATE_ASC, stringResource(Res.string.history_sort_oldest)) {
            onChange(SolveSort.DATE_ASC)
        }
        SortChip(sort == SolveSort.BEST_TIME, stringResource(Res.string.history_sort_fastest)) {
            onChange(SolveSort.BEST_TIME)
        }
        SortChip(sort == SolveSort.WORST_TIME, stringResource(Res.string.history_sort_worst)) {
            onChange(SolveSort.WORST_TIME)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp, maxLines = 1) },
        modifier = Modifier.wrapContentHeight(),
        colors = FilterChipDefaults.filterChipColors(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableSolveItem(
    row: SolveRow,
    pendingDeleteId: Long?,
    onTap: () -> Unit,
    onSwipedToDelete: () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.33f },
    )

    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onSwipedToDelete()
        }
    }

    LaunchedEffect(pendingDeleteId, row.id) {
        if (pendingDeleteId != row.id && state.currentValue != SwipeToDismissBoxValue.Settled) {
            state.reset()
        }
    }

    SwipeToDismissBox(
        state = state,
        // End-to-start only (right-to-left in LTR locales). Start-to-end
        // is the navigation drawer's open gesture: a drag that begins on
        // a list row and travels that way is ambiguous, and whichever
        // handler wins the race, one of the two feels broken. Dragging
        // the other way belongs to nothing else, so delete gets it
        // outright. Direction-relative rather than hard "right to left"
        // so the pair stays non-conflicting under an RTL layout, where
        // both gestures mirror together.
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = { SwipeBackground() },
    ) {
        SolveListItem(row, onClick = onTap)
    }
}

@Composable
private fun SwipeBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 24.dp),
        // The label sits on the end edge – the side the row uncovers as
        // it travels away from it.
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text = stringResource(Res.string.history_delete),
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SolveListItem(row: SolveRow, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        // surfaceContainerLow is one step above the page background in
        // both modes – darker than the page in light mode (page is
        // surface = #FFFFFF; this is #F2F2F6) and lighter than the
        // page in dark mode (page is background = #0B0B0D; this is
        // #1A1A1D). Material 3's default Card color
        // (surfaceContainerHigh) was too dark for a list item that
        // sits on a page background in both modes; the previous
        // surfaceContainerLowest override was *too light* in light
        // mode (basically the page color) and *too dark* in dark
        // mode (below page brightness), so it failed both tasks.
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = displayDuration(row),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 24.sp,
                color = if (row.isDnf) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatDate(row.solvedAt),
                    style = MaterialTheme.typography.bodyMedium,
                )
                row.ao5Ms?.let {
                    Text(
                        text = stringResource(Res.string.stat_ao5) + " " + formatDuration(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Display a solve's time according to its DNF/penalty state:
 *   - DNF → "DNF"
 *   - Has +2 → "X.XX+" (effective time + plus marker)
 *   - Otherwise raw effective time
 */
private fun displayDuration(row: SolveRow): String = when {
    row.isDnf -> "DNF"
    row.penaltyMs > 0 -> formatDuration(row.effectiveMs) + "+"
    else -> formatDuration(row.effectiveMs)
}

/**
 * A labelled line in the detail dialog: bold caption, value beside it.
 * Pulled out because the dialog now has seven of them and the
 * `Row { Text(bold); Text(value) }` pattern was being retyped each time.
 */
@Composable
private fun DetailRow(label: String, value: String, monospace: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label: ", fontWeight = FontWeight.Black)
        Text(value, fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default)
    }
}

/**
 * The five times an Ao5 was computed from, oldest first, with the two
 * that did not count in brackets.
 *
 * Which two those are comes from [Ao5.trimmedIndices] rather than from a
 * `min`/`max` here, so the brackets can never disagree with the average
 * printed above them. It matters more than it looks: a DNF is the
 * *slowest* result rather than a missing one, and two entries tied for
 * fastest drop only one of themselves — both cases a local min/max would
 * mark wrongly.
 *
 * Rendered as one wrapping [FlowRow] of monospace tokens instead of a
 * single string. Five times plus brackets overflow the dialog's width on
 * a narrow phone, and a plain Text would either clip them or break the
 * line mid-number; this way each time stays whole and the row wraps
 * between them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Ao5TimesRow(encoded: String) {
    val times = remember(encoded) { Ao5.parseTimes(encoded) }
    val trimmed = remember(times) { Ao5.trimmedIndices(times) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        times.forEachIndexed { index, ms ->
            val text = ms?.let(::formatDuration) ?: stringResource(Res.string.solve_dnf)
            val dropped = index in trimmed
            Text(
                text = if (dropped) "($text)" else text,
                fontFamily = FontFamily.Monospace,
                // The dropped pair is dimmed as well as bracketed. The
                // brackets carry the meaning for anyone who knows the
                // convention; the weight difference carries it for
                // everyone else, and the two agree.
                color = if (dropped) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/**
 * The turns of the solve, as standard notation.
 *
 * The stored track carries a millisecond timestamp per move — that is
 * what makes a full reconstruction possible later — but the dialog shows
 * only the sequence and the count. A list of fifty-five timestamps is not
 * something anyone reads; when the timings are surfaced it should be as
 * a replay or a graph, and neither belongs in an AlertDialog.
 *
 * **Quarter turns are not collapsed into half turns.** A physical `R2`
 * reaches the app as two `R` events and is stored as two moves with two
 * timestamps, so that is what is shown. Rendering it as `R2` would read
 * more like a scramble, but it would also disagree with the "Turns"
 * count directly above it, and it would throw away the distinction the
 * reconstruction data exists to preserve — the two halves of a half turn
 * happen at different moments.
 *
 * No scroll or height cap of its own: the dialog's content column
 * already scrolls, and a second vertical scroller inside it would swallow
 * the drag before the outer one ever saw it, leaving the rest of the
 * dialog unreachable on a short screen.
 */
@Composable
private fun MoveSequence(moves: List<TrackedMove>) {
    val notation = remember(moves) {
        moves.joinToString(" ") { move -> move.face.name + if (move.cw) "" else "'" }
    }
    Text(
        text = notation,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SolveDetailDialog(
    detail: SolveDetail,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val row = detail.row
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(displayDuration(row)) },
        text = {
            // The dialog's own content scrolls: with the Ao5 window, the
            // scramble and the move sequence it is now taller than a
            // small phone in landscape, and AlertDialog does not scroll
            // its text slot for you.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                DetailRow(stringResource(Res.string.history_date), formatDate(row.solvedAt))

                // Which cube. Absent entirely for solves recorded before
                // the column existed rather than shown as "unknown" —
                // there is no fact to report, and a row saying so would
                // appear on every historical solve forever.
                detail.cubeLabel?.let {
                    DetailRow(stringResource(Res.string.history_cube), it)
                }

                Text(
                    stringResource(Res.string.history_scramble_label),
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(row.scramble, fontFamily = FontFamily.Monospace)

                row.ao5Ms?.let {
                    DetailRow(stringResource(Res.string.stat_ao5), formatDuration(it), monospace = true)
                }
                // The five constituent times. Shown whenever they exist,
                // which is not the same condition as the average existing
                // — a window holding two DNFs has five real times and no
                // average, and those five are exactly the ones worth
                // looking at.
                row.ao5Times?.let { encoded ->
                    Text(
                        stringResource(Res.string.history_ao5_times),
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Ao5TimesRow(encoded)
                    Text(
                        stringResource(Res.string.history_ao5_trimmed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                row.fluency?.let {
                    DetailRow(stringResource(Res.string.stat_fluency), formatTps(it))
                }
                // Total turns recorded during the solve. The 0-guard
                // hides the row for solves that pre-date the
                // moveCount column (default-0 by SQL) so we don't
                // misleadingly show "Turns: 0" for genuine pre-feature
                // data. New solves – including ones with a single
                // recorded turn – pass the guard normally.
                if (row.moveCount > 0) {
                    DetailRow(stringResource(Res.string.history_turns), row.moveCount.toString())
                }

                // The move sequence, once the track has been read. While
                // `loaded` is false we render nothing at all rather than
                // a spinner or a "none recorded" line: the read is a
                // primary-key lookup that resolves in a frame or two, and
                // either placeholder would flash.
                if (detail.loaded) {
                    Text(
                        stringResource(Res.string.history_moves),
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (detail.moves.isEmpty()) {
                        Text(
                            stringResource(Res.string.history_moves_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        MoveSequence(detail.moves)
                    }
                }
            }
        },
        confirmButton = {
            DialogButton(
                label = stringResource(Res.string.history_delete),
                onClick = onDelete,
                emphasis = DialogButtonEmphasis.DESTRUCTIVE,
            )
        },
        dismissButton = {
            DialogButton(
                label = stringResource(Res.string.history_close),
                onClick = onDismiss,
                emphasis = DialogButtonEmphasis.NEUTRAL,
            )
        },
    )
}

private fun formatDate(epochMs: Long): String {
    val dt = kotlin.time.Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dt.date} ${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
}
