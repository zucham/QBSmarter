package com.zucham.qbsmarter.domain.timing

import com.zucham.qbsmarter.util.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Solve timer with dual-clock semantics:
 *   • Wall-clock (currentTimeMillis) drives the on-screen tick at 16ms,
 *     so the displayed timer never freezes between moves.
 *   • Cube-clock (the per-move accumulated `cubeTimestamp` reported by the
 *     parser) sets the canonical first and last move timestamps. The
 *     final solve duration is computed as `lastCube - firstCube`, NOT
 *     from wall-clock or from the regression-corrected device clock.
 *
 * Why cube-clock for the final duration: the cube reports per-move
 * elapsed time on its own crystal oscillator, which is monotonic and
 * has no BLE-jitter. For the time horizons of a solve (≤120s) the
 * crystal's drift is microseconds – for our purposes it's
 * indistinguishable from real wall-clock time, AND it doesn't suffer
 * from the packet-arrival jitter that affects `deviceTimestamp`.
 *
 * Lifecycle: `reset` → `observeMove(...)` (auto-starts the ticker on the
 * first move) → repeated `observeMove` → `finish()` (stops the ticker,
 * returns the canonical duration).
 */
class SolveTimer {

    /**
     * Online linear-regression estimator of cube-clock vs device-clock,
     * still maintained but no longer used by [finish]. Kept around so
     * future stat code can convert cube timestamps to wall-clock for
     * display without re-fitting from scratch.
     */
    private val estimator = ClockSkewEstimator()

    private var firstCubeMs: Long? = null
    private var lastCubeMs: Long? = null
    private var firstWallMs: Long? = null

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var ticker: Job? = null

    fun reset() {
        estimator.reset()
        firstCubeMs = null
        lastCubeMs = null
        firstWallMs = null
        _elapsedMs.value = 0L
        _running.value = false
        ticker?.cancel()
        ticker = null
    }

    /** Record one move. Auto-starts the ticker on the first observation. */
    fun observeMove(cubeTimestamp: Long, deviceTimestamp: Long) {
        estimator.observe(cubeTimestamp, deviceTimestamp)
        if (firstCubeMs == null) firstCubeMs = cubeTimestamp
        lastCubeMs = cubeTimestamp
    }

    /**
     * Drive the displayed elapsed time at 16 ms. Idempotent – calling
     * twice does not start two tickers.
     */
    fun startTicker(scope: CoroutineScope) {
        if (ticker?.isActive == true) return
        firstWallMs = currentTimeMillis()
        _running.value = true
        ticker = scope.launch {
            while (isActive) {
                val first = firstWallMs ?: break
                _elapsedMs.value = currentTimeMillis() - first
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop the timer. Returns the canonical solve duration as the
     * difference of cube timestamps from first move to last move.
     * Returns 0 if there was no first move.
     *
     * Falls back to wall-clock duration in two edge cases:
     *   • The cube timestamps are degenerate (`firstCube == lastCube`,
     *     which can only happen with exactly one move – not a real
     *     solve, but be defensive).
     *   • The cube reported a non-monotonic timestamp (`last < first`).
     *     Shouldn't happen in normal operation but if firmware ever
     *     resets its clock mid-solve, fall back rather than report
     *     a negative duration.
     */
    fun finish(): Long {
        ticker?.cancel()
        ticker = null
        _running.value = false
        val firstCube = firstCubeMs
        val lastCube = lastCubeMs
        val firstWall = firstWallMs
        val duration = when {
            firstCube == null || lastCube == null -> 0L
            lastCube > firstCube -> lastCube - firstCube
            firstWall != null -> (currentTimeMillis() - firstWall).coerceAtLeast(0L)
            else -> 0L
        }
        _elapsedMs.value = duration
        return duration
    }

    companion object {
        const val TICK_INTERVAL_MS = 16L
    }
}
