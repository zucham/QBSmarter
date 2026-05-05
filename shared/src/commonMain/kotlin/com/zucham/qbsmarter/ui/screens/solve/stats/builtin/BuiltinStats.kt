package com.zucham.qbsmarter.ui.screens.solve.stats.builtin

import com.zucham.qbsmarter.data.db.SolveRow
import com.zucham.qbsmarter.ui.screens.solve.stats.SolveSession
import com.zucham.qbsmarter.ui.screens.solve.stats.SolveStat
import com.zucham.qbsmarter.util.formatDuration
import com.zucham.qbsmarter.util.formatTps
import qbsmarter.shared.generated.resources.Res
import qbsmarter.shared.generated.resources.stat_ao12
import qbsmarter.shared.generated.resources.stat_ao5
import qbsmarter.shared.generated.resources.stat_best
import qbsmarter.shared.generated.resources.stat_fluency
import qbsmarter.shared.generated.resources.stat_mean
import qbsmarter.shared.generated.resources.stat_total

/**
 * All-time fastest solve. Sourced from [SolveSession.bestDurationMs],
 * which the VM populates from `AppCache.bestDurationMs` (an indexed
 * `MIN(duration_ms + penalty_ms)` over the active profile, DNFs
 * excluded). When caching is disabled the cache emits null and we fall
 * back to the recent-solves window's minimum – not perfect (a best
 * older than the 100-row window would slip out) but honest.
 *
 * Crucially, the in-flight solve duration is **not** considered here.
 * An earlier implementation min'd the running timer with the historical
 * best, which made the tile tick down with the live timer the moment
 * the running solve dropped below the previous record – confusing
 * because the on-screen "fastest solve" effectively duplicated the main
 * timer until SOLVED committed the new row. The displayed value now
 * always reflects the persisted DB record, which is what the user
 * expects to compare their current solve against.
 */
class FastestStat : SolveStat {
    override val id = "best"
    override val label = Res.string.stat_best
    override fun compute(history: List<SolveRow>, current: SolveSession): String? {
        val best = current.bestDurationMs
            ?: history.minOfOrNull { it.effectiveMs }
            ?: return null
        return formatDuration(best)
    }
}

class MeanStat : SolveStat {
    override val id = "mean"
    override val label = Res.string.stat_mean
    override fun compute(history: List<SolveRow>, current: SolveSession): String? {
        if (history.isEmpty()) return null
        return formatDuration(history.map { it.durationMs }.average().toLong())
    }
}

class Ao5Stat : SolveStat {
    override val id = "ao5"
    override val label = Res.string.stat_ao5
    override fun compute(history: List<SolveRow>, current: SolveSession): String? =
        trimmedAverage(history.map { it.durationMs }.take(5))?.let(::formatDuration)
}

class Ao12Stat : SolveStat {
    override val id = "ao12"
    override val label = Res.string.stat_ao12
    override fun compute(history: List<SolveRow>, current: SolveSession): String? =
        trimmedAverage(history.map { it.durationMs }.take(12))?.let(::formatDuration)
}

/** WCA-ish trimmed average: drop top/bottom 5% (≥1 each). Returns null if too few. */
private fun trimmedAverage(times: List<Long>): Long? {
    if (times.size < 5) return null
    val n = times.size
    val trim = (n * 0.05).toInt().coerceAtLeast(1)
    val middle = times.sorted().subList(trim, n - trim)
    return if (middle.isEmpty()) null else middle.average().toLong()
}

/** Turns per second across either the current solve or the last persisted one. */
class FluencyStat : SolveStat {
    override val id = "fluency"
    override val label = Res.string.stat_fluency
    override fun compute(history: List<SolveRow>, current: SolveSession): String? {
        if (current.running && current.moveCount > 0 && current.durationMs > 0) {
            val tps = current.moveCount * 1000.0 / current.durationMs
            return "${formatTps(tps)} tps"
        }
        return history.firstOrNull()?.fluency?.let { "${formatTps(it)} tps" }
    }
}


/**
 * All-time solve count for the active profile. Sourced from
 * [SolveSession.totalSolves], which the VM populates from the cache –
 * the recent-100 [history] list is not enough because a profile may have
 * thousands of older solves that are no longer in the in-memory window.
 *
 * Returns null when the count is zero so a fresh profile doesn't show
 * a "Total: 0" tile (the "this stat doesn't apply yet" convention used
 * by Ao5/Ao12).
 */
class TotalSolvesStat : SolveStat {
    override val id = "total"
    override val label = Res.string.stat_total
    override fun compute(history: List<SolveRow>, current: SolveSession): String? =
        if (current.totalSolves <= 0L) null else current.totalSolves.toString()
}
