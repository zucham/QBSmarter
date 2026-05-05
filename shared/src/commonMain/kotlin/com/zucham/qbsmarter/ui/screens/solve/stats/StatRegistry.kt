package com.zucham.qbsmarter.ui.screens.solve.stats

import com.zucham.qbsmarter.ui.screens.solve.stats.StatRegistry.Companion.DEFAULT
import com.zucham.qbsmarter.ui.screens.solve.stats.builtin.Ao12Stat
import com.zucham.qbsmarter.ui.screens.solve.stats.builtin.Ao5Stat
import com.zucham.qbsmarter.ui.screens.solve.stats.builtin.FastestStat
import com.zucham.qbsmarter.ui.screens.solve.stats.builtin.FluencyStat
import com.zucham.qbsmarter.ui.screens.solve.stats.builtin.MeanStat
import com.zucham.qbsmarter.ui.screens.solve.stats.builtin.TotalSolvesStat

/**
 * Stat registry. New stat = one class + one entry in [DEFAULT].
 * Mutable on purpose so we can later let settings reorder/hide entries.
 */
class StatRegistry(initial: List<SolveStat> = DEFAULT) {
    private val stats: MutableList<SolveStat> = initial.toMutableList()
    val all: List<SolveStat> get() = stats.toList()

    companion object {
        /**
         * Default order for stat tiles. Layout-wise we have a 3-column
         * grid; with `StepTimesStat` filtered out (always returns null
         * until step detection lands) we render 6 visible tiles = 2 rows
         * of 3. Best/Mean/Ao5/Ao12 cluster the time-based numbers; TPS
         * and Total close out the second row.
         */
        val DEFAULT: List<SolveStat> = listOf(
            FluencyStat(), Ao5Stat(), Ao12Stat(),
            FastestStat(), MeanStat(), TotalSolvesStat(),
        )
    }
}
