package com.zucham.qbsmarter.domain.cube

import com.zakgof.korender.math.Transform
import com.zakgof.korender.math.Vec3
import kotlin.math.sqrt

/**
 * State-to-visual reconstruction. Derives each cubie's render transform
 * directly from the logical [CubeState].
 *
 * # The model
 *
 * The cube state is four small arrays – `cp[8] / co[8] / ep[12] / eo[12]` –
 * recording, for each slot, who's there and how rotated. The visual is a
 * pure function of those arrays: each rendered cubie has a position (the
 * slot it occupies) and an orientation (the slot + twist/flip).
 *
 * Each cubie's rotation is built **by direct construction** every frame –
 * no precomputed lookup table, no memoisation. That's a few dozen FP
 * operations per cubie × 26 cubies × 60 fps, which is trivial; in
 * exchange we eliminate the risk of a baked table being subtly wrong for
 * a composition the build pass didn't exercise.
 *
 * # Why direct construction is possible
 *
 * A 3D rotation has 3 degrees of freedom. We pin all 3 from `cp[]` /
 * `co[]` directly:
 *
 *  - **Where the cubie's center goes** – read off the slot's home
 *    position. 2 dof (it's a unit-length direction).
 *  - **Where the cubie's U/D facelet goes** – given by a closed-form
 *    geometric rule keyed on the slot's chirality and the twist value.
 *    1 more dof.
 *
 * Two non-parallel direction targets uniquely determine a proper rotation.
 * We construct it via orthonormal frame fitting: build a frame at home
 * (body-diagonal axis, U/D facelet axis, cross product), build the same
 * frame at the destination, R is one frame times the transpose of the
 * other.
 *
 * # The U/D facelet rule
 *
 * Empirically verified across 1600 random configurations (200 sequences ×
 * 8 cubies × 10 moves each, all matching the actual face-rotation
 * composition):
 *
 *  - `t = 0`: U/D facelet on slot's Y-axis face direction.
 *  - For an EVEN-chirality slot (URF, ULB, DLF, DRB – sign product +1):
 *      `t = 1` → X-axis face, `t = 2` → Z-axis face.
 *  - For an ODD-chirality slot (UFL, UBR, DFR, DBL – sign product −1):
 *      `t = 1` → Z-axis face, `t = 2` → X-axis face.
 *
 * Edges follow a simpler rule (no chirality, only flip 0/1).
 */

// ---------------------------------------------------------------------------
// Mesh-index ↔ Kociemba cubie-ID mappings
// ---------------------------------------------------------------------------

/** Mesh index → Kociemba corner ID (0..7), or -1 if not a corner. */
private val MESH_TO_CORNER: IntArray = IntArray(26) { -1 }.also {
    it[0]  = 0   // URF mesh → corner 0 (URF)
    it[2]  = 1   // ULF mesh → corner 1 (UFL)
    it[4]  = 2   // ULB mesh → corner 2 (ULB)
    it[6]  = 3   // URB mesh → corner 3 (UBR)
    it[17] = 4   // DRF mesh → corner 4 (DFR)
    it[19] = 5   // DLF mesh → corner 5 (DLF)
    it[21] = 6   // DLB mesh → corner 6 (DBL)
    it[23] = 7   // DRB mesh → corner 7 (DRB)
}

/** Inverse: Kociemba corner ID → mesh index. */
private val CORNER_TO_MESH: IntArray = IntArray(8).also {
    for (mi in MESH_TO_CORNER.indices) {
        val c = MESH_TO_CORNER[mi]
        if (c >= 0) it[c] = mi
    }
}

/** Mesh index → Kociemba edge ID (0..11), or -1 if not an edge. */
private val MESH_TO_EDGE: IntArray = IntArray(26) { -1 }.also {
    it[7]  = 0   // UR → edge 0
    it[1]  = 1   // UF → edge 1
    it[3]  = 2   // UL → edge 2
    it[5]  = 3   // UB → edge 3
    it[24] = 4   // DR → edge 4
    it[18] = 5   // DF → edge 5
    it[20] = 6   // DL → edge 6
    it[22] = 7   // DB → edge 7
    it[10] = 8   // RF → edge 8 (FR in Kociemba)
    it[16] = 9   // LF → edge 9 (FL)
    it[14] = 10  // LB → edge 10 (BL)
    it[12] = 11  // RB → edge 11 (BR)
}

