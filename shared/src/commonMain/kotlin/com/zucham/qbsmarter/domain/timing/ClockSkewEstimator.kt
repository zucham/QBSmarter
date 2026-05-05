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

    companion object {
        const val MIN_RELIABLE_SAMPLES = 20L
    }
}
