package com.zucham.qbsmarter.domain.driver.moyu

import com.zucham.qbsmarter.domain.cube.CubeState
import com.zucham.qbsmarter.domain.cube.fromKociembaFacelets

/**
 * Decodes the MoYu V10 AI's Facelets event payload (event type 0xA3,
 * 48 stickers × 3 bits) into a [CubeState].
 *
 * **Wire format.** Stickers are laid out face-by-face in `FBUDLR`
 * order. Eight stickers per face (centres excluded; centres are
 * implicit and fixed). Each sticker is a 3-bit colour index:
 *
 *   | Value | Colour | Maps to face (WCA orientation) |
 *   |-------|--------|--------------------------------|
 *   | 0     | Green  | F                              |
 *   | 1     | Blue   | B                              |
 *   | 2     | White  | U                              |
 *   | 3     | Yellow | D                              |
 *   | 4     | Orange | L                              |
 *   | 5     | Red    | R                              |
 *
 * The eight per-face stickers are in row-major order, scanning from
 * top-left to bottom-right, with the centre position omitted. So a
 * face reads as positions 0,1,2,3,5,6,7,8 in a 3×3 grid (centre = 4).
 *
 * **Why a separate decoder instead of a slot-by-slot CP/CO/EP/EO
 * read.** GAN cubes report the cube in CP/CO/EP/EO form directly;
 * their parsers can dump bits into [CubeState]'s arrays. MoYu reports
 * sticker colours – the universal "human-eye" view of the cube. To
 * map back to CP/CO/EP/EO we have to walk corners + edges, identify
 * each by its colour set, and reconstruct orientation. The shared
 * [CubeState.Companion.fromKociembaFacelets] helper does that work
 * given a 54-char URFDLB string, so the only MoYu-specific job here
 * is the FBUDLR-to-URFDLB face re-order plus the colour-to-face
 * relabelling.
 *
 * **Orientation assumption.** The colour-to-face map above is the WCA
 * scrambling orientation (green front, white top). If the user is
 * holding the cube with a different colour up or facing them, the
 * Facelets event will decode to an unrecognisable state and we'll
 * return null – the caller (the driver) ignores null and waits for a
 * proper snapshot to land. GAN cubes have the same assumption
 * implicitly; the user is expected to orient the cube correctly when
 * pairing.
 *
 * **Cost.** O(54) per call, allocations bounded. Called once per
 * Facelets event (a handful of times per minute on a normal
 * connection), so performance isn't a concern.
 */
internal object MoyuFaceletDecoder {

    /**
     * Colour-index → Kociemba face-letter. Indexed by the 3-bit value
     * the cube emits per sticker.
     */
    private val COLOR_TO_FACE: CharArray = charArrayOf(
        /* 0 Green  */ 'F',
        /* 1 Blue   */ 'B',
        /* 2 White  */ 'U',
        /* 3 Yellow */ 'D',
        /* 4 Orange */ 'L',
        /* 5 Red    */ 'R',
    )

    /**
     * MoYu reports faces in F, B, U, D, L, R order – Kociemba expects
     * U, R, F, D, L, B order. This table maps Kociemba face index
     * (0..5 for URFDLB) → MoYu face index (0..5 for FBUDLR), so we
     * can fill the Kociemba string left-to-right.
     */
    private val KOC_FACE_TO_MOYU_FACE: IntArray = intArrayOf(
        /* U=2 in MoYu */ 2,
        /* R=5 in MoYu */ 5,
        /* F=0 in MoYu */ 0,
        /* D=3 in MoYu */ 3,
        /* L=4 in MoYu */ 4,
        /* B=1 in MoYu */ 1,
    )

    /**
     * Decode the 48-sticker colour array into a [CubeState], or null if
     * the sticker pattern doesn't form a valid permutation (corrupted
     * packet, wrong cube orientation, etc.).
     *
     * @param stickers 48 values, each 0..5, in MoYu FBUDLR face order
     *   and per-face row-major scan order (centres excluded).
     */
    fun decode(stickers: IntArray): CubeState? {
        if (stickers.size != 48) return null
        if (stickers.any { it !in 0..5 }) return null

        // Build the 54-char Kociemba facelet string by walking URFDLB
        // face order. For each face, we need 9 characters in row-major
        // order. The centre is at position 4 and is determined by the
        // face itself; the other 8 come from MoYu's per-face block.
        //
        // ASSUMPTION on per-face sticker order: the 8 per-face stickers
        // are read in row-major scan order with the centre (position 4)
        // omitted – i.e. the cube reports positions 0,1,2,3,5,6,7,8 in
        // that order. This matches the conventional read order used by
        // GAN's Gen2 cubes when laid out on a 3×3 face grid and is the
        // most natural interpretation of the protocol writeup, which
        // doesn't explicitly specify the per-face permutation.
        //
        // If hardware testing reveals a different per-face ordering, the
        // fix is local to this method: change [NON_CENTRE_POSITIONS] to
        // reflect the actual order. The corresponding inverse (used by
        // anyone calling [CubeState.toKociembaFacelets] back into MoYu's
        // wire format) would need the same edit. Doing it here keeps
        // the protocol-version-of-truth in one place.
        val out = CharArray(54)
        for (kocFace in 0 until 6) {
            val moyuFace = KOC_FACE_TO_MOYU_FACE[kocFace]
            val moyuOffset = moyuFace * 8  // 8 stickers per face, no centre
            val kocOffset = kocFace * 9  // 9 stickers per face, centre included
            // Centre is the face's own colour letter.
            val centreFace = "URFDLB"[kocFace]
            out[kocOffset + 4] = centreFace
            // 8 non-centre stickers, in row-major positions.
            for (p in 0 until 8) {
                val colourIdx = stickers[moyuOffset + p]
                out[kocOffset + NON_CENTRE_POSITIONS[p]] = COLOR_TO_FACE[colourIdx]
            }
        }

        return CubeState.fromKociembaFacelets(out.concatToString())
    }

    /**
     * Per-face grid positions (within a 3×3 face) for the 8 non-centre
     * stickers, in the order the MoYu cube emits them. Row-major scan
     * with centre (position 4) omitted is the working assumption – see
     * the long-form comment inside [decode].
     */
    private val NON_CENTRE_POSITIONS: IntArray = intArrayOf(0, 1, 2, 3, 5, 6, 7, 8)
}
