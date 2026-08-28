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

/**
 * Inverse of [toKociembaFacelets]. Parse a 54-char URFDLB-ordered
 * sticker string back into a [CubeState]. Returns null when the input
 * is malformed (wrong length; non-`URFDLB` character; sticker pattern
 * not matching any valid corner/edge cubie permutation).
 *
 * Driven by the same [CORNER_FACELET_MAP] / [EDGE_FACELET_MAP] tables
 * the forward direction uses. For each corner slot:
 *   1. Read the three facelet colors at the slot's facelet indices.
 *   2. Find the canonical corner cubie whose unordered colour triple
 *      matches. That's [cp].
 *   3. Determine orientation by which of the three stickers carries
 *      the U/D colour. Position-of-UD = co (per the forward formula
 *      `(p + co[i]) % 3` where p = 0 is the canonical UD facelet).
 *
 * Edges are analogous but simpler: each edge has a designated
 * "primary" sticker (UD-colour for UD-layer edges, FB-colour for
 * middle-layer edges – matching the standard Kociemba/F2L convention
 * encoded in the EDGE_FACELET_MAP rows). Orientation is 0 if the
 * cubie's primary sticker is at slot position 0, else 1.
 *
 * Centres at indices 4/13/22/31/40/49 are not read – they're implicit
 * (they define the colour-to-face mapping). The caller is expected to
 * supply a string where centres already match URFDLB; the MoYu
 * decoder constructs the string with that invariant in mind.
 *
 * **Cost.** O(54) parsing, no allocations beyond the result. Called
 * once per Facelets event (a handful of times per minute on a normal
 * connection), so performance isn't a concern.
 *
 * Used by the MoYu V10 driver to convert the cube's
 * `FBUDLR`-ordered sticker-colour facelets event into our internal
 * [CubeState]. GAN cubes report state already in CP/CO/EP/EO form and
 * never call this.
 */
fun CubeState.Companion.fromKociembaFacelets(facelets: String): CubeState? {
    if (facelets.length != 54) return null
    val faces = "URFDLB"
    // Quick character whitelist – any unknown char is an outright reject.
    if (facelets.any { it !in faces }) return null

    fun faceOf(idx: Int): Char = facelets[idx]

    // -- Corners --
    val cp = IntArray(N_CORNERS)
    val co = IntArray(N_CORNERS)
    for (slot in 0 until N_CORNERS) {
        val pos = CORNER_FACELET_MAP[slot]
        // Three colours at the slot's three facelet positions, in canonical
        // order (p=0 is the slot's UD facelet, p=1 / p=2 are the side
        // facelets in canonical rotation).
        val c0 = faceOf(pos[0])
        val c1 = faceOf(pos[1])
        val c2 = faceOf(pos[2])
        // Twist = which p has the U or D colour. Per the forward formula
        // `(p + co[i]) % 3 = 0` solves to p = (3 - co[i]) % 3, so the
        // location of the UD sticker tells us co directly: co = location.
        val twist = when {
            c0 == 'U' || c0 == 'D' -> 0
            c1 == 'U' || c1 == 'D' -> 1
            c2 == 'U' || c2 == 'D' -> 2
            else -> return null  // No UD sticker → corner is impossible.
        }
        co[slot] = twist
        // Read the cubie's three colours in CANONICAL (untwisted) order so
        // we can match against the catalogue: rotate (c0, c1, c2) by -twist.
        val canonical = when (twist) {
            0 -> Triple(c0, c1, c2)
            1 -> Triple(c1, c2, c0)
            2 -> Triple(c2, c0, c1)
            else -> error("unreachable")
        }
        // Find cubie j whose canonical colour triple matches.
        var found = -1
        for (j in 0 until N_CORNERS) {
            val jc0 = faces[CORNER_FACELET_MAP[j][0] / 9]
            val jc1 = faces[CORNER_FACELET_MAP[j][1] / 9]
            val jc2 = faces[CORNER_FACELET_MAP[j][2] / 9]
            if (jc0 == canonical.first && jc1 == canonical.second && jc2 == canonical.third) {
                found = j
                break
            }
        }
        if (found < 0) return null
        cp[slot] = found
    }
    // Permutation must be a bijection of 0..7.
    if (cp.toSet().size != N_CORNERS) return null

    // -- Edges --
    val ep = IntArray(N_EDGES)
    val eo = IntArray(N_EDGES)
    for (slot in 0 until N_EDGES) {
        val pos = EDGE_FACELET_MAP[slot]
        val c0 = faceOf(pos[0])
        val c1 = faceOf(pos[1])
        // Find cubie j whose canonical colour pair matches (unordered).
        // Canonical primary colour = colour at p=0 of cubie j's slot.
        var found = -1
        var flip = 0
        for (j in 0 until N_EDGES) {
            val jc0 = faces[EDGE_FACELET_MAP[j][0] / 9]
            val jc1 = faces[EDGE_FACELET_MAP[j][1] / 9]
            if (jc0 == c0 && jc1 == c1) { found = j; flip = 0; break }
            if (jc0 == c1 && jc1 == c0) { found = j; flip = 1; break }
        }
        if (found < 0) return null
        ep[slot] = found
        eo[slot] = flip
    }
    if (ep.toSet().size != N_EDGES) return null

    return CubeState(cp = cp, co = co, ep = ep, eo = eo)
}
