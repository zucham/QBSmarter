package com.zucham.qbsmarter.domain.reconstruction

import com.zakgof.korender.math.Quaternion
import com.zucham.qbsmarter.domain.cube.CubeFace
import com.zucham.qbsmarter.domain.cube.rotationCloseness
import kotlin.math.cos

/** What one recorded solve yields, ready to be encoded and stored. */
data class RecordedSolve(
    val moves: List<TrackedMove>,
    val gyro: List<TrackedGyro>,
)

/**
 * Collects the move and gyroscope streams of one solve in memory, and
 * hands them back on a single timeline when the solve ends.
 *
 * ## Why it buffers instead of writing as it goes
 *
 * Two reasons, and the second is the interesting one.
 *
 * The obvious one is that a solve is the app's hot path — the timer is
 * ticking at 16 ms and the parser is delivering packets — and a SQLite
 * write per gyro packet would put disk I/O in the middle of it. A whole
 * solve is a couple of kilobytes; it belongs in RAM until it is done.
 *
 * The real one is the clock. Moves carry a cube-clock timestamp and gyro
 * samples carry a device wall-clock one, and the mapping between the two
 * is `SolveTimer`'s running regression — which is still being fitted
 * while the solve is in progress and is at its most accurate only once
 * the solve is over. Projecting each gyro sample as it arrives would
 * stamp early samples with an early, badly-fitted mapping and late ones
 * with a better one, warping the timeline in the middle. Holding
 * everything and projecting once at the end applies a single mapping to
 * the whole solve.
 *
 * ## The deadband
 *
 * Gyro packets arrive at tens of hertz whether or not the cube is
 * moving, and a solve is mostly *not* the cube moving — it is fingers
 * turning layers while the cube's overall orientation sits still,
 * punctuated by fast reorientations (y-rotations between pairs, cube
 * flips) of 90–180° in a couple of hundred milliseconds.
 *
 * Recording at a fixed rate has to pick one number for both regimes and
 * gets both wrong: high enough for the flicks wastes most of its samples
 * on stillness, low enough to be cheap rounds the flicks off into
 * mush. (Slerp between two samples is *exact* for a constant rotation
 * rate — the error is entirely in the acceleration at the start and end
 * of each flick, which is precisely where a fixed low rate has no
 * samples to spare.)
 *
 * So [onGyro] keeps a sample when the pose has moved more than
 * [ANGLE_THRESHOLD_DEG] from the last one it kept, or when
 * [HEARTBEAT_MS] has passed with nothing kept.
 *
 * ### What the simulation actually showed
 *
 * A 15-second solve with six reorientations (four y-rotations, two cube
 * flips, cosine-eased, plus hand tremor throughout), replayed by slerping
 * between the kept samples and compared against the true motion:
 *
 * ```
 *   cube sending 50 Hz          samples   bytes   worst replay error
 *     every packet kept             750    3900             0.57°
 *     deadband 3° / 250 ms          112     582             7.03°
 *     fixed 10 Hz                   150     780            10.63°
 *
 *   cube sending 20 Hz
 *     every packet kept             300    1560             3.10°
 *     deadband 3° / 250 ms           80     416            10.41°
 *     fixed 10 Hz                   150     780            10.63°
 * ```
 *
 * Two things in there are worth carrying forward, because neither is
 * what the design intuition predicted.
 *
 * **The threshold hardly matters.** 2°, 3° and 5° all keep the same 80
 * samples at a 20 Hz packet rate. During a flick the cube is turning
 * fast enough that consecutive packets are already 10–20° apart, so
 * every one of them clears any threshold in that range; during stillness
 * the tremor never reaches even 2°, so it is [HEARTBEAT_MS] that fires.
 * The threshold only bites in a middle regime that barely occurs. 3° is
 * chosen as comfortably inside the flat part of that curve, not because
 * it is a knife edge — the heartbeat is the knob that actually sets the
 * cost.
 *
 * **The fidelity ceiling is the cube, not us.** During a flick the
 * deadband is already keeping every packet the cube sent, so no
 * threshold and no policy can do better than the "every packet kept"
 * row — and at a 20 Hz packet rate that row is itself 3.1° off. The
 * remaining error is slerp cutting the corner between widely-spaced
 * poses, which is an *interpolation* problem, not a sampling one. If the
 * replay ever needs to look better, the win is a squad or Catmull-Rom
 * interpolator on playback, which costs no storage at all; recording
 * more samples cannot buy it.
 *
 * What the deadband does buy is the thing it was chosen for: at a 50 Hz
 * packet rate it is 582 bytes against fixed 10 Hz's 780, with a *lower*
 * error, and at 20 Hz it matches 10 Hz's error for barely half the
 * bytes.
 *
 * ## Threading
 *
 * Single-threaded by contract: every entry point is called from the
 * driver-event path that `SolveViewModel.onCubeEvent` runs on, the same
 * one that already mutates the view model's move counter and logical
 * state. Nothing here is synchronised, and nothing else may touch it.
 */
class SolveRecorder {

    private class RawMove(val face: CubeFace, val cw: Boolean, val cubeMs: Long)
    private class RawGyro(val quat: Quaternion, val deviceMs: Long)

