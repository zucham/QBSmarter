package com.zucham.qbsmarter.domain.driver.giiker

import com.zucham.qbsmarter.domain.cube.CubeState
import com.zucham.qbsmarter.domain.cube.fromKociembaFacelets
import com.zucham.qbsmarter.domain.driver.CubeVendor
import com.zucham.qbsmarter.domain.driver.SmartCubeCommand
import com.zucham.qbsmarter.domain.driver.SmartCubeEvent
import com.zucham.qbsmarter.domain.driver.protocol.CubeProtocol
import com.zucham.qbsmarter.domain.driver.protocol.KOCIEMBA_FACE_ORDER
import com.zucham.qbsmarter.domain.driver.protocol.ProtocolIo
import com.zucham.qbsmarter.domain.driver.protocol.cubeFaceOf
import com.zucham.qbsmarter.util.currentTimeMillis

/**
 * GiiKER Super Cube i3 / i3S / i3SE and Xiaomi Mi Smart Magic Cube.
 *
 * **One notification says everything.** Unlike every other family here,
 * Giiker has no opcodes, no message types and no request surface: it
 * pushes a single fixed-shape 20-byte frame that contains the complete
 * cube state *and* a short move history, unprompted, whenever anything
 * turns. There is nothing to ask for, which is why [buildCommand]
 * returns null for everything and why there is no handshake to run
 * (see below).
 *
 * **No handshake.** [onConnected] is deliberately not overridden. The
 * cube starts pushing state the instant notifications are enabled — in
 * fact it pushes one immediately on connect — so the default no-op is
 * exactly right. Writing anything at all to it here would be cargo cult.
 *
 * **No AES, but not always plaintext either.** A frame is either sent
 * in the clear or lightly obfuscated with a fixed 36-byte key table,
 * and it says which via a marker byte at offset 18 — there is no
 * negotiation and no device flag to consult, so [deobfuscate] has to
 * sniff every packet. See that function for why the two cases end up
 * different *lengths*, which is the single most surprising property of
 * this protocol.
 *
 * **No integrity check anywhere.** No CRC, no checksum, no length
 * field, no serial number. A corrupted frame is indistinguishable from
 * a real one at the framing level, so the only defence is to bounds-
 * check and range-check every field before using it and to drop the
 * frame rather than throw — a garbled permutation index must not take
 * the ingest coroutine down. Every read below is written with that in
 * mind.
 *
 * **No serial number also means no deduplication.** The move history in
 * a frame is *newest first* and overlaps heavily with the previous
 * frame's, and nothing labels which entries are new. The strategy here
 * is therefore: emit only the newest move, only when the state block
 * actually changed, and drop a notification whose payload is
 * byte-identical to the one before it. See [decode].
 *
 * **No gyroscope.** Giiker genuinely has no orientation sensor — not
 * "we cannot tell yet", which is what GoCube and QiYi report as a null
 * [SmartCubeEvent.Hardware.gyroSupported]. `false` here is a positive
 * statement about the hardware, and it is what lets the UI hide the
 * orientation controls permanently instead of waiting forever for a
 * stream that will never start.
 *
 * Stateful: it remembers the last payload (for duplicate suppression),
 * the last state nibbles (to decide whether a move happened) and
 * whether it has announced the hardware. Per-connection instance, so
 * none of that needs resetting.
 */
internal class GiikerProtocol : CubeProtocol {

    override val vendor: CubeVendor = CubeVendor.GIIKER

    override val id: String = "giiker"

    /**
     * The previous *deobfuscated* payload, 18 or 20 bytes.
     *
     * Two purposes, both about the missing serial number. The first is
     * the one cubing.js documents: the cube emits a spurious duplicate
     * of its current frame right after connect, and without this the
     * app would replay the last move the user made before pairing. The
     * second is general — a repeated frame carries a repeated move
     * history, and there is no counter to tell it apart from a genuine
     * repeat of the same turn.
     *
     * Compared *after* deobfuscation on purpose: the key-selector byte
     * at offset 19 changes from frame to frame, so two obfuscated
     * frames with identical content are not identical on the wire but
     * are identical here.
     */
    private var lastPayload: ByteArray? = null

