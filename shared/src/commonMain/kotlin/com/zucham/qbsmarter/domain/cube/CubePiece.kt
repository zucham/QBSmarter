package com.zucham.qbsmarter.domain.cube

import com.zakgof.korender.math.Vec3

/** Identifies which OBJ mesh to render and where it lives. */
data class CubePieceData(val meshFile: String)

/**
 * A single visual cubie. Identity is established at construction (a unique
 * mesh index + a fixed home position); the class holds no mutable per-piece
 * visual state. The render transform is derived on demand from the cube's
 * current [CubeState] via [transformForMesh] in `StateToTransforms.kt` – so
 * the visual cannot desync from the logical state.
 */
class CubePiece(
)

/**
 * Convert a 1-3 letter piece name (URF, UF, U, etc.) into its home position
 * vector. U/D adds ±Y, R/L adds ±X, F/B adds ±Z.
 */
fun homePositionFromName(name: String): Vec3 {
    var x = 0f; var y = 0f; var z = 0f
    for (ch in name) when (ch) {
        'U' -> y = 1f
        'D' -> y = -1f
        'R' -> x = 1f
        'L' -> x = -1f
        'F' -> z = 1f
        'B' -> z = -1f
    }
    return Vec3(x, y, z)
}

/**
 * The 26 cubies that make up a 3×3 cube (8 corners + 12 edges + 6 centers).
 * The OBJ filenames here MUST match the resources under
 * `composeResources/files/`.
 */
val CUBE_PARTS: List<CubePieceData> = listOf(
    CubePieceData("gan_cube_model/URF.obj"), CubePieceData("gan_cube_model/UF.obj"),  CubePieceData("gan_cube_model/ULF.obj"),
    CubePieceData("gan_cube_model/UL.obj"),  CubePieceData("gan_cube_model/ULB.obj"), CubePieceData("gan_cube_model/UB.obj"),
    CubePieceData("gan_cube_model/URB.obj"), CubePieceData("gan_cube_model/UR.obj"),  CubePieceData("gan_cube_model/U.obj"),
    CubePieceData("gan_cube_model/F.obj"),   CubePieceData("gan_cube_model/RF.obj"),  CubePieceData("gan_cube_model/R.obj"),
    CubePieceData("gan_cube_model/RB.obj"),  CubePieceData("gan_cube_model/B.obj"),   CubePieceData("gan_cube_model/LB.obj"),
    CubePieceData("gan_cube_model/L.obj"),   CubePieceData("gan_cube_model/LF.obj"),
    CubePieceData("gan_cube_model/DRF.obj"), CubePieceData("gan_cube_model/DF.obj"),  CubePieceData("gan_cube_model/DLF.obj"),
    CubePieceData("gan_cube_model/DL.obj"),  CubePieceData("gan_cube_model/DLB.obj"), CubePieceData("gan_cube_model/DB.obj"),
    CubePieceData("gan_cube_model/DRB.obj"), CubePieceData("gan_cube_model/DR.obj"),  CubePieceData("gan_cube_model/D.obj"),
)
