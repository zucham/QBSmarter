package com.zucham.qbsmarter.domain.cube

import com.zakgof.korender.math.Vec3

/** The six faces of a 3×3 cube, in the standard Kociemba URFDLB order. */
enum class CubeFace { U, R, F, D, L, B }

/** Outward-pointing axis of a face (R = +X, L = -X, etc.). */
fun CubeFace.axis(): Vec3 = when (this) {
    CubeFace.U -> Vec3(0f, 1f, 0f)
    CubeFace.D -> Vec3(0f, -1f, 0f)
    CubeFace.R -> Vec3(1f, 0f, 0f)
    CubeFace.L -> Vec3(-1f, 0f, 0f)
    CubeFace.F -> Vec3(0f, 0f, 1f)
    CubeFace.B -> Vec3(0f, 0f, -1f)
}

/**
 * The face on the opposite side of the cube (parallel axis). Used by
 * [CubeMoveQueue] to coalesce simultaneous turns of opposite faces into a
 * single parallel animation – those don't share any pieces, so they can run
 * concurrently without conflict.
 */
fun CubeFace.opposite(): CubeFace = when (this) {
    CubeFace.U -> CubeFace.D
    CubeFace.D -> CubeFace.U
    CubeFace.R -> CubeFace.L
    CubeFace.L -> CubeFace.R
    CubeFace.F -> CubeFace.B
    CubeFace.B -> CubeFace.F
}
