package com.zucham.qbsmarter.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.zucham.qbsmarter.db.QbsmarterDatabase
import com.zucham.qbsmarter.db.Solves
import com.zucham.qbsmarter.domain.reconstruction.GyroTrack
import com.zucham.qbsmarter.domain.reconstruction.MoveTrack
import com.zucham.qbsmarter.domain.reconstruction.TrackCodecs
import com.zucham.qbsmarter.domain.reconstruction.TrackedGyro
import com.zucham.qbsmarter.domain.reconstruction.TrackedMove
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
 * the encoding [Ao5] defines; read it with [ao5TimesList]. `cubeMac`
 * identifies the physical cube the solve was done on, or null for solves
 * recorded before the column existed.
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
    val cubeMac: String? = null,
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
    cubeMac = cube_mac,
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
        cubeMac: String? = null,
    ): Long = db.transactionWithResult {
        db.solvesQueries.insert(
            userId, solvedAt, durationMs, scramble, null, null, fluency, extras,
            if (isDnf) 1L else 0L, penaltyMs, moveCount, cubeMac,
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
        cubeMac: String? = null,
    ): Long = db.transactionWithResult {
        db.solvesQueries.insert(
            userId, solvedAt, durationMs, scramble, null, null, fluency, extras,
            if (isDnf) 1L else 0L, penaltyMs, moveCount, cubeMac,
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

    /**
     * Best (lowest) Ao5 the profile has ever recorded, or null before it
     * has five solves.
     *
     * Like [bestDuration] this is an index seek, not a scan – it reads
     * the persisted per-solve `ao5_ms` through the partial
     * `solves_user_ao5` index rather than recomputing sliding windows.
     * That is only sound because the column is now maintained on every
     * path that can invalidate it (see [insert], [delete],
     * [updatePenalty]) and was backfilled for pre-v2 history.
     */
    fun bestAo5(userId: String): Long? =
        db.solvesQueries.bestAo5(userId).executeAsOne().best

    /** One solve by id, or null if it has been deleted. */
    fun byId(id: Long): SolveRow? =
        db.solvesQueries.selectById(id).executeAsOneOrNull()?.toRow()

    /**
     * One cube's solves, newest first. Paged the same way as [page]; the
     * caller supplies the MAC from [cubesUsed].
     */
    fun pageForCube(userId: String, cubeMac: String, limit: Long, offset: Long): List<SolveRow> =
        db.solvesQueries.pageByCubeDateDesc(userId, cubeMac, limit, offset)
            .executeAsList().map(Solves::toRow)

    /**
     * How many solves this profile has recorded on one cube.
     *
     * `executeAsOne()` yields the Long directly, with no wrapper class to
     * unpack. SQLDelight only generates a row class for a single-column
     * query when that column is **nullable** — it needs somewhere to put
     * a null that is a value rather than an absent row. `COUNT(*)` is
     * never null, so this one comes back bare, while `bestDuration` and
     * `bestAo5` (both `MIN(...)`) come back wrapped and are read as
     * `.executeAsOne().best`.
     */
    fun countForCube(userId: String, cubeMac: String): Long =
        db.solvesQueries.countForCube(userId, cubeMac).executeAsOne()

    /** Distinct cubes this profile has solved on, most recent first. */
    fun cubesUsed(userId: String): List<CubeUsage> =
        db.solvesQueries.cubesUsed(userId).executeAsList().map {
            CubeUsage(mac = it.mac ?: "", solveCount = it.solve_count, lastSolvedAt = it.last_solved_at ?: 0L)
        }

    // -- Reconstruction tracks --------------------------------------------

    /**
     * Persist the move and gyro tracks recorded for a solve.
     *
     * Both are optional and stored independently: a solve whose cube has
     * no gyroscope still gets a move track, and a recording that produced
     * no usable gyro samples writes no gyro row rather than an empty one
     * (an absent row and a zero-sample row would be the same thing said
     * two ways, and only one of them survives a prune).
     *
     * Written in its own transaction *after* the solve row exists, not as
     * part of the insert. Encoding a few hundred samples is real work and
     * the insert happens on the timer's finish path; separating them
     * keeps that path short, and a track that fails to write costs the
     * replay of one solve rather than the solve itself.
     */
    fun saveTracks(
        solveId: Long,
        userId: String,
        solvedAt: Long,
        moves: List<TrackedMove>,
        gyro: List<TrackedGyro>,
        pinGyro: Boolean = false,
    ) = db.transaction {
        if (moves.isNotEmpty()) {
            db.solveTracksQueries.putMoves(
                solveId = solveId,
                format = TrackCodecs.MOVE_FORMAT_V1.toLong(),
                moveCount = moves.size.toLong(),
                payload = TrackCodecs.encodeMoves(moves),
            )
        }
        if (gyro.isNotEmpty()) {
            db.solveTracksQueries.putGyro(
                solveId = solveId,
                userId = userId,
                solvedAt = solvedAt,
                format = TrackCodecs.GYRO_FORMAT_V1.toLong(),
                sampleCount = gyro.size.toLong(),
                pinned = if (pinGyro) 1L else 0L,
                payload = TrackCodecs.encodeGyro(gyro),
            )
        }
    }

    /** Decoded move track for a solve, or null if none was recorded. */
    fun moveTrack(solveId: Long): MoveTrack? {
        val row = db.solveTracksQueries.selectMoves(solveId).executeAsOneOrNull() ?: return null
        return TrackCodecs.decodeMoves(row.format.toInt(), row.payload)
    }

    /** Decoded gyro track for a solve, or null if none was recorded. */
    fun gyroTrack(solveId: Long): GyroTrack? {
        val row = db.solveTracksQueries.selectGyro(solveId).executeAsOneOrNull() ?: return null
        return TrackCodecs.decodeGyro(row.format.toInt(), row.payload)
    }

    /**
     * Whether a solve has rotation data and whether it is pinned, without
     * reading the blob. Cheap enough for a list row to ask per item.
     */
    fun gyroStatus(solveId: Long): GyroStatus? =
        db.solveTracksQueries.gyroStatus(solveId).executeAsOneOrNull()?.let {
            GyroStatus(sampleCount = it.sample_count, pinned = it.pinned != 0L)
        }

    /**
     * Pin or unpin a solve's gyro track. Pinned tracks are exempt from
     * every retention rule – see `SolveTracks.sq`.
     */
    fun setGyroPinned(solveId: Long, pinned: Boolean) =
        db.solveTracksQueries.setGyroPinned(if (pinned) 1L else 0L, solveId)

    /**
     * Delete one solve's rotation data at the user's request. Ignores the
     * pin: a pin defends against automatic retention, not against the
     * person who set it.
     */
    fun deleteGyroTrack(solveId: Long) = db.solveTracksQueries.deleteGyro(solveId)

    /** How much gyro data this profile is holding. */
    fun gyroUsage(userId: String): GyroUsage =
        db.solveTracksQueries.gyroBytesForUser(userId).executeAsOne().let {
            GyroUsage(bytes = it.bytes ?: 0L, tracks = it.tracks)
        }

    /** Retention: keep only the newest [keep] unpinned gyro tracks. */
    fun pruneGyroKeepingNewest(userId: String, keep: Long) =
        db.solveTracksQueries.pruneGyroKeepingNewest(userId, keep)

    /** Retention: drop unpinned gyro tracks older than [cutoff] (epoch ms). */
    fun pruneGyroOlderThan(userId: String, cutoff: Long) =
        db.solveTracksQueries.pruneGyroOlderThan(userId, cutoff)

    /** Retention: drop every unpinned gyro track for the profile. */
    fun deleteAllGyroForUser(userId: String) =
        db.solveTracksQueries.deleteAllGyroForUser(userId)
}

/** One cube's share of a profile's solve history. */
data class CubeUsage(val mac: String, val solveCount: Long, val lastSolvedAt: Long)

/** Presence and pin state of a solve's gyro track. */
data class GyroStatus(val sampleCount: Long, val pinned: Boolean)

/** Aggregate gyro storage held for a profile. */
data class GyroUsage(val bytes: Long, val tracks: Long)
