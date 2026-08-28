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
 * ## The rule — WCA 9f8 and 9f9
 *
 * Taken from the regulations verbatim, because the WCA definition is the
 * one this app follows wherever a cubing convention is in question:
 *
 *  * **9f8** — "of these 5 attempts, the best and worst attempts are
 *    removed, and the arithmetic mean of the remaining 3 attempts
 *    determines the competitor's ranking".
 *  * **9f9** — "one DNF or DNS is permitted to count as the competitor's
 *    worst result of the round. If a competitor has more than one DNF
 *    and/or DNS result in the round, their average result for the round
 *    is DNF."
 *
 * The window is the five most recent solves ending at (and including)
 * the one the average belongs to, in **effective** time — `duration_ms +
 * penalty_ms`, so a +2 counts.
 *
 *  * fewer than five solves so far → no window, no average;
 *  * five valid times → drop the best and the worst, mean the middle
 *    three;
 *  * four valid times and one DNF → the DNF *is* the worst (9f9), so
 *    drop it and the best, and mean the remaining three;
 *  * two or more DNFs → no average (9f9).
 *
 * The last case is why [Result.times] and [Result.ao5Ms] are nullable
 * independently: a window can exist and still produce no number.
 *
 * ## A DNF is an attempt, not a gap
 *
 * Worth stating explicitly, because the intuition runs the other way: a
 * DNF solve is **not** skipped when building the windows of the solves
 * that follow it. It occupies one of their five slots and is trimmed
 * there as their worst result, which is exactly what 9f9 describes — so
 * one DNF touches the five averages it appears in, and each of those
 * still averages three real times.
 *
 * Excluding it from those windows instead would mean averaging five
 * *timed* solves drawn from six attempts, which is not an Ao5 of
 * anything the WCA defines.
 *
 * Verified against an independent transcription of 9f8/9f9 over every
 * DNF pattern across five attempts, in several orderings, plus ties.
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
     * Which of the five entries were **dropped** from the average: the
     * fastest and the slowest, by exactly the rule [compute] applies.
     *
     * Returned as indices into the list [parseTimes] produces, so the UI
     * can bracket the two that did not count without re-deriving which
     * ones they were and risking a different answer from the number
     * printed beside them.
     *
     * Three details that a naive "min and max" would get wrong:
     *
     *  * **A DNF is the slowest**, not a missing value to be skipped. If
     *    the window holds one, it is the dropped-slowest and the real
     *    slowest time still counts toward the mean.
     *  * **Ties drop one entry, not both.** Five solves of which two are
     *    the identical fastest time drop one of them; the other is part
     *    of the middle three. Marking both would show four brackets
     *    around a three-solve average.
     *  * **No average, no brackets.** Two or more DNFs mean there is no
     *    Ao5 at all, and brackets whose meaning is "excluded from the
     *    average" are meaningless without one. Returns empty.
     */
    fun trimmedIndices(times: List<Long?>): Set<Int> {
        if (times.size < WINDOW) return emptySet()
        val validCount = times.count { it != null }
        if (validCount < WINDOW - 1) return emptySet()

        val dnfIndex = times.indexOfFirst { it == null }
        val slowest = if (dnfIndex >= 0) {
            dnfIndex
        } else {
            times.indices.maxByOrNull { times[it]!! } ?: return emptySet()
        }
        val fastest = times.indices
            .filter { it != slowest && times[it] != null }
            .minByOrNull { times[it]!! } ?: return emptySet()
        return setOf(slowest, fastest)
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