    /**
     * The previous frame's state nibbles (the first [STATE_NIBBLES]).
     *
     * The gate for move emission. A frame whose state block is
     * unchanged carries no new turn no matter what its history section
     * says, and null — no frame seen yet — means the very first frame
     * of the connection, which is a state push rather than a move.
     */
    private var lastStateNibbles: IntArray? = null

    /** Whether [SmartCubeEvent.Hardware] has already been announced. */
    private var hardwareAnnounced: Boolean = false

    /**
     * Giiker has no command surface reachable from this architecture,
     * so every command maps to null and the driver skips the write.
     *
     * Most of that is inherent to the protocol: state is pushed
     * unprompted on every change (so [SmartCubeCommand.RequestFacelets]
     * has nothing to ask for), there is no hardware-info opcode at all
     * (the [SmartCubeEvent.Hardware] event below is synthesised), the
     * cube is reset with its physical gesture rather than over BLE, and
     * without a serial number there is no
     * [SmartCubeCommand.RequestMoveHistory] window to name.
     *
     * Battery is the one real gap, and it is architectural rather than
     * protocol-level. Giiker exposes battery on a **second GATT
     * service**, `0000aaaa-…`: write `0xB5` to characteristic
     * `0000aaac-…` and the level arrives as byte 1 of a notification on
     * `0000aaab-…`. Our transport binds exactly one service per
     * connection — the state service `0000aadb-…` — so those handles do
     * not exist as far as this protocol is concerned, and a payload
     * built here would be written to the wrong characteristic.
     *
     * TODO: reaching battery requires a multi-service transport (a
     *  second bound service plus a second notification source routed
     *  into the same ingest). Until then [SmartCubeCommand.RequestBattery]
     *  is knowingly unimplemented rather than merely unsupported, and
     *  the Devices screen will show no battery for a Giiker cube.
     */
    override fun buildCommand(cmd: SmartCubeCommand): ByteArray? = null

    /**
     * Deobfuscate, drop duplicates, then read state and (at most) one
     * move out of the nibble stream.
     *
     * The ordering of the emitted events matters: the move goes out
     * before the facelets, because the facelets describe the state
     * *after* that turn. The app can animate the quarter turn and then
     * resync to a snapshot that already agrees with it — the same
     * convention the QiYi protocol uses.
     */
    override suspend fun decode(packet: ByteArray, io: ProtocolIo): List<SmartCubeEvent> {
        val now = currentTimeMillis()

        // A Giiker frame is exactly 20 bytes. There is no length field
        // to consult and no shorter valid form, so anything smaller is
        // truncation and anything larger has trailing bytes we ignore.
        if (packet.size < PACKET_BYTES) return emptyList()

        val payload = deobfuscate(packet)

        // Byte-identical repeat: no serial number exists to tell a real
        // repeated turn from a resent frame, so the frame is dropped
        // whole. See [lastPayload].
        if (lastPayload?.contentEquals(payload) == true) return emptyList()
        lastPayload = payload

        val nibbles = toNibbles(payload)
        // 36 nibbles from an obfuscated frame, 40 from a plaintext one;
        // both comfortably cover the state block, but the check is here
        // because everything below indexes into this array.
        if (nibbles.size < STATE_NIBBLES) return emptyList()

        val stateNibbles = nibbles.copyOf(STATE_NIBBLES)
        val previousState = lastStateNibbles
        lastStateNibbles = stateNibbles
        val stateChanged = previousState != null && !previousState.contentEquals(stateNibbles)

        val events = mutableListOf<SmartCubeEvent>()

        // The cube never identifies itself — no name frame, no firmware
        // version, no capability bitmap — so the announcement is
        // synthesised the first time a frame decodes far enough to
        // prove we really are talking to a Giiker.
        if (!hardwareAnnounced) {
            hardwareAnnounced = true
            events += SmartCubeEvent.Hardware(
                deviceTimestamp = now,
                name = HARDWARE_NAME,
                hwVersion = "",
                swVersion = "",
                // false, not null: this hardware has no gyroscope at
                // all. Null would mean "not known yet" and would leave
                // the orientation controls waiting on a stream that
                // does not exist. See the class KDoc.
                gyroSupported = false,
                vendor = CubeVendor.GIIKER,
            )
        }

        if (stateChanged && nibbles.size >= STATE_NIBBLES + MOVE_RECORD_NIBBLES) {
            events += decodeNewestMove(nibbles, now)
        }

        decodeFacelets(stateNibbles)?.let {
            events += SmartCubeEvent.Facelets(state = it, deviceTimestamp = now)
        }

        return events
    }

