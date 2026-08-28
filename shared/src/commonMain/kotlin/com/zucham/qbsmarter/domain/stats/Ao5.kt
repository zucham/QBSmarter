package com.zucham.qbsmarter.domain.stats

/**
 * The average-of-5 rule, in one place.
 *
 * There are three implementations of this arithmetic in the app — the
 * live one that runs when a solve finishes, the recompute that runs when
 * a penalty is edited or a solve deleted, and the SQL in `3.sqm` that
 * backfilled the whole history. If they disagree, a solve shows five
 * times whose average is visibly not the average printed next to them,
 * and there is no way for the user to tell which number is wrong. The
 * first two both call this; the third is a transcription of these same
 * rules into SQL, and is commented as such at both ends.
 *
 * ## The rule
 *
 * An Ao5 is over the five most recent solves ending at (and including)
 * the one it belongs to, working in **effective** time — `duration_ms +
 * penalty_ms`, so a +2 counts — and treating a DNF as having no time at
 * all rather than as a large one.
 *
 *  * fewer than five solves so far → no window, no average;
 *  * five valid times → drop the best and the worst, mean the middle
 *    three;
 *  * four valid times and one DNF → the DNF *is* the worst, so drop it
 *    and the best, and mean the remaining three;
 *  * two or more DNFs → no average. Two of the middle three would have
 *    no time, and the WCA rule is that the average is itself a DNF.
 *
 * The last case is why [Result.times] and [Result.ao5Ms] are nullable
 * independently: a window can exist and still produce no number.
 */
object Ao5 {

    /** How many solves an Ao5 spans. */
    const val WINDOW = 5

    /** How a DNF is written inside [Result.times]. */
    const val DNF_TOKEN = "D"

    /**
     * @property ao5Ms the average, or null when the window is short or
     *   holds two or more DNFs.
     * @property times the five effective times oldest-first, DNFs written
     *   as [DNF_TOKEN], or null when there was no full window. Non-null
     *   with a null [ao5Ms] is a legitimate state — see the class note.
     */
    data class Result(val ao5Ms: Long?, val times: String?)

    /** Nothing to average. */
    val NONE = Result(null, null)

    /**
     * @param window the solves ending at the one being computed,
     *   **oldest first**. Shorter than [WINDOW] yields [NONE]. A longer
     *   list is trimmed from the front rather than the back, which keeps
     *   the "window ending at this solve" reading instead of silently
     *   averaging the wrong five.
     */
    fun compute(window: List<Ao5Entry>): Result {
        if (window.size < WINDOW) return NONE
        val five = window.subList(window.size - WINDOW, window.size)
        val times = five.joinToString(",") { entry ->
            if (entry.isDnf) DNF_TOKEN else entry.effectiveMs.toString()
        }
        val valid = five.filter { !it.isDnf }.map { it.effectiveMs }
        val ao5 = when (valid.size) {
            WINDOW -> (valid.sum() - valid.min() - valid.max()) / 3
            WINDOW - 1 -> (valid.sum() - valid.min()) / 3
            else -> null
        }
        return Result(ao5, times)
    }

    /**
     * Read back a stored [Result.times] as effective times, oldest first,
     * with null for each DNF.
     *
     * Tolerant by design: an unparseable entry decodes as a DNF rather
     * than failing the whole row. A History screen that refuses to show a
     * solve because one of five numbers behind one of its statistics does
     * not parse is a much worse outcome than one that shows that entry as
     * a dash.
     */
    fun parseTimes(encoded: String?): List<Long?> =
        encoded?.split(',')?.map { it.trim().toLongOrNull() } ?: emptyList()
}

/** The two things an Ao5 needs from a solve. */
interface Ao5Entry {
    val effectiveMs: Long
    val isDnf: Boolean
}
