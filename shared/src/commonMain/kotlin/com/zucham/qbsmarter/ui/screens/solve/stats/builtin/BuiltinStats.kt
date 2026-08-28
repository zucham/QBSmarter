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
import qbsmarter.shared.generated.resources.stat_best_ao5
import qbsmarter.shared.generated.resources.stat_fluency
import qbsmarter.shared.generated.resources.stat_mean

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
            ?: history.filter { !it.isDnf }.minOfOrNull { it.effectiveMs }
            ?: return null
        return formatDuration(best)
    }
}

/**
 * Mean of every solve in the window, in **effective** time, with DNFs
 * left out.
 *
 * Both of those used to be wrong: it averaged raw `durationMs`, so a +2
 * never showed up in the mean, and it included DNFs at their recorded
 * time, so a solve the user explicitly marked as failed pulled the
 * average around as if it had counted. `Solves.sq` has always documented
 * that stats work in effective time and skip DNFs; this now does.
 */
class MeanStat : SolveStat {
    override val id = "mean"
    override val label = Res.string.stat_mean
    override fun compute(history: List<SolveRow>, current: SolveSession): String? {
        val times = history.filter { !it.isDnf }.map { it.effectiveMs }
        if (times.isEmpty()) return null
        return formatDuration(times.average().toLong())
    }

    /**
     * The profile's all-time solve count, shown beside the label.
     *
     * It used to have a tile of its own. Folding it in here costs nothing
     * — a mean and the number of solves it is a mean *of* are one thought,
     * and the tile it vacated now holds the best Ao5 — but note the two
     * numbers do not describe the same set: the mean is over the recent
     * window the stat grid receives, the count is the whole history. That
     * is the pre-existing behaviour of both, kept deliberately rather
     * than quietly changed while moving them together.
     */
    override fun labelSuffix(history: List<SolveRow>, current: SolveSession): String? =
        if (current.totalSolves <= 0L) null else current.totalSolves.toString()
}

/**
 * The most recent solve's Ao5, read straight off its persisted
 * `ao5_ms` rather than recomputed here.
 *
 * Recomputing was how the stat card and the History row came to disagree:
 * the card trimmed a sorted window of raw durations, the stored column
 * was written by a third piece of code, and neither applied the DNF rule.
 * The database now holds a value maintained by `Ao5` on every path that
 * can change it, so the card's job is to display it, not to have an
 * opinion about it.
 *
 * A null `ao5Ms` on the newest solve means either fewer than five solves
 * or a window with two or more DNFs; both correctly hide the row.
 */
class Ao5Stat : SolveStat {
    override val id = "ao5"
    override val label = Res.string.stat_ao5
    override fun compute(history: List<SolveRow>, current: SolveSession): String? =
        history.firstOrNull()?.ao5Ms?.let(::formatDuration)
}

/**
 * Ao12 over the newest twelve solves. Still computed here — unlike Ao5
 * there is no persisted column to read — but under the same rules
 * `Ao5` applies: effective time, and a DNF is the worst result rather
 * than a number.
 */
class Ao12Stat : SolveStat {
    override val id = "ao12"
    override val label = Res.string.stat_ao12
    override fun compute(history: List<SolveRow>, current: SolveSession): String? =
        trimmedAverage(history.take(12))?.let(::formatDuration)
}

/**
 * WCA-style trimmed average: drop the fastest and slowest 5% (at least
 * one each) and mean the rest.
 *
 * DNFs are not "very slow times" – they have no time at all – so they
 * are counted, trimmed as the worst results, and never contribute a
 * number to the mean. If more of them are trimmed away than the trim
 * allows for, the average is itself a DNF and this returns null, which
 * is the same rule `Ao5` applies to a window holding two DNFs.
 */
private fun trimmedAverage(window: List<SolveRow>): Long? {
    val n = window.size
    if (n < 5) return null
    val trim = (n * 0.05).toInt().coerceAtLeast(1)
    val dnfCount = window.count { it.isDnf }
    if (dnfCount > trim) return null
    val times = window.filter { !it.isDnf }.map { it.effectiveMs }.sorted()
    // The DNFs have already been removed from the top; only the
    // remaining slow end still needs trimming, along with the fast end.
    val middle = times.subList(trim, (n - trim).coerceAtMost(times.size))
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
 * Best Ao5 the profile has ever recorded — the Ao5 counterpart to
 * [FastestStat], and the value the Ao5 record celebration is measured
 * against.
 *
 * Sourced from [SolveSession.bestAo5Ms] (the cache's indexed
 * `MIN(ao5_ms)`), not from [history]. The window is the most recent 100
 * solves and a best Ao5 is very often older than that, so computing it
 * here would silently show a "best" that is merely the best *lately* —
 * and would disagree with the record popup, which reads the real one.
 *
 * The fall-back to the window exists only for caching-disabled mode, and
 * is honest rather than correct: it is the best Ao5 still in memory. Null
 * before the profile has five solves, which hides the tile.
 */
class BestAo5Stat : SolveStat {
    override val id = "bestAo5"
    override val label = Res.string.stat_best_ao5
    override fun compute(history: List<SolveRow>, current: SolveSession): String? {
        val best = current.bestAo5Ms
            ?: history.mapNotNull { it.ao5Ms }.minOrNull()
            ?: return null
        return formatDuration(best)
    }
}