    // -- Framing ----------------------------------------------------------

    /**
     * Undo Giiker's optional obfuscation, returning the payload the
     * nibble decoder should read.
     *
     * The frame is self-describing: byte 18 holds [OBFUSCATION_MARKER]
     * when the first 18 bytes have been scrambled, and byte 19 then
     * holds two 4-bit offsets into [OBFUSCATION_KEY], packed high
     * nibble then low. Recovery is a plain byte-wise add of the two
     * selected key windows, modulo 256 — the firmware subtracted the
     * same two windows on the way out, so this is not encryption in any
     * useful sense. There is no key exchange and no per-device salt;
     * the table is the same in every unit ever shipped.
     *
     * The index arithmetic is safe by construction: `i` reaches 17 and
     * each selector reaches 15, so `i + k` reaches 32 in a 36-entry
     * table.
     *
     * **The two branches return different lengths, and that is not a
     * bug.** An obfuscated frame keeps only its 18 scrambled bytes —
     * bytes 18 and 19 are the marker and the key selectors, not data —
     * while a plaintext frame keeps all 20. Expanded to nibbles that is
     * 36 versus 40, and since the state block occupies nibbles 0..31 an
     * obfuscated cube delivers only **two** history moves where a
     * plaintext one delivers **four**. Every loop over the history is
     * therefore bounded by the actual nibble count rather than by a
     * constant, or an obfuscated cube would read past its own payload.
     */
    private fun deobfuscate(packet: ByteArray): ByteArray {
        if (packet[OBFUSCATION_MARKER_OFFSET] != OBFUSCATION_MARKER) {
            return packet.copyOf(PACKET_BYTES)
        }

        val selectors = packet[KEY_SELECTOR_OFFSET].toInt() and 0xFF
        val k1 = selectors shr 4
        val k2 = selectors and 0x0F

        val out = ByteArray(OBFUSCATED_BODY_BYTES)
        for (i in 0 until OBFUSCATED_BODY_BYTES) {
            val value = (packet[i].toInt() and 0xFF) +
                OBFUSCATION_KEY[i + k1] +
                OBFUSCATION_KEY[i + k2]
            out[i] = (value and 0xFF).toByte()
        }
        return out
    }

    /**
     * Expand bytes to nibbles, **high nibble first**.
     *
     * Giiker packs two 4-bit fields per byte in big-endian order, the
     * opposite of QiYi's facelet packing. Getting it backwards produces
     * a permutation that is still made of plausible 0..15 values and
     * still passes every range check, so the mistake shows up only as a
     * cube that is permanently in an impossible state.
     */
    private fun toNibbles(payload: ByteArray): IntArray {
        val out = IntArray(payload.size * 2)
        for (i in payload.indices) {
            val b = payload[i].toInt() and 0xFF
            out[i * 2] = (b shr 4) and 0x0F
            out[i * 2 + 1] = b and 0x0F
        }
        return out
    }

    // -- State ------------------------------------------------------------

