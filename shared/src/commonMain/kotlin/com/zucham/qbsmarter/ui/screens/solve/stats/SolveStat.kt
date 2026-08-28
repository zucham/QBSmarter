package com.zucham.qbsmarter.ui.screens.solve.stats

import com.zucham.qbsmarter.data.db.SolveRow
import org.jetbrains.compose.resources.StringResource

/**
 * One stat row on the Solve screen. The contract is intentionally string-
 * shaped – a stat is a value the user reads, not data we plot. Returning
 * null hides the row (used for Ao5 with too few solves).
 *
 * The label is a [StringResource] so it localizes; resolution happens at
 * the composable call site via stringResource().
 */
interface SolveStat {
    val id: String
    val label: StringResource
    fun compute(history: List<SolveRow>, current: SolveSession): String?

    /**
     * Optional trailing note rendered next to [label], small and dimmed.
     *
     * Exists so a tile can carry a second number without taking a second
     * line. The grid gives every tile the same height, and a value that
     * wrapped would make its whole row taller than the one above it; the
     * label has room to spare. `MeanStat` uses it for the solve count —
     * "Mean · 482" reads as one fact about a body of solves, which is
     * what it is, and frees the tile that used to hold the count alone.
     *
     * Null (the default) renders the label unchanged.
     */
    fun labelSuffix(history: List<SolveRow>, current: SolveSession): String? = null
}

/**
 * Live-solve snapshot passed to stats while a solve is in flight.
 *
 * [totalSolves] is the count of all persisted solves for the active
 * profile. Read by [SolveStat.labelSuffix] on the mean tile; per-solve
 * stats ignore it. Sourced from the cache so it's O(1) to read at every
 * frame the stat grid recomposes.
 *
 * [bestDurationMs] is the all-time best (effective time, DNFs excluded)
 * for the active profile. Sourced from the cache, which is fed by an
 * indexed `MIN(...)` SQL query – this is the **canonical** value the
 * BestStat displays. The recent-solves [history] window the stat also
 * receives only holds the most recent 100 solves; the all-time best can
 * be older than that and would be invisible if BestStat tried to
 * compute it from the window alone. Equally important: the running
 * solve's in-flight duration is **never** mixed in here (an earlier
 * BestStat implementation `min`'d the running time and the historical
 * best, which made the "fastest solve" tile track the live timer
 * instead of the saved DB record).
 */
data class SolveSession(
    val running: Boolean,
    val durationMs: Long,
    val moveCount: Int,
    val totalSolves: Long = 0L,
    val bestDurationMs: Long? = null,
    /**
     * All-time best Ao5 for the active profile, from the cache's
     * `bestAo5Ms`. Same reasoning as [bestDurationMs]: the recent-solves
     * window cannot see far enough back to find it, and the running
     * solve is never mixed in.
     */
    val bestAo5Ms: Long? = null,
)