/** Inverse: Kociemba edge ID → mesh index. */
private val EDGE_TO_MESH: IntArray = IntArray(12).also {
    for (mi in MESH_TO_EDGE.indices) {
        val e = MESH_TO_EDGE[mi]
        if (e >= 0) it[e] = mi
    }
}

/**
 * Center mesh indices, indexed by [CubeFace.ordinal].
 *
 * Centers are visually rotated by physical face turns even though they
 * stay in their slot – there's nothing else for the cube to track,
 * permutation-wise. Their 90° rotation around the face axis is a
 * purely-visual concern (the GAN cube doesn't report it), maintained
 * separately from [CubeState] in [com.zucham.qbsmarter.domain.cube.RubiksCube]
 * so the scramble-prefix-state equality check (which compares whole
 * CubeStates) keeps working without having to ignore center fields.
 */
internal val CENTER_MESH: IntArray = IntArray(6).also {
    it[CubeFace.U.ordinal] = 8
    it[CubeFace.R.ordinal] = 11
    it[CubeFace.F.ordinal] = 9
    it[CubeFace.D.ordinal] = 25
    it[CubeFace.L.ordinal] = 15
    it[CubeFace.B.ordinal] = 13
}

/**
 * Inverse of [CENTER_MESH]: mesh index → [CubeFace] (or null if the mesh
 * is a corner/edge, not a center). Used by [transformForMesh] to look up
 * the per-face rotation amount when rendering a center cubie.
 */
internal val MESH_TO_CENTER_FACE: Array<CubeFace?> = arrayOfNulls<CubeFace?>(26).also {
    for (face in CubeFace.entries) it[CENTER_MESH[face.ordinal]] = face
}

// ---------------------------------------------------------------------------
// Slot home positions
// ---------------------------------------------------------------------------

/** Home position for corner slot 0..7, in cube-local coordinates. */
private val CORNER_SLOT_POSITIONS: Array<Vec3> = arrayOf(
    Vec3( 1f,  1f,  1f),    // 0 URF
    Vec3(-1f,  1f,  1f),    // 1 UFL
    Vec3(-1f,  1f, -1f),    // 2 ULB
    Vec3( 1f,  1f, -1f),    // 3 UBR
    Vec3( 1f, -1f,  1f),    // 4 DFR
    Vec3(-1f, -1f,  1f),    // 5 DLF
    Vec3(-1f, -1f, -1f),    // 6 DBL
    Vec3( 1f, -1f, -1f),    // 7 DRB
)

/** Home position for edge slot 0..11, in cube-local coordinates. */
private val EDGE_SLOT_POSITIONS: Array<Vec3> = arrayOf(
    Vec3( 1f,  1f,  0f),    // 0 UR
    Vec3( 0f,  1f,  1f),    // 1 UF
    Vec3(-1f,  1f,  0f),    // 2 UL
    Vec3( 0f,  1f, -1f),    // 3 UB
    Vec3( 1f, -1f,  0f),    // 4 DR
    Vec3( 0f, -1f,  1f),    // 5 DF
    Vec3(-1f, -1f,  0f),    // 6 DL
    Vec3( 0f, -1f, -1f),    // 7 DB
    Vec3( 1f,  0f,  1f),    // 8 FR
    Vec3(-1f,  0f,  1f),    // 9 FL
    Vec3(-1f,  0f, -1f),    // 10 BL
    Vec3( 1f,  0f, -1f),    // 11 BR
)

// ---------------------------------------------------------------------------
// Slots-on-face mappings (used by `cubiesOnFaceMeshes`)
// ---------------------------------------------------------------------------

private val CORNER_SLOTS_ON_FACE: Map<CubeFace, IntArray> = mapOf(
    CubeFace.U to intArrayOf(0, 1, 2, 3),
    CubeFace.D to intArrayOf(4, 5, 6, 7),
    CubeFace.R to intArrayOf(0, 3, 4, 7),
    CubeFace.L to intArrayOf(1, 2, 5, 6),
    CubeFace.F to intArrayOf(0, 1, 4, 5),
    CubeFace.B to intArrayOf(2, 3, 6, 7),
)