    /**
     * Nibbles 0..31 — the full cube state, as cubie permutation and
     * orientation rather than as stickers.
     *
     * Layout: corner permutation in nibbles 0..7, corner orientation in
     * 8..15, edge permutation in 16..27, edge orientation packed as
     * twelve bits across nibbles 28..30. Nibble 31 is unused padding.
     *
     * **The cubie numbering is Giiker's own, and so is the orientation
     * reference.** It is not Kociemba's and not any other vendor's, so
     * the permutation cannot simply be copied into a [CubeState] — it
     * has to be rendered back out to stickers through Giiker's own
     * facelet tables ([CORNER_FACELETS] / [EDGE_FACELETS]) and then
     * re-parsed. That is what the loops below do: the standard Kociemba
     * "place the cubie that lives at slot j onto slot i, rotated by its
     * orientation" construction, but with the custom tables on **both**
     * sides of the assignment. Using the custom table on one side and a
     * standard one on the other is the classic way to get a facelet
     * string that looks fine and decodes to nonsense.
     *
     * Every permutation index is range-checked before use. There is no
     * checksum in this protocol, so a single corrupted nibble reaches
     * here as an out-of-range cubie id; returning null drops the
     * snapshot and leaves the app on its last good state until the next
     * frame lands, which is a few milliseconds away.
     */
    private fun decodeFacelets(nibbles: IntArray): CubeState? {
        val cornerPerm = IntArray(CORNER_COUNT)
        val cornerTwist = IntArray(CORNER_COUNT)
        for (i in 0 until CORNER_COUNT) {
            val perm = nibbles[i] - 1
            if (perm !in 0 until CORNER_COUNT) return null
            cornerPerm[i] = perm
            // The mask flips the twist reference for the four corners
            // Giiker measures the other way round. The double modulo is
            // not redundant: Kotlin's `%` keeps the sign of the
            // dividend, so a negated twist would otherwise land on -1
            // or -2 and index out of the facelet triple. JS's `%`
            // behaves the same way but cstimer's `3 +` bias happens to
            // keep it positive for every value the cube actually sends;
            // we do not rely on that.
            val raw = nibbles[i + CORNER_ORI_OFFSET] * CORNER_ORI_MASK[i]
            cornerTwist[i] = ((CORNER_TWIST_BIAS + raw) % 3 + 3) % 3
        }

        val edgePerm = IntArray(EDGE_COUNT)
        for (i in 0 until EDGE_COUNT) {
            val perm = nibbles[EDGE_PERM_OFFSET + i] - 1
            if (perm !in 0 until EDGE_COUNT) return null
            edgePerm[i] = perm
        }

        // Twelve orientation bits over three nibbles, read MSB-first
        // within each nibble: edge 0 is bit 3 of nibble 28, edge 11 is
        // bit 0 of nibble 30.
        val edgeFlip = IntArray(EDGE_COUNT)
        var bit = 0
        for (i in 0 until EO_NIBBLES) {
            val nibble = nibbles[EO_OFFSET + i]
            for (mask in EO_BIT_MASKS) {
                edgeFlip[bit++] = if (nibble and mask != 0) 1 else 0
            }
        }

        val facelets = CharArray(FACELET_COUNT)

        for (c in 0 until CORNER_COUNT) {
            val j = cornerPerm[c]
            val ori = cornerTwist[c]
            for (n in 0 until 3) {
                facelets[CORNER_FACELETS[c][(n + ori) % 3]] =
                    KOCIEMBA_FACE_ORDER[CORNER_FACELETS[j][n] / FACELETS_PER_FACE]
            }
        }

        for (e in 0 until EDGE_COUNT) {
            val j = edgePerm[e]
            val ori = edgeFlip[e]
            for (n in 0 until 2) {
                facelets[EDGE_FACELETS[e][(n + ori) % 2]] =
                    KOCIEMBA_FACE_ORDER[EDGE_FACELETS[j][n] / FACELETS_PER_FACE]
            }
        }

        // Centres are fixed and never transmitted — the cube has no way
        // to know it has been rotated in the hand, so the state is
        // always expressed relative to its own centres.
        for (face in 0 until FACE_COUNT) {
            facelets[face * FACELETS_PER_FACE + CENTRE_INDEX] = KOCIEMBA_FACE_ORDER[face]
        }

        return CubeState.fromKociembaFacelets(facelets.concatToString())
    }

