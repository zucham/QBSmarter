package com.zucham.qbsmarter.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.zucham.qbsmarter.db.QbsmarterDatabase
import com.zucham.qbsmarter.db.Solves
import com.zucham.qbsmarter.domain.stats.Ao5
import com.zucham.qbsmarter.domain.stats.Ao5Entry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class SolveSort { DATE_DESC, DATE_ASC, BEST_TIME, WORST_TIME }

/**
 * Persisted solve. Effective time (the value displayed and used by stats)
 * is `durationMs + penaltyMs`; raw `durationMs` is preserved so the user
 * can later remove a +2 penalty without losing data. DNF is a separate
 * boolean – those rows are skipped by best/mean/AoN computations.
 *
 * `moveCount` is the total turn count recorded during the solve. Used
 * at runtime to compute live TPS (which is then persisted into
 * `fluency`); kept around as its own field so the History detail
 * dialog can show "turns: N" alongside the time. Stats deliberately do
 * NOT consume `moveCount` – it's a History-only field by product spec.
 *
 * `ao5Times` is the five effective times `ao5Ms` was computed from, in
 * the encoding [Ao5] defines; read it with [ao5TimesList].
 */
data class SolveRow(
    val id: Long,
    val userId: String,
    val solvedAt: Long,
    val durationMs: Long,
    val scramble: String,
    val ao5Ms: Long?,
    val fluency: Double?,
    val extras: String?,
    override val isDnf: Boolean,
    val penaltyMs: Long,
    val moveCount: Long,
    val ao5Times: String? = null,
) : Ao5Entry {
    /** Total displayed time. DNFs still expose a number for sorting; UI shows "DNF". */
    override val effectiveMs: Long get() = durationMs + penaltyMs

    /**
     * The five times behind [ao5Ms], oldest first, null for each DNF.
     * Empty when this solve has no Ao5 window.
     */
    val ao5TimesList: List<Long?> get() = Ao5.parseTimes(ao5Times)
}

private fun Solves.toRow() = SolveRow(
    id = id,
    userId = user_id,
    solvedAt = solved_at,
    durationMs = duration_ms,
    scramble = scramble,
    ao5Ms = ao5_ms,
    fluency = fluency,
    extras = extras,
    isDnf = is_dnf != 0L,
    penaltyMs = penalty_ms,
    moveCount = move_count,
    ao5Times = ao5_times,
)

