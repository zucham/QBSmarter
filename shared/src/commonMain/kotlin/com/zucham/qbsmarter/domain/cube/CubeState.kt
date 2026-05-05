package com.zucham.qbsmarter.domain.cube

/**
 * Logical cube state in the Kociemba representation:
 *   • cp[i] = which corner cubie is at position i  (0..7)
 *   • co[i] = orientation of that corner (0..2)
 *   • ep[i] = which edge cubie is at position i  (0..11)
 *   • eo[i] = orientation of that edge (0..1)
 *
 * Move tables match the standard Kociemba conventions so we can hand the
 * facelet string off to a solver later without translating anything.
 */
const val N_CORNERS = 8
const val N_EDGES = 12

data class CubeState(
    val cp: IntArray = IntArray(N_CORNERS) { it },
    val co: IntArray = IntArray(N_CORNERS) { 0 },
    val ep: IntArray = IntArray(N_EDGES) { it },
    val eo: IntArray = IntArray(N_EDGES) { 0 },
) {
    /** True if every cubie is in its home slot with zero twist. */
    fun isSolved(): Boolean =
        cp.contentEquals(SOLVED.cp) &&
            co.contentEquals(SOLVED.co) &&
            ep.contentEquals(SOLVED.ep) &&
            eo.contentEquals(SOLVED.eo)

    // Generated equals / hashCode would compare arrays by reference; override.
    override fun equals(other: Any?): Boolean =
        other is CubeState &&
            cp.contentEquals(other.cp) &&
            co.contentEquals(other.co) &&
            ep.contentEquals(other.ep) &&
            eo.contentEquals(other.eo)

    override fun hashCode(): Int {
        var r = cp.contentHashCode()
        r = 31 * r + co.contentHashCode()
        r = 31 * r + ep.contentHashCode()
        r = 31 * r + eo.contentHashCode()
        return r
    }

    companion object {
        val SOLVED = CubeState()
    }
}

/** Per-face cycle tables for clockwise quarter turns. */
private data class FaceMoveData(
    val cornerCycle: IntArray,
    val cornerTwist: IntArray,
    val edgeCycle: IntArray,
    val edgeFlip: IntArray,
)

private val FACE_MOVES: Map<CubeFace, FaceMoveData> = mapOf(
    CubeFace.U to FaceMoveData(
        intArrayOf(0, 3, 2, 1), intArrayOf(0, 0, 0, 0),
        intArrayOf(0, 3, 2, 1), intArrayOf(0, 0, 0, 0),
    ),
    CubeFace.D to FaceMoveData(
        intArrayOf(4, 5, 6, 7), intArrayOf(0, 0, 0, 0),
        intArrayOf(4, 5, 6, 7), intArrayOf(0, 0, 0, 0),
    ),
    CubeFace.F to FaceMoveData(
        intArrayOf(0, 1, 5, 4), intArrayOf(1, 2, 1, 2),
        intArrayOf(1, 9, 5, 8), intArrayOf(1, 1, 1, 1),
    ),
    CubeFace.B to FaceMoveData(
        intArrayOf(2, 3, 7, 6), intArrayOf(1, 2, 1, 2),
        intArrayOf(3, 11, 7, 10), intArrayOf(1, 1, 1, 1),
    ),
    CubeFace.R to FaceMoveData(
        intArrayOf(0, 4, 7, 3), intArrayOf(2, 1, 2, 1),
        intArrayOf(0, 8, 4, 11), intArrayOf(0, 0, 0, 0),
    ),
    CubeFace.L to FaceMoveData(
        intArrayOf(1, 2, 6, 5), intArrayOf(1, 2, 1, 2),
        intArrayOf(2, 10, 6, 9), intArrayOf(0, 0, 0, 0),
    ),
)

/** Apply one CW quarter turn. */
fun applyMoveCW(state: CubeState, face: CubeFace): CubeState {
    val m = FACE_MOVES.getValue(face)
    val cp = state.cp.copyOf(); val co = state.co.copyOf()
    val ep = state.ep.copyOf(); val eo = state.eo.copyOf()
    val cc = m.cornerCycle; val ec = m.edgeCycle

    val tCP = state.cp[cc[0]]; val tCO = state.co[cc[0]]
    for (i in 0..2) {
        cp[cc[i]] = state.cp[cc[i + 1]]
        co[cc[i]] = (state.co[cc[i + 1]] + m.cornerTwist[i]) % 3
    }
    cp[cc[3]] = tCP
    co[cc[3]] = (tCO + m.cornerTwist[3]) % 3

    val tEP = state.ep[ec[0]]; val tEO = state.eo[ec[0]]
    for (i in 0..2) {
        ep[ec[i]] = state.ep[ec[i + 1]]
        eo[ec[i]] = (state.eo[ec[i + 1]] + m.edgeFlip[i]) % 2
    }
    ep[ec[3]] = tEP
    eo[ec[3]] = (tEO + m.edgeFlip[3]) % 2

    return CubeState(cp, co, ep, eo)
}

/** CW = 1 turn, CCW = 3 turns. Half-turn callers should use [applyMoves]. */
fun applyMove(state: CubeState, face: CubeFace, clockwise: Boolean): CubeState {
    val turns = if (clockwise) 1 else 3
    var s = state
    repeat(turns) { s = applyMoveCW(s, face) }
    return s
}

/** Apply a sequence of CubeMove (with `times`). */
fun applyMoves(state: CubeState, moves: List<CubeMove>): CubeState {
    var s = state
    for (m in moves) repeat(m.times) { s = applyMoveCW(s, m.face) }
    return s
}

// ---------------------------------------------------------------------------
// Kociemba facelet string conversion. 54-character "URFDLB" format.
// ---------------------------------------------------------------------------

private val CORNER_FACELET_MAP = arrayOf(
    intArrayOf(8, 9, 20), intArrayOf(6, 18, 38),
    intArrayOf(0, 36, 47), intArrayOf(2, 45, 11),
    intArrayOf(29, 26, 15), intArrayOf(27, 44, 24),
    intArrayOf(33, 53, 42), intArrayOf(35, 17, 51),
)
private val EDGE_FACELET_MAP = arrayOf(
    intArrayOf(5, 10), intArrayOf(7, 19), intArrayOf(3, 37), intArrayOf(1, 46),
    intArrayOf(32, 16), intArrayOf(28, 25), intArrayOf(30, 43), intArrayOf(34, 52),
    intArrayOf(23, 12), intArrayOf(21, 41), intArrayOf(50, 39), intArrayOf(48, 14),
)

fun CubeState.toKociembaFacelets(): String {
    val faces = "URFDLB"
    val f = CharArray(54) { faces[it / 9] }
    for (i in 0 until 8) for (p in 0..2) {
        f[CORNER_FACELET_MAP[i][(p + co[i]) % 3]] =
            faces[CORNER_FACELET_MAP[cp[i]][p] / 9]
    }
    for (i in 0 until 12) for (p in 0..1) {
        f[EDGE_FACELET_MAP[i][(p + eo[i]) % 2]] =
            faces[EDGE_FACELET_MAP[ep[i]][p] / 9]
    }
    return f.concatToString()
}
