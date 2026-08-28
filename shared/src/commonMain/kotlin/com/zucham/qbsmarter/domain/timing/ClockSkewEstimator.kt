package com.zucham.qbsmarter.domain.timing

/**
 * Online linear-regression estimator for the cube clock vs the device
 * clock. Uses incremental least-squares (running sums of x, y, xy, x², n)
 * so each move costs O(1) – we never replay history.
 *
 * The cube reports its own monotonic timestamp on each move; the device
 * reports its wall-clock when it received that move. Both have skew –
 * the cube's clock has crystal drift and the BLE stack adds variable
 * latency. Fitting a line cube_ts → device_ts gives us a single best
 * "real time the move happened" for each move.
 *
 * Below MIN_RELIABLE_SAMPLES we have too few points for the slope to
 * mean much; callers fall back to raw deviceTs in that regime.
 */
class ClockSkewEstimator {

    private var n = 0L
    private var sx = 0.0
    private var sy = 0.0
    private var sxx = 0.0
    private var sxy = 0.0

    fun reset() {
        n = 0; sx = 0.0; sy = 0.0; sxx = 0.0; sxy = 0.0
    }

    fun observe(cubeTs: Long, deviceTs: Long) {
        val x = cubeTs.toDouble(); val y = deviceTs.toDouble()
        n += 1; sx += x; sy += y; sxx += x * x; sxy += x * y
    }

    /** Number of samples observed. */
    val sampleCount: Long get() = n

    /** True once we have enough samples to use the regression output. */
    val isReliable: Boolean get() = n >= MIN_RELIABLE_SAMPLES

    /** Predict device timestamp from cube timestamp. */
    fun predict(cubeTs: Long): Long {
        if (n == 0L) return cubeTs
        val x = cubeTs.toDouble()
        val denom = n * sxx - sx * sx
        if (denom == 0.0) return (sy / n + (x - sx / n)).toLong()  // degenerate
        val slope = (n * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / n
        return (slope * x + intercept).toLong()
    }

    /**
     * The inverse of [predict]: given a device wall-clock timestamp, the
     * cube-clock timestamp it corresponds to.
     *
     * This is what puts gyroscope samples on the same timeline as moves.
     * `SmartCubeEvent.Gyro` carries only a device timestamp — no cube
     * protocol reports a cube-clock time on a gyro packet — while a
     * solve's whole timeline is defined by `Move.cubeTimestamp`. Without
     * a projection the two streams would be recorded against two clocks
     * that disagree by a drifting offset, and a replay would show the
     * cube rotating a little before or after the turn that caused the
     * rotation, drifting further apart the longer the solve ran.
     *
     * Only meaningful once the fit has points on both sides of the
     * question; callers should hold their samples until the solve ends
     * and [isReliable] is true, which is exactly what `SolveRecorder`
     * does. Falls back to the identity when there is nothing fitted yet,
     * matching [predict]'s behaviour in the same situation.
     *
     * A near-zero slope would mean the cube clock stood still while the
     * device clock advanced — firmware misbehaving rather than drift —
     * and is rejected in favour of the identity rather than divided by.
     */
    fun predictCube(deviceTs: Long): Long {
        if (n == 0L) return deviceTs
        val denom = n * sxx - sx * sx
        if (denom == 0.0) return deviceTs
        val slope = (n * sxy - sx * sy) / denom
        if (slope < MIN_PLAUSIBLE_SLOPE) return deviceTs
        val intercept = (sy - slope * sx) / n
        return ((deviceTs.toDouble() - intercept) / slope).toLong()
    }

    companion object {
        const val MIN_RELIABLE_SAMPLES = 20L

        /**
         * Below this, the fitted cube-per-device-millisecond rate is not
         * drift, it is a broken clock, and inverting it would blow the
         * projected timestamps up. Two clocks that are both roughly
         * counting milliseconds have a slope near 1; a tenth of that is
         * already far outside anything crystal drift can produce.
         */
        const val MIN_PLAUSIBLE_SLOPE = 0.1
    }
}
