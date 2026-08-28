package com.zucham.qbsmarter.ui.screens.solve.stats

import com.zucham.qbsmarter.ui.screens.solve.stats.StatRegistry.Companion.DEFAULT
import com.zucham.qbsmarter.ui.screens.solve.stats.builtin.Ao12Stat
import com.zucham.qbsmarter.ui.screens.solve.stats.builtin.Ao5Stat
import com.zucham.qbsmarter.ui.screens.solve.stats.builtin.BestAo5Stat
import com.zucham.qbsmarter.ui.screens.solve.stats.builtin.FastestStat
import com.zucham.qbsmarter.ui.screens.solve.stats.builtin.FluencyStat
import com.zucham.qbsmarter.ui.screens.solve.stats.builtin.MeanStat

/**
 * Stat registry. New stat = one class + one entry in [DEFAULT].
 * Mutable on purpose so we can later let settings reorder/hide entries.
 */
class StatRegistry(initial: List<SolveStat> = DEFAULT) {
    private val stats: MutableList<SolveStat> = initial.toMutableList()
    val all: List<SolveStat> get() = stats.toList()

    companion object {
        /**
         * Default order for stat tiles: a 3-column grid, six visible
         * tiles, two clean rows.
         *
         * Row 1 is the solve you just did — turn rate, and the two
         * rolling averages it moved. Row 2 is the profile: best single,
         * mean, best Ao5. Reading down a column therefore pairs each
         * rolling number with its all-time counterpart, which is the
         * comparison a solver is actually making.
         *
         * `TotalSolvesStat` was removed rather than pushed onto a third
         * row of one. The count now rides on the mean tile as a label
         * suffix (see `MeanStat.labelSuffix`) — it is a caption for the
         * mean more than a statistic in its own right, and the tile it
         * freed went to the best Ao5, which had nowhere else to go.
         */
        val DEFAULT: List<SolveStat> = listOf(
            FluencyStat(), Ao5Stat(), Ao12Stat(),
            FastestStat(), MeanStat(), BestAo5Stat(),
        )
    }
}