    private val moves = ArrayList<RawMove>()
    private val gyro = ArrayList<RawGyro>()

    private var recording = false

    /**
     * The most recent sample seen, recorded or not, and whether or not a
     * solve is in progress. Kept so [start] can seed the track with the
     * pose the cube is already in: the first gyro packet after the timer
     * starts is a few tens of milliseconds late, and without a seed the
     * replay would open on an orientation it has no value for.
     */
    private var latestSample: RawGyro? = null

    /** Pose the deadband measures against – the last one actually kept. */
    private var lastKept: RawGyro? = null

    /** Begin recording. Discards anything left over from a previous solve. */
    fun start() {
        moves.clear()
        gyro.clear()
        lastKept = null
        recording = true
        latestSample?.let {
            gyro += it
            lastKept = it
        }
    }

    /** Abandon the current recording (disconnect, aborted solve, profile switch). */
    fun cancel() {
        recording = false
        moves.clear()
        gyro.clear()
        lastKept = null
    }

    fun onMove(face: CubeFace, cw: Boolean, cubeTimestamp: Long) {
        if (!recording || moves.size >= MAX_MOVES) return
        moves += RawMove(face, cw, cubeTimestamp)
    }

    /**
     * Offer one gyroscope sample. Fed unconditionally, exactly like
     * `CubeGyroscope.onSample` — outside a solve it only refreshes
     * [latestSample], which is what lets [start] seed the track.
     */
    fun onGyro(quat: Quaternion, deviceTimestamp: Long) {
        val sample = RawGyro(quat, deviceTimestamp)
        latestSample = sample
        if (!recording || gyro.size >= MAX_SAMPLES) return
        val previous = lastKept
        if (previous != null &&
            rotationCloseness(quat, previous.quat) >= ANGLE_THRESHOLD_COSINE &&
            deviceTimestamp - previous.deviceMs < HEARTBEAT_MS
        ) {
            return
        }
        gyro += sample
        lastKept = sample
    }

    /**
     * Close the recording and return both streams on one timeline:
     * milliseconds since [firstMoveCubeMs], on the cube clock.
     *
     * [toCubeClock] projects a device wall-clock timestamp onto the cube
     * clock — `ClockSkewEstimator.predictCube`, by way of the timer that
     * has been fitting it all solve.
     *
     * Returns null when there is nothing worth storing (no moves, so no
     * timeline to hang anything off). Callers should treat that as "this
     * solve has no reconstruction data", not as an error: a solve
     * recorded with the cube disconnected mid-way is still a solve.
     */
    fun finish(firstMoveCubeMs: Long, toCubeClock: (Long) -> Long): RecordedSolve? {
        recording = false
        if (moves.isEmpty()) {
            moves.clear(); gyro.clear(); lastKept = null
            return null
        }
        val trackedMoves = moves.map {
            TrackedMove(it.face, it.cw, (it.cubeMs - firstMoveCubeMs).toIntClamped())
        }
        val trackedGyro = gyro.map {
            TrackedGyro(it.quat, (toCubeClock(it.deviceMs) - firstMoveCubeMs).toIntClamped())
        }
        moves.clear(); gyro.clear(); lastKept = null
        return RecordedSolve(trackedMoves, trackedGyro)
    }

    /**
     * A solve is minutes at the outside, so the relative timeline fits an
     * Int with five orders of magnitude to spare. Clamping rather than
     * truncating means a wild projection from a badly-fitted clock
     * produces a sample pinned at the end of the track instead of one
     * that has wrapped around to the start of it.
     */
    private fun Long.toIntClamped(): Int =
        coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    companion object {
        /**
         * How far the cube must turn before a new sample is kept.
         *
         * Sits in the middle of the flat part of the cost curve — see the
         * table in the class documentation, where 2°, 3° and 5° all keep
         * the same number of samples. It is deliberately not tuned to a
         * knife edge, because the quantity it controls (samples during
         * medium-speed motion) turns out to be a small part of the total.
         */
        const val ANGLE_THRESHOLD_DEG = 3.0

        /**
         * Longest gap with no sample stored. Bounds how stale the replay
         * can be while the cube sits still, and gives the track periodic
         * keyframes so a decode that goes wrong somewhere cannot drift
         * indefinitely.
         */
        const val HEARTBEAT_MS = 250L

        /**
         * `rotationCloseness` is `|dot(a, b)|`, and the angle between two
         * rotations is `2·acos(|dot|)` — so the half-angle is what gets
         * compared against a cosine here. Precomputed: the deadband test
         * runs on every packet and this saves an `acos` on each one.
         */
        private val ANGLE_THRESHOLD_COSINE =
            cos(ANGLE_THRESHOLD_DEG / 2.0 * kotlin.math.PI / 180.0).toFloat()

        /**
         * Hard caps. Nothing normal approaches these — a 60-second solve
         * is ~100 moves and, at the deadband's worst case of one sample
         * per packet, a few thousand samples. They exist so a solve that
         * never ends (a cube left connected and turning on a desk, a
         * phase machine wedged in RUNNING) grows a bounded buffer rather
         * than an unbounded one.
         */
        private const val MAX_MOVES = 5_000
        private const val MAX_SAMPLES = 20_000
    }
}