    // -- Moves ------------------------------------------------------------

    /**
     * Nibbles 32 onwards — the move history, as `(face, amount)` pairs,
     * **newest first**.
     *
     * Only the newest pair is ever emitted. The trailing entries are
     * history that was already emitted when the frames that produced
     * them arrived, and with no serial number there is nothing to
     * deduplicate them against; replaying them would double every turn
     * of a solve. The history is therefore useful only as a diagnostic,
     * not as the packet-loss recovery it superficially resembles — and
     * with an obfuscated cube there are only two entries anyway (see
     * [deobfuscate]).
     *
     * The face nibble is 1-based over Giiker's own [GIIKER_FACE_ORDER]
     * (`B D L U R F`), not [KOCIEMBA_FACE_ORDER].
     *
     * The amount nibble is folded with `% 7` before use. That is a
     * firmware workaround, not maths: some units add 7 to the amount,
     * emitting 8, 9 and 10 for the three turn kinds, and the fold maps
     * those back onto 1, 2 and 3. Values are validated first so the
     * fold never sees a zero — Kotlin's `%` would take `(0 - 1) % 7` to
     * -1 rather than 6.
     *
     * **Half turns become two events.** Giiker is the only family here
     * that reports a 180 as a single move, but [SmartCubeEvent.Move] is
     * a quarter turn by definition — it has a `cw` flag and no
     * magnitude — so a 180 is emitted as two clockwise quarter turns of
     * the same face. Two clockwise rather than one of each direction
     * because the composition must equal the physical turn, and the two
     * share a timestamp because the cube reports only one.
     */
    private fun decodeNewestMove(nibbles: IntArray, now: Long): List<SmartCubeEvent> {
        val faceNibble = nibbles[MOVE_OFFSET]
        val amountNibble = nibbles[MOVE_OFFSET + 1]

        val faceIndex = faceNibble - 1
        if (faceIndex !in GIIKER_FACE_ORDER.indices) return emptyList()
        val face = cubeFaceOf(GIIKER_FACE_ORDER[faceIndex]) ?: return emptyList()

        if (amountNibble < 1) return emptyList()
        val amount = (amountNibble - 1) % AMOUNT_FOLD

        // Giiker stamps no cube clock on a move — there is no uptime
        // counter anywhere in the protocol — so the receive time is the
        // only timestamp available and both fields carry it.
        fun move(cw: Boolean) = SmartCubeEvent.Move(
            face = face,
            cw = cw,
            cubeTimestamp = now,
            deviceTimestamp = now,
        )

        return when (amount) {
            AMOUNT_CW -> listOf(move(cw = true))
            AMOUNT_HALF -> listOf(move(cw = true), move(cw = true))
            AMOUNT_CCW -> listOf(move(cw = false))
            // 4, 5 and 6 are not turn kinds; the fold only ever
            // produces them from a corrupted nibble.
            else -> emptyList()
        }
    }

