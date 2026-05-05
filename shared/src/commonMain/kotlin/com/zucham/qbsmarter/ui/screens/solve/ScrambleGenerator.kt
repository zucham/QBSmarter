package com.zucham.qbsmarter.ui.screens.solve

import com.zucham.qbsmarter.domain.cube.CubeFace
import kotlin.random.Random

/**
 * Random-move 3x3 scrambler that approximates a WCA-style scramble.
 *
 * **Important caveat.** This is *not* a bit-exact WCA scramble. The
 * official WCA program (TNoodle) generates a uniformly random cube
 * state and then asks Kociemba's two-phase algorithm for an
 * inverse-solution as the scramble; that produces a uniformly-
 * distributed scramble at variable length 17–21. We do not currently
 * embed a Kociemba solver. Instead, we generate random moves with
 * canonical filtering at slightly higher length (19–23) to compensate
 * for the lack of move-cancellation that a solver gives us. The
 * resulting scrambles are statistically close to uniform but not
 * exactly so. Good enough for casual practice; not WCA-grade.
 *
 * **Filtering rules** match the move-canonicalisation pruning that
 * Kociemba's solver enforces internally:
 *
 *   1. **Same face forbidden.** Two consecutive moves on the same
 *      face (e.g. `R R'` or `R R2`) are equivalent to a single move,
 *      so they're rejected. Without this rule a scramble could
 *      degenerate (e.g. `R R'` is a no-op, wasting two moves).
 *
 *   2. **Sandwich forbidden.** A move whose face equals the
 *      *previous-previous* face AND whose previous move is the
 *      *opposite* face – for example `R L R`, `R L R'`, `R L2 R'`.
 *      All such sequences canonicalize to a 2-move equivalent
 *      (`R L R` ≡ `R2 L`), and emitting the longer form would mean
 *      two 3-move-prefix sequences map to the same state. Min2phase
 *      enforces this same rule via `ckmv2bit` during search.
 *
 * Both rules are face-only – they apply regardless of the modifier
 * (`""`, `'`, `2`). The modifier is then drawn uniformly from
 * {`""`, `'`, `2`} for each accepted face.
 *
 * **Length variability.** Each call samples a length uniformly from
 * [DEFAULT_MIN_LENGTH, DEFAULT_MAX_LENGTH] inclusive. The default
 * range (19–23) is centerd around TNoodle's typical 18–20 with a
 * couple of extra moves on each end to compensate for the fact that
 * we don't run a solver. Callers that want a fixed length can pass
 * one in (used by tests and by any future "competition mode" that
 * wants a stable length).
 *
 * **Threading.** Stateless across calls. Safe to share one instance
 * across threads as long as the underlying [Random] is thread-safe;
 * `Random.Default` is.
 */
class ScrambleGenerator(private val random: Random = Random.Default) {

    private val faces = CubeFace.entries

    /**
     * Generate a single scramble.
     *
     * @param length Target length, or `null` to sample from
     *   [DEFAULT_MIN_LENGTH]..[DEFAULT_MAX_LENGTH] uniformly. Passing a
     *   fixed value is mostly for tests; production callers should
     *   leave it at the default.
     */
    fun generate(length: Int? = null): String {
        val targetLength = length
            ?: random.nextInt(DEFAULT_MIN_LENGTH, DEFAULT_MAX_LENGTH + 1)
        require(targetLength > 0) { "Length must be positive, got $targetLength" }

        val tokens = ArrayList<String>(targetLength)
        // Track the previous two moves' FACES (modifiers don't affect the
        // canonical filtering rules – see the kdoc above).
        var prev: CubeFace? = null
        var prevPrev: CubeFace? = null

        repeat(targetLength) {
            val face = pickFace(prev, prevPrev)
            tokens += face.name + MODIFIERS[random.nextInt(MODIFIERS.size)]
            prevPrev = prev
            prev = face
        }
        return tokens.joinToString(" ")
    }

    /**
     * Pick the next face given the previous two. Builds the candidate
     * list explicitly (rather than retry-rejecting) so the work is
     * bounded and the distribution over allowed faces is exactly uniform.
     *
     * Number of allowed faces by case:
     *  - `prev == null` (first move): 6 allowed.
     *  - `prev != null, prevPrev == null` (second move): 5 allowed
     *    (anything but `prev`).
     *  - `prev != null, prevPrev = opposite(prev)` (sandwich-forming):
     *    4 allowed. The same-face rule rejects `prev`; the sandwich
     *    rule rejects `prevPrev` (which is `opposite(prev)`).
     *  - `prev != null, prevPrev != null, prevPrev != opposite(prev)`:
     *    5 allowed. The sandwich rule needs `prev == opposite(f)` AND
     *    `f == prevPrev`; the only `f` satisfying the first half is
     *    `opposite(prev)`, but then `f == prevPrev` requires
     *    `prevPrev == opposite(prev)`, which is false here. So the
     *    sandwich rule never fires; only the same-face rule applies.
     */
    private fun pickFace(prev: CubeFace?, prevPrev: CubeFace?): CubeFace {
        if (prev == null) return faces[random.nextInt(faces.size)]
        // Build the allowed list. Six faces is small enough that the
        // explicit loop is faster than building a Set and removing.
        val allowed = ArrayList<CubeFace>(faces.size)
        for (f in faces) {
            if (f == prev) continue
            if (prevPrev != null && f == prevPrev && prev == OPPOSITE[f.ordinal]) continue
            allowed += f
        }
        return allowed[random.nextInt(allowed.size)]
    }

    private companion object {
        /** Move modifiers, three uniform options. */
        val MODIFIERS = arrayOf("", "'", "2")

        /**
         * Lower bound for default sampled scramble length. TNoodle
         * typically emits 17–21 moves; we go slightly longer because
         * random-move scrambles can include more "wasted" moves (e.g.
         * `R F R'` doesn't cancel under our rules but a Kociemba
         * solver wouldn't emit it).
         */
        const val DEFAULT_MIN_LENGTH = 19

        /** Upper bound for default sampled scramble length. */
        const val DEFAULT_MAX_LENGTH = 23

        /**
         * Lookup table for [CubeFace.opposite()] indexed by ordinal,
         * so the per-call pick loop avoids a `when` branch. Order
         * follows [CubeFace.entries]: U R F D L B.
         */
        val OPPOSITE: Array<CubeFace> = arrayOf(
            CubeFace.D, // U
            CubeFace.L, // R
            CubeFace.B, // F
            CubeFace.U, // D
            CubeFace.R, // L
            CubeFace.F, // B
        )
    }
}