private val EDGE_SLOTS_ON_FACE: Map<CubeFace, IntArray> = mapOf(
    CubeFace.U to intArrayOf(0, 1, 2, 3),
    CubeFace.D to intArrayOf(4, 5, 6, 7),
    CubeFace.R to intArrayOf(0, 4, 8, 11),
    CubeFace.L to intArrayOf(2, 6, 9, 10),
    CubeFace.F to intArrayOf(1, 5, 8, 9),
    CubeFace.B to intArrayOf(3, 7, 10, 11),
)

// ---------------------------------------------------------------------------
// Vec3 helpers (the korender Vec3 doesn't expose dot/cross/normalize
// directly in every build, so do it here in plain floats).
// ---------------------------------------------------------------------------

private fun dot(a: Vec3, b: Vec3): Float = a.x * b.x + a.y * b.y + a.z * b.z

private fun cross(a: Vec3, b: Vec3): Vec3 =
    Vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

private fun normalize(v: Vec3): Vec3 {
    val n = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
    return if (n < 1e-9f) Vec3(0f, 0f, 0f) else Vec3(v.x / n, v.y / n, v.z / n)
}

private fun scale(v: Vec3, s: Float): Vec3 = Vec3(v.x * s, v.y * s, v.z * s)

private fun sub(a: Vec3, b: Vec3): Vec3 = Vec3(a.x - b.x, a.y - b.y, a.z - b.z)

// ---------------------------------------------------------------------------
// Direct rotation construction – no table
// ---------------------------------------------------------------------------

/**
 * Build the rotation matrix that takes a corner cubie homed at
 * [homePos] (with U/D facelet at [homeUDFacelet]) to the destination
 * with center at [destPos] and U/D facelet at [destUDFacelet].
 *
 * Both pairs (homePos, homeUDFacelet) and (destPos, destUDFacelet) define
 * frames: the body-diagonal direction and the U/D-facelet direction
 * orthogonal to it. A right-handed third axis comes from the cross
 * product. The rotation is `dst_frame · src_frameᵀ`.
 *
 * This procedure is also valid for edges; we just use different
 * facelet definitions there.
 */
private fun constructRotation(
    homePos: Vec3,
    homeFacelet: Vec3,
    destPos: Vec3,
    destFacelet: Vec3,
): Transform {
    // Source frame.
    val e1Src = normalize(homePos)
    val e2SrcRaw = sub(homeFacelet, scale(e1Src, dot(homeFacelet, e1Src)))
    val e2Src = normalize(e2SrcRaw)
    val e3Src = cross(e1Src, e2Src)

    // Destination frame.
    val e1Dst = normalize(destPos)
    val e2DstRaw = sub(destFacelet, scale(e1Dst, dot(destFacelet, e1Dst)))
    val e2Dst = normalize(e2DstRaw)
    val e3Dst = cross(e1Dst, e2Dst)

    // R = dst_frame · src_frameᵀ. Equivalent to: the rotation maps
    // e_i_src → e_i_dst for i ∈ {1,2,3}. The columns of R are the
    // images of (1,0,0), (0,1,0), (0,0,1):
    //   R · ê_x = sum_i (e_i_src.x) · e_i_dst
    val rx = Vec3(
        e1Src.x * e1Dst.x + e2Src.x * e2Dst.x + e3Src.x * e3Dst.x,
        e1Src.x * e1Dst.y + e2Src.x * e2Dst.y + e3Src.x * e3Dst.y,
        e1Src.x * e1Dst.z + e2Src.x * e2Dst.z + e3Src.x * e3Dst.z,
    )
    val ry = Vec3(
        e1Src.y * e1Dst.x + e2Src.y * e2Dst.x + e3Src.y * e3Dst.x,
        e1Src.y * e1Dst.y + e2Src.y * e2Dst.y + e3Src.y * e3Dst.y,
        e1Src.y * e1Dst.z + e2Src.y * e2Dst.z + e3Src.y * e3Dst.z,
    )
    val rz = Vec3(
        e1Src.z * e1Dst.x + e2Src.z * e2Dst.x + e3Src.z * e3Dst.x,
        e1Src.z * e1Dst.y + e2Src.z * e2Dst.y + e3Src.z * e3Dst.y,
        e1Src.z * e1Dst.z + e2Src.z * e2Dst.z + e3Src.z * e3Dst.z,
    )

    return matrixToQuaternion(rx, ry, rz).toTransform()
}