    private companion object {

        /** Announced name; the cube never tells us its own. */
        const val HARDWARE_NAME = "Giiker"

        /** Every Giiker notification is exactly this long. */
        const val PACKET_BYTES = 20

        /** Data bytes surviving deobfuscation; see [deobfuscate]. */
        const val OBFUSCATED_BODY_BYTES = 18

        /** `0xA7` here means "the first 18 bytes are scrambled". */
        const val OBFUSCATION_MARKER_OFFSET = 18
        val OBFUSCATION_MARKER: Byte = 0xA7.toByte()

        /** Two packed 4-bit windows into [OBFUSCATION_KEY]. */
        const val KEY_SELECTOR_OFFSET = 19

        /**
         * Giiker's fixed obfuscation table, identical in every unit.
         *
         * 36 entries because the largest index reachable is
         * `17 + 15 = 32`; the tail exists so both key windows can slide
         * without wrapping. Reproduced verbatim from cstimer's
         * `giikercube.js` and cross-checked against cubing.js — the
         * values have no structure and must not be "tidied".
         */
        val OBFUSCATION_KEY = intArrayOf(
            176, 81, 104, 224, 86, 137, 237, 119, 38, 26, 193, 161,
            210, 126, 150, 81, 93, 13, 236, 249, 89, 235, 88, 24,
            113, 81, 214, 131, 130, 199, 2, 169, 39, 165, 171, 41,
        )

        // -- Nibble layout ------------------------------------------------

        /** Nibbles 0..31 are the state; the move history follows. */
        const val STATE_NIBBLES = 32

        const val CORNER_COUNT = 8
        const val EDGE_COUNT = 12
        const val FACE_COUNT = 6
        const val FACELETS_PER_FACE = 9
        const val FACELET_COUNT = FACE_COUNT * FACELETS_PER_FACE
        const val CENTRE_INDEX = 4

        /** Corner orientation follows the eight permutation nibbles. */
        const val CORNER_ORI_OFFSET = 8

        /** Edge permutation: twelve nibbles, 16..27. */
        const val EDGE_PERM_OFFSET = 16

        /** Edge orientation: twelve bits packed into nibbles 28..30. */
        const val EO_OFFSET = 28
        const val EO_NIBBLES = 3

        /** MSB-first within each nibble. Order is load-bearing. */
        val EO_BIT_MASKS = intArrayOf(8, 4, 2, 1)

        /** Keeps the masked twist non-negative before the modulo. */
        const val CORNER_TWIST_BIAS = 3

        /** First move record: `(face, amount)`. */
        const val MOVE_OFFSET = 32
        const val MOVE_RECORD_NIBBLES = 2

        /** Firmware workaround: some units add 7 to the amount nibble. */
        const val AMOUNT_FOLD = 7

        const val AMOUNT_CW = 0
        const val AMOUNT_HALF = 1
        const val AMOUNT_CCW = 2

        /**
         * Giiker's face numbering as Kociemba face letters: the face
         * nibble is 1-based over `B D L U R F`. Not
         * [KOCIEMBA_FACE_ORDER] — indexing one with a value meant for
         * the other silently mirrors and rotates every turn.
         */
        const val GIIKER_FACE_ORDER = "BDLURF"

        /**
         * The three facelet indices of each Giiker corner cubie, in the
         * cube's own cubie order and its own orientation reference.
         *
         * Indices are standard Kociemba facelet positions (0..53, URFDLB
         * face-major), but the *cubie* order and the rotation origin
         * within each triple are Giiker's. Both the source slot and the
         * destination slot of the placement loop in [decodeFacelets]
         * read this same table, which is what makes the conversion
         * self-consistent without ever naming Giiker's cubie ids.
         */
        val CORNER_FACELETS = arrayOf(
            intArrayOf(26, 15, 29),
            intArrayOf(20, 8, 9),
            intArrayOf(18, 38, 6),
            intArrayOf(24, 27, 44),
            intArrayOf(51, 35, 17),
            intArrayOf(45, 11, 2),
            intArrayOf(47, 0, 36),
            intArrayOf(53, 42, 33),
        )

        /** The two facelet indices of each Giiker edge cubie. */
        val EDGE_FACELETS = arrayOf(
            intArrayOf(25, 28),
            intArrayOf(23, 12),
            intArrayOf(19, 7),
            intArrayOf(21, 41),
            intArrayOf(32, 16),
            intArrayOf(5, 10),
            intArrayOf(3, 37),
            intArrayOf(30, 43),
            intArrayOf(52, 34),
            intArrayOf(48, 14),
            intArrayOf(46, 1),
            intArrayOf(50, 39),
        )

        /**
         * Per-corner twist sign. Giiker measures four of its corners
         * against the opposite reference from the other four, so the
         * raw orientation nibble has to be negated for those before it
         * means anything. There is no principle behind which four —
         * it falls out of the cubie numbering in [CORNER_FACELETS].
         */
        val CORNER_ORI_MASK = intArrayOf(-1, 1, -1, 1, 1, -1, 1, -1)
    }
}
