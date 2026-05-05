package com.zucham.qbsmarter.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.zucham.qbsmarter.db.QbsmarterDatabase
import com.zucham.qbsmarter.db.Solves
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
    val isDnf: Boolean,
    val penaltyMs: Long,
    val moveCount: Long,
) {
    /** Total displayed time. DNFs still expose a number for sorting; UI shows "DNF". */
    val effectiveMs: Long get() = durationMs + penaltyMs
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
)

class SolvesRepository(
    private val db: QbsmarterDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Insert a new solve and return its auto-generated id. Wraps the
     * insert + last_insert_rowid in a single transaction so a concurrent
     * insert on another thread can't race in between and steal our id.
     */
    fun insert(
        userId: String, solvedAt: Long, durationMs: Long, scramble: String,
        ao5Ms: Long?, fluency: Double?, extras: String? = null,
        isDnf: Boolean = false, penaltyMs: Long = 0L, moveCount: Long = 0L,
    ): Long = db.transactionWithResult {
        db.solvesQueries.insert(
            userId, solvedAt, durationMs, scramble, ao5Ms, fluency, extras,
            if (isDnf) 1L else 0L, penaltyMs, moveCount,
        )
        db.solvesQueries.lastInsertedId().executeAsOne()
    }

    fun delete(id: Long) = db.solvesQueries.deleteById(id)

    /**
     * Update DNF / penalty flags on an existing solve. Called from the
     * post-solve "DNF" / "+2" buttons on the Solve screen and (in the
     * future) from a context menu on the History screen.
     */
    fun updatePenalty(id: Long, isDnf: Boolean, penaltyMs: Long) =
        db.solvesQueries.updatePenalty(if (isDnf) 1L else 0L, penaltyMs, id)

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
}