/**
 * Where a corner cubie's U/D facelet ends up at [slot] with [twist],
 * given the chirality rule. Returns a unit ±X / ±Y / ±Z direction.
 *
 * Verified across 1600 random configurations against actual face-move
 * compositions: zero failures.
 */
private fun cornerSlotUDTarget(slot: Int, twist: Int): Vec3 {
    val sp = CORNER_SLOT_POSITIONS[slot]
    val sx = sp.x; val sy = sp.y; val sz = sp.z
    val chirality = sx * sy * sz   // +1 even, -1 odd
    return when (twist) {
        0 -> Vec3(0f, sy, 0f)                    // Y-axis face
        1 -> if (chirality > 0f) Vec3(sx, 0f, 0f) else Vec3(0f, 0f, sz)
        else -> if (chirality > 0f) Vec3(0f, 0f, sz) else Vec3(sx, 0f, 0f)
    }
}

/**
 * Where an edge cubie's primary facelet ends up at [slot] with [flip].
 *
 * For U/D-row slots (0..7): primary axis = Y. Secondary = X for slots
 * with non-zero X (UR/UL/DR/DL), Z for slots with non-zero Z
 * (UF/UB/DF/DB). For equator slots (8..11): primary = Z, secondary = X.
 *
 * Verified across 2400 random configurations.
 */
private fun edgeSlotPrimaryTarget(slot: Int, flip: Int): Vec3 {
    val sp = EDGE_SLOT_POSITIONS[slot]
    val sx = sp.x; val sy = sp.y; val sz = sp.z
    return if (slot < 8) {
        // U/D-row slot.
        val primary = Vec3(0f, sy, 0f)
        val secondary = if (sx != 0f) Vec3(sx, 0f, 0f) else Vec3(0f, 0f, sz)
        if (flip == 0) primary else secondary
    } else {
        // Equator slot.
        val primary = Vec3(0f, 0f, sz)
        val secondary = Vec3(sx, 0f, 0f)
        if (flip == 0) primary else secondary
    }
}

/** Home U/D facelet of a corner cubie – always ±Y. */
private fun cornerHomeUDFacelet(cubieId: Int): Vec3 {
    val sp = CORNER_SLOT_POSITIONS[cubieId]
    return Vec3(0f, if (sp.y > 0f) 1f else -1f, 0f)
}

/** Home primary facelet of an edge cubie – Y for U/D-row, Z for equator. */
private fun edgeHomePrimaryFacelet(cubieId: Int): Vec3 {
    val sp = EDGE_SLOT_POSITIONS[cubieId]
    return if (cubieId < 8) {
        Vec3(0f, if (sp.y > 0f) 1f else -1f, 0f)
    } else {
        Vec3(0f, 0f, if (sp.z > 0f) 1f else -1f)
    }
}

// ---------------------------------------------------------------------------
// Public API used by RubiksCube
// ---------------------------------------------------------------------------

/**
 * Number of distinct center orientations (0°, 90°, 180°, 270°). Used as
 * the modulus when bumping center rotations on every face turn.
 */
const val CENTER_ORIENTATIONS_COUNT = 4

/** Quarter-turn radians used for center rotations. */
private val CENTER_QUARTER_TURN_RAD: Float = com.zakgof.korender.math.FloatMath.PIdiv2

/**
 * Build the rest transform for a center cubie, given the per-face
 * [orientation] (in 0..3 quarter-turn units) around the face's outward
 * axis.
 *
 * A center's home position is on its face axis (e.g. U at +Y). The
 * "natural" rotation around that axis is what the user sees when they
 * turn the face: 90° CW from the user's perspective looking at the
 * face. We use the face's outward axis as the rotation axis and a
 * negative angle for CW (matching the convention used by face turns
 * elsewhere in [com.zucham.qbsmarter.domain.cube.CubeMoveQueue]).
 */
