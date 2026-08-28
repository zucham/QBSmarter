package com.zucham.qbsmarter.domain.reconstruction

import com.zakgof.korender.math.Quaternion
import com.zucham.qbsmarter.domain.cube.CubeFace

/**
 * One quarter turn, timestamped relative to the start of its solve.
 *
 * [tMs] is milliseconds since the solve's **first move**, on the cube
 * clock – the same clock `SolveTimer` derives the solve duration from,
 * so the last move's [tMs] equals the recorded duration by construction
 * rather than by luck. The first move is therefore always `tMs = 0`.
 */
data class TrackedMove(
    val face: CubeFace,
    val cw: Boolean,
    val tMs: Int,
)

/**
 * One gyroscope pose, timestamped on the same relative cube-clock
 * timeline as [TrackedMove].
 *
 * The quaternion is stored in **cube axes**, exactly as the driver
 * reported it – not in renderer axes. The cube-to-renderer remap lives
 * in `CubeGyroscope.toRendererFrame`, and if the recording applied it
 * first, every stored track would be welded to whatever the renderer's
 * axis convention happened to be on the day it was recorded. Storing the
 * raw sensor frame means a replay written years later applies the
 * mapping that is correct then.
 *
 * [tMs] may be negative: gyro sampling starts when the solve does, and
 * the timeline's zero is the first *move*, which is a beat later.
 */
data class TrackedGyro(
    val quat: Quaternion,
    val tMs: Int,
)

/** A decoded move track plus the format byte it was stored under. */
data class MoveTrack(val moves: List<TrackedMove>)

/** A decoded gyro track. */
data class GyroTrack(val samples: List<TrackedGyro>)