class SolvesRepository(
    private val db: QbsmarterDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Insert a new solve and return its auto-generated id.
     *
     * The Ao5 is computed **here**, inside the transaction, from the five
     * rows the database actually holds — not passed in by the caller.
     *
     * It used to be passed in, computed by `SolveViewModel` from
     * `AppCache.recentSolves`, and that was wrong in three ways at once.
     * The cache is gated on a user-facing "use caching" setting, so with
     * caching switched off the list was empty, the window never reached
     * five, and `ao5_ms` silently stopped being written at all (taking
     * personal-best detection down with it, since that read the same
     * cache). It averaged raw `duration_ms`, so a +2 didn't count. And it
     * ignored DNFs entirely, so a failed solve entered the average as an
     * ordinary time. Reading the window from the database inside the
     * insert transaction fixes all three, and costs one indexed five-row
     * lookup per solve.
     *
     * The insert, the window read and the Ao5 write are one transaction
     * so that no reader can observe the new row without its average, and
     * so a concurrent insert can't slip between the insert and
     * `last_insert_rowid`.
     */
    fun insert(
        userId: String, solvedAt: Long, durationMs: Long, scramble: String,
        fluency: Double?, extras: String? = null,
        isDnf: Boolean = false, penaltyMs: Long = 0L, moveCount: Long = 0L,
    ): Long = db.transactionWithResult {
        db.solvesQueries.insert(
            userId, solvedAt, durationMs, scramble, null, null, fluency, extras,
            if (isDnf) 1L else 0L, penaltyMs, moveCount,
        )
        val id = db.solvesQueries.lastInsertedId().executeAsOne()
        val ao5 = computeAo5For(userId, solvedAt, id)
        db.solvesQueries.updateAo5(ao5.ao5Ms, ao5.times, id)
        id
    }

    /**
     * Insert a solve from an import bundle, **without** deriving its Ao5.
     *
     * Import is the one caller that must not compute averages row by
     * row. A bundle's solves are not guaranteed to arrive oldest-first,
     * and a solve inserted out of order would average whichever rows
     * happened to be present at that moment — and, worse, would not
     * cause the already-inserted solves after it to be re-derived. The
     * importer therefore inserts everything with no average at all and
     * calls [rebuildAo5ForUser] once at the end, which is both correct
     * regardless of order and cheaper: one pass instead of a five-row
     * window read per imported solve.
     *
     * The bundle's own `ao5Ms` is deliberately not carried across. It
     * was derived from the exporting database's set of solves, and the
     * importing one is a merge of two sets — the number would be a claim
     * about a history that does not exist here.
     */
    fun insertForImport(
        userId: String, solvedAt: Long, durationMs: Long, scramble: String,
        fluency: Double?, extras: String? = null,
        isDnf: Boolean = false, penaltyMs: Long = 0L, moveCount: Long = 0L,
    ): Long = db.transactionWithResult {
        db.solvesQueries.insert(
            userId, solvedAt, durationMs, scramble, null, null, fluency, extras,
            if (isDnf) 1L else 0L, penaltyMs, moveCount,
        )
        db.solvesQueries.lastInsertedId().executeAsOne()
    }

    /**
     * Re-derive `ao5_ms` / `ao5_times` for every solve a profile owns.
     *
     * One ordered pass with a five-row sliding window, writing only the
     * rows whose value actually changes — on a profile that was already
     * consistent this touches nothing, which matters because a write to
     * `solves` invalidates every SQLDelight query listening to the table
     * and would otherwise make the whole app recompose for no reason.
     *
     * Called after an import. Not on any hot path: it reads the profile's
     * entire history.
     */
    fun rebuildAo5ForUser(userId: String) = db.transaction {
        val all = db.solvesQueries.allAscending(userId).executeAsList().map(Solves::toRow)
        for (index in all.indices) {
            val window = all.subList(maxOf(0, index - (Ao5.WINDOW - 1)), index + 1)
            val computed = Ao5.compute(window)
            val row = all[index]
            if (computed.ao5Ms != row.ao5Ms || computed.times != row.ao5Times) {
                db.solvesQueries.updateAo5(computed.ao5Ms, computed.times, row.id)
            }
        }
    }

    /**
     * Delete a solve, then repair the Ao5 of every solve that had it in
     * its window.
     *
     * A stored Ao5 is a fact about five specific solves. Removing one of
     * them without touching the four that followed would leave up to four
     * rows claiming an average over a solve that no longer exists —
     * invisible until the user opened one of them and counted the times.
     */
    fun delete(id: Long) = db.transaction {
        val row = db.solvesQueries.selectById(id).executeAsOneOrNull() ?: return@transaction
        db.solvesQueries.deleteById(id)
        repairAo5After(row.user_id, row.solved_at, row.id)
    }

    /**
     * Update DNF / penalty flags on an existing solve. Called from the
     * post-solve "DNF" / "+2" buttons on the Solve screen and from the
     * History detail dialog.
     *
     * Both flags feed the *effective* time, so changing either one
     * changes this solve's own Ao5 and the Ao5 of the up-to-four solves
     * that follow it — [repairAo5After] covers all five.
     */
    fun updatePenalty(id: Long, isDnf: Boolean, penaltyMs: Long) = db.transaction {
        db.solvesQueries.updatePenalty(if (isDnf) 1L else 0L, penaltyMs, id)
        val row = db.solvesQueries.selectById(id).executeAsOneOrNull() ?: return@transaction
        repairAo5After(row.user_id, row.solved_at, row.id)
    }

    /** The Ao5 of the window ending at the given solve, straight from the DB. */
    private fun computeAo5For(userId: String, solvedAt: Long, id: Long): Ao5.Result {
        // windowEndingAt returns newest-first; Ao5.compute wants oldest-first.
        val window = db.solvesQueries.windowEndingAt(userId, solvedAt, id)
            .executeAsList().map(Solves::toRow).reversed()
        return Ao5.compute(window)
    }

    /**
     * Recompute `ao5_ms` / `ao5_times` for every solve whose window
     * contains the one identified by ([solvedAt], [id]) — that solve
     * itself plus the four after it. Safe to call when the solve has just
     * been deleted: it then simply isn't among the rows returned.
     *
     * Callers must already be inside a transaction.
     */
    private fun repairAo5After(userId: String, solvedAt: Long, id: Long) {
        val affected = db.solvesQueries
            .solvesAffectedByChangeAt(userId, solvedAt, id)
            .executeAsList()
        for (row in affected) {
            val ao5 = computeAo5For(userId, row.solved_at, row.id)
            db.solvesQueries.updateAo5(ao5.ao5Ms, ao5.times, row.id)
        }
    }

    /** Live count, used by stat cards and the History header. */
    fun observeCount(userId: String): Flow<Long> =
        db.solvesQueries.countAll(userId).asFlow().mapToOne(ioDispatcher)
            .distinctUntilChanged()

    fun bestDuration(userId: String): Long? =
        db.solvesQueries.bestDuration(userId).executeAsOne().best

    /** Live "recent N" stream; consumed by [com.zucham.qbsmarter.data.cache.AppCache]. */
    fun recentForStats(userId: String, limit: Long = 100): Flow<List<SolveRow>> =
        db.solvesQueries.recentForStats(userId, limit)
            .asFlow().mapToList(ioDispatcher).map { rows -> rows.map(Solves::toRow) }

    /** Synchronous page fetch for the History paging source. */
    fun page(userId: String, sort: SolveSort, limit: Long, offset: Long): List<SolveRow> {
        val q = when (sort) {
            SolveSort.DATE_DESC -> db.solvesQueries.pageByDateDesc(userId, limit, offset)
            SolveSort.DATE_ASC -> db.solvesQueries.pageByDateAsc(userId, limit, offset)
            SolveSort.BEST_TIME -> db.solvesQueries.pageByDurationAsc(userId, limit, offset)
            SolveSort.WORST_TIME -> db.solvesQueries.pageByDurationDesc(userId, limit, offset)
        }
        return q.executeAsList().map(Solves::toRow)
    }

    /** All solves for a profile, used by export. */
    fun snapshotAllForUser(userId: String): List<SolveRow> =
        db.solvesQueries.recentForStats(userId, Long.MAX_VALUE).executeAsList().map(Solves::toRow)

    /** One solve by id, or null if it has been deleted. */
    fun byId(id: Long): SolveRow? =
        db.solvesQueries.selectById(id).executeAsOneOrNull()?.toRow()
}