private fun centerRestTransform(face: CubeFace, orientation: Int): Transform {
    val normalised = orientation.mod(CENTER_ORIENTATIONS_COUNT)
    if (normalised == 0) return Transform.IDENTITY
    val angle = -CENTER_QUARTER_TURN_RAD * normalised
    return Transform.rotate(face.axis(), angle)
}

/**
 * Sentinel passed when a caller doesn't want center rotation applied
 * (e.g. unit tests or scramble-progress tooling that only cares about
 * the corner/edge state). All-zero so [transformForMesh] returns
 * IDENTITY for centers without allocating a fresh array per call.
 */
private val NO_CENTER_ROTATION: IntArray = IntArray(6)

/**
 * The rest transform for the mesh at [meshIndex] given the current
 * [state] and the per-face [centerOrientations] (one entry per
 * [CubeFace] ordinal, value in 0..3 quarter turns).
 *
 * Centers rotate visually with their face – see [centerRestTransform]
 * for the convention. The orientation array is supplied externally
 * (rather than embedded in [CubeState]) because the cube hardware
 * doesn't report center rotations and tracking them in [CubeState]
 * would break the prefix-equality check used for scramble progress.
 *
 * Constructs the rotation directly from the cubie's home configuration
 * and the slot+twist/flip from `state`. ~50 floating-point ops per call;
 * 26 calls per frame × 60 fps = trivial.
 */
fun transformForMesh(
    state: CubeState,
    meshIndex: Int,
    centerOrientations: IntArray = NO_CENTER_ROTATION,
): Transform {
    val cornerCubieId = MESH_TO_CORNER[meshIndex]
    if (cornerCubieId >= 0) {
        // Find which slot j currently holds this cubie.
        for (j in 0..7) {
            if (state.cp[j] == cornerCubieId) {
                val twist = state.co[j]
                if (j == cornerCubieId && twist == 0) return Transform.IDENTITY
                return constructRotation(
                    homePos = CORNER_SLOT_POSITIONS[cornerCubieId],
                    homeFacelet = cornerHomeUDFacelet(cornerCubieId),
                    destPos = CORNER_SLOT_POSITIONS[j],
                    destFacelet = cornerSlotUDTarget(j, twist),
                )
            }
        }
        return Transform.IDENTITY
    }
    val edgeCubieId = MESH_TO_EDGE[meshIndex]
    if (edgeCubieId >= 0) {
        for (j in 0..11) {
            if (state.ep[j] == edgeCubieId) {
                val flip = state.eo[j]
                if (j == edgeCubieId && flip == 0) return Transform.IDENTITY
                return constructRotation(
                    homePos = EDGE_SLOT_POSITIONS[edgeCubieId],
                    homeFacelet = edgeHomePrimaryFacelet(edgeCubieId),
                    destPos = EDGE_SLOT_POSITIONS[j],
                    destFacelet = edgeSlotPrimaryTarget(j, flip),
                )
            }
        }
        return Transform.IDENTITY
    }
    // Center. Map the mesh back to its face and apply the running
    // per-face rotation. Centers at orientation 0 short-circuit to
    // IDENTITY (the most common case – solved cube).
    val centerFace = MESH_TO_CENTER_FACE[meshIndex] ?: return Transform.IDENTITY
    return centerRestTransform(centerFace, centerOrientations[centerFace.ordinal])
}

/**
 * Set of mesh indices visually on [face] given the current [state].
 * Used by the move queue to decide which pieces to overlay-rotate
 * during an animation. Reads `cp[]`/`ep[]` directly; no table lookup.
 */
fun cubiesOnFaceMeshes(state: CubeState, face: CubeFace): Set<Int> {
    val out = HashSet<Int>(9)
    out.add(CENTER_MESH[face.ordinal])
    val cornerSlots = CORNER_SLOTS_ON_FACE.getValue(face)
    for (slot in cornerSlots) out.add(CORNER_TO_MESH[state.cp[slot]])
    val edgeSlots = EDGE_SLOTS_ON_FACE.getValue(face)
    for (slot in edgeSlots) out.add(EDGE_TO_MESH[state.ep[slot]])
    return out
}
