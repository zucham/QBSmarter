package com.zucham.qbsmarter.domain.cube

/** A single quarter or half turn. The `times` field is 1, 2, or 3 (== inverse). */
data class CubeMove(val face: CubeFace, val times: Int) {

    init {
        require(times in 1..3) { "times must be 1..3, got $times" }
    }

    /**
     * The inverse move: same face, opposite quarter direction; half-turns
     * (`times == 2`) are self-inverse so they stay unchanged.
     *
     *   R   ↔  R'      (times 1 ↔ 3)
     *   R2  ↔  R2      (times 2)
     */
    fun inverse(): CubeMove = when (times) {
        1 -> CubeMove(face, 3)
        3 -> CubeMove(face, 1)
        else -> this
    }

    /** WCA-style notation: "R", "R'", "R2". */
    fun notation(): String = when (times) {
        1 -> face.name
        2 -> "${face.name}2"
        else -> "${face.name}'"
    }

    companion object {
        /**
         * Parse a single WCA token: "R", "R'", "R2".
         * `'` means a CCW quarter (encoded as `times = 3`); a digit suffix
         * is the half-turn ("R2" = `times = 2`).
         */
        fun parse(token: String): CubeMove {
            require(token.isNotEmpty()) { "Empty move token" }
            val face = CubeFace.valueOf(token[0].toString())
            val times = when {
                token.length == 1 -> 1
                token == "${face.name}2" -> 2
                token == "${face.name}'" -> 3
                else -> error("Unrecognized move token: '$token'")
            }
            return CubeMove(face, times)
        }

        /** Parse a whitespace-separated WCA scramble, e.g. "R U R' U' F2". */
        fun parseAll(scramble: String): List<CubeMove> =
            scramble.split(' ', '\t', '\n')
                .filter { it.isNotBlank() }
                .map(::parse)

        /**
         * Collapse adjacent same-face moves into their compact form.
         * Pure data transformation – does not look at the rest of the
         * scramble or any cube state.
         *
         * Used for rendering the correction prefix in a human-friendly
         * way: two consecutive `U'` moves become `U2`, `R + R + R + R`
         * disappears entirely (R^4 = identity), `R + R'` is dropped, and
         * `R2 R` becomes `R'` (half-turn followed by the same face's
         * quarter is the inverse-quarter).
         *
         * Algorithm: walk the list once, treating the output as a stack;
         * for each new move, if the top of the stack is on the same face,
         * compose them (sum of `times` mod 4) and either replace the top
         * (1, 2, 3) or drop it (0). Otherwise push.
         */
        fun mergeAdjacentSameFace(moves: List<CubeMove>): List<CubeMove> {
            val out = ArrayDeque<CubeMove>()
            for (move in moves) {
                val top = out.lastOrNull()
                if (top != null && top.face == move.face) {
                    out.removeLast()
                    val combinedTimes = (top.times + move.times) % 4
                    if (combinedTimes != 0) out.addLast(CubeMove(move.face, combinedTimes))
                } else {
                    out.addLast(move)
                }
            }
            return out.toList()
        }
    }
}
