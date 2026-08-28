package com.zucham.qbsmarter.domain.driver.gocube

import com.zakgof.korender.math.Vec3
import com.zucham.qbsmarter.domain.cube.CubeState
import com.zucham.qbsmarter.domain.cube.fromKociembaFacelets
import com.zucham.qbsmarter.domain.driver.CubeVendor
import com.zucham.qbsmarter.domain.driver.SmartCubeCommand
import com.zucham.qbsmarter.domain.driver.SmartCubeEvent
import com.zucham.qbsmarter.domain.driver.protocol.CubeIdentity
import com.zucham.qbsmarter.domain.driver.protocol.CubeProtocol
import com.zucham.qbsmarter.domain.driver.protocol.KOCIEMBA_FACE_ORDER
import com.zucham.qbsmarter.domain.driver.protocol.ProtocolIo
import com.zucham.qbsmarter.domain.driver.protocol.cubeFaceOf
import com.zucham.qbsmarter.domain.driver.protocol.unitQuaternion
import com.zucham.qbsmarter.util.currentTimeMillis

/**
 * GoCube / Rubik's Connected wire protocol.
 *
 * **One protocol, two brands.** Rubik's Connected is a GoCube in a
 * different shell speaking the identical protocol over the identical
 * (GoCube-flavoured) Nordic UART service, so the vendor is *injected*
 * rather than hard-coded: the registry builds this class twice, once
 * with [CubeVendor.GOCUBE] and once with [CubeVendor.RUBIKS], and the
 * only thing the choice affects is the label stamped on
 * [SmartCubeEvent.Hardware].
 *
 * **Nothing is encrypted.** No key, no salt, no MAC-derived IV — the
 * cube speaks plaintext ASCII-ish frames both ways, which is why the
 * registry gives this spec no `createEncryptor` and the driver passes
 * bytes through untouched in both directions.
 *
 * **The two directions do not share a framing.** Cube → app is a
 * checksummed `'*' | len | type | payload | sum | CRLF` frame; app →
 * cube is a *single raw byte* with no framing at all (see
 * [buildCommand]). Symmetry would be nice; the firmware disagrees.
 *
 * **The orientation stream is off until asked for.** A GoCube says
 * nothing about its gyro until it receives `0x38`, which is the entire
 * reason cstimer's implementation never observes a type-3 message and
 * concludes the cube has no orientation reporting. [onConnected] opts
 * in, but only for hardware that actually has the sensor.
 *
 * **There is no missed-move detection of any kind.** GoCube attaches no
 * serial number, sequence counter or cube clock to a move, so a dropped
 * BLE notification is simply gone and cannot even be *noticed*, let
 * alone retransmitted (hence the null for
 * [SmartCubeCommand.RequestMoveHistory]). The only defence is the
 * periodic state resync in [decode] — see [movesSinceResync].
 *
 * Stateful: it counts moves since the last resync and remembers whether
 * it has announced the hardware. Per-connection instance, so neither
 * needs resetting.
 */
internal class GoCubeProtocol(
    override val vendor: CubeVendor,
    private val identity: CubeIdentity,
) : CubeProtocol {

    override val id: String = "gocube"

    /**
     * Whether this particular unit has an orientation sensor, decided
     * from the advertised name because nothing on the wire says so.
     *
     * poliva's rule, reproduced exactly: only a device whose name
     * starts with `GoCube` **and not** `GoCubeX` has a gyro. The
     * GoCube X and Rubik's Connected share the same UART service and
     * the same message types but carry no sensor, and asking them to
     * enable one is harmless but pointless.
     *
     * A null name means an unnamed scan result, which we treat as
     * "no gyro" — the conservative direction, since the cost of a
     * wrong true is an orientation stream that never arrives.
     */
    private val supportsGyro: Boolean = identity.name
        ?.let { it.startsWith("GoCube", ignoreCase = true) && !it.startsWith("GoCubeX", ignoreCase = true) }
        ?: false

    /**
     * Moves seen since the last full-state request.
     *
     * GoCube cannot tell us it dropped a move, so instead of detecting
     * loss we periodically stop trusting our accumulated state and ask
     * the cube what it actually is (`0x33`). Every 21st move is
     * cstimer's cadence and is a reasonable trade: frequent enough that
     * a lost turn is corrected within a few seconds of solving, rare
     * enough not to interleave a write into every notification.
     *
     * Initialised to 100 — deliberately past the threshold — so the
     * very first move of a connection triggers an anchor against the
     * cube's real state rather than inheriting whatever the app happened
     * to believe. cstimer does the same, but its resync is a no-op in
     * practice: a cubie-buffer swap bug means the refreshed state is
     * written into a buffer that is immediately discarded. Ours actually
     * emits the resulting [SmartCubeEvent.Facelets].
     */
    private var movesSinceResync: Int = RESYNC_PRIME

    /** Whether [SmartCubeEvent.Hardware] has already been announced. */
    private var hardwareAnnounced: Boolean = false

    /**
     * Ask for the current state, opt in to orientation, and ask what
     * kind of cube this is.
     *
     * Order matters only for the first: the state request goes out
     * before anything else so that the app has a real facelet snapshot
     * as early as possible, rather than animating the user's first turn
     * against a stale assumption.
     *
     * The `0x38` enable is skipped on hardware without a sensor. This is
     * the step every implementation that "has no GoCube gyro support"
     * is missing — the stream is off by default and the cube will stay
     * silent about orientation forever if nobody asks.
     */
    override suspend fun onConnected(io: ProtocolIo) {
        io.writePlain(byteArrayOf(CMD_GET_STATE))
        if (supportsGyro) io.writePlain(byteArrayOf(CMD_ENABLE_ORIENTATION))
        io.writePlain(byteArrayOf(CMD_GET_CUBE_TYPE))
    }

    /**
     * GoCube commands are single raw bytes: no `'*'` prefix, no length,
     * no checksum, no CRLF. The cube's own frames are checksummed but
     * it accepts (and expects) bare opcodes in return.
     *
     * [SmartCubeCommand.RequestHardware] maps to the cube-type query,
     * which is the closest thing the protocol has to a hardware-info
     * opcode — it answers "edge cube or not" and nothing else, so the
     * [SmartCubeEvent.Hardware] this protocol emits is synthesised in
     * [decode] rather than parsed from a reply.
     *
     * [SmartCubeCommand.RequestMoveHistory] maps to null because there
     * is nothing to request: GoCube stamps no serial number on a move,
     * so there is no gap to detect, no window to name, and no
     * retransmit path in the firmware. Recovery is the periodic
     * full-state resync instead.
     */
    override fun buildCommand(cmd: SmartCubeCommand): ByteArray? = when (cmd) {
        SmartCubeCommand.RequestFacelets -> byteArrayOf(CMD_GET_STATE)
        SmartCubeCommand.RequestBattery -> byteArrayOf(CMD_GET_BATTERY)
        SmartCubeCommand.RequestReset -> byteArrayOf(CMD_SET_SOLVED_STATE)
        SmartCubeCommand.RequestHardware -> byteArrayOf(CMD_GET_CUBE_TYPE)
        is SmartCubeCommand.RequestMoveHistory -> null
    }

    /**
     * Validate the frame, then dispatch on its message type.
     *
     * The declared length locates the payload and the checksum, but is
     * never trusted on its own: a corrupt length byte would otherwise
     * index straight past the array. Every read here is bounded by
     * `packet.size` as well.
     *
     * Unlike cstimer — which skips checksum verification entirely — a
     * mismatch drops the frame. Plaintext over BLE is not error-free,
     * and a single flipped bit in a state frame is the difference
     * between a resync and a spurious "impossible cube".
     */
    override suspend fun decode(packet: ByteArray, io: ProtocolIo): List<SmartCubeEvent> {
        val now = currentTimeMillis()

        // poliva's floor: prefix + len + type + at least one payload
        // byte + checksum + CRLF. Anything shorter cannot be a frame.
        if (packet.size < MIN_PACKET_SIZE) return emptyList()
        if (packet[0] != FRAME_PREFIX) return emptyList()
        if (packet[packet.size - 2] != ASCII_CR || packet[packet.size - 1] != ASCII_LF) return emptyList()

        // The length byte excludes the CRLF suffix, so the frame really
        // occupies `declaredLen + 2` bytes and the trailer must sit
        // exactly where the length says it does.
        val declaredLen = packet[1].toInt() and 0xFF
        if (declaredLen < MIN_FRAME_LEN || declaredLen + CRLF_BYTES > packet.size) return emptyList()
        if (packet[declaredLen] != ASCII_CR || packet[declaredLen + 1] != ASCII_LF) return emptyList()
        if (!checksumOk(packet, declaredLen)) return emptyList()

        val type = packet[2].toInt() and 0xFF
        // Payload spans [3, payloadEnd); the byte at payloadEnd is the
        // checksum. Empty for a frame that carries nothing but a type.
        val payloadEnd = declaredLen - 1

        val body = when (type) {
            MSG_MOVE -> decodeMoves(packet, payloadEnd, now, io)
            MSG_STATE -> decodeState(packet, payloadEnd, now)
            MSG_ORIENTATION -> decodeOrientation(packet, payloadEnd, now)
            MSG_BATTERY -> decodeBattery(packet, payloadEnd, now)
            // Type 7 — offline solve statistics, documented as ASCII
            // "moves#time#solves". The field units are undocumented
            // (ticks? centiseconds? since power-on? since last reset?)
            // and no capture we have disambiguates them, so we decline
            // to guess rather than feed the app plausible nonsense.
            // Left unparsed on purpose; revisit with real hardware.
            MSG_OFFLINE_STATS -> emptyList()
            // Type 8 — cube type: one payload byte, 0x00 = non-edge
            // cube, 0x01 = edge cube. Nothing downstream distinguishes
            // the two (the facelet and move encodings are identical),
            // so there is no event to raise; it is requested in
            // [onConnected] mostly as a liveness probe.
            MSG_CUBE_TYPE -> emptyList()
            else -> return emptyList()
        }

        // The cube never volunteers a name, firmware version or
        // capability bitmap, so synthesise the hardware announcement
        // from what we do know the moment a frame decodes cleanly —
        // proof enough that we are talking to a real GoCube.
        if (!hardwareAnnounced) {
            hardwareAnnounced = true
            return listOf(
                SmartCubeEvent.Hardware(
                    deviceTimestamp = now,
                    name = identity.name ?: vendor.name,
                    hwVersion = "",
                    swVersion = "",
                    // null, not true, when we expect a gyro: the name
                    // heuristic is a guess, and the app upgrades this
                    // to true on the first real orientation packet —
                    // the only trustworthy evidence there is. false is
                    // reserved for hardware we know has no sensor,
                    // where a null would leave the controls hidden
                    // behind a promise that never arrives.
                    gyroSupported = if (supportsGyro) null else false,
                    vendor = vendor,
                ),
            ) + body
        }
        return body
    }

    /**
     * Checksum: the low byte of the sum of every byte from the prefix
     * up to (but not including) the checksum itself. The CRLF suffix is
     * outside the sum, as it is outside the declared length.
     */
    private fun checksumOk(packet: ByteArray, declaredLen: Int): Boolean {
        var sum = 0
        for (i in 0 until declaredLen - 1) sum += packet[i].toInt() and 0xFF
        return (sum and 0xFF) == (packet[declaredLen - 1].toInt() and 0xFF)
    }

    // -- Moves ------------------------------------------------------------

    /**
     * Type 1 — one or more quarter turns, as 2-byte records.
     *
     * A record is `code | angle`. The **angle byte is deliberately
     * unused**: it reports how far the face was physically rotated, but
     * GoCube only ever reports completed quarter turns, so the angle
     * adds nothing the code has not already said. Reading it as part of
     * the move would invent half-turns that the cube never claimed.
     *
     * The code packs face and direction: `code shr 1` is a face index in
     * GoCube's own B F U D R L order (mapped through
     * [GOCUBE_TO_KOCIEMBA]), and the low bit is the direction —
     * **0 is clockwise**, 1 is counter-clockwise.
     *
     * Several turns can share one notification when the user is fast,
     * so this emits one [SmartCubeEvent.Move] per record rather than
     * assuming a single move per frame.
     */
    private suspend fun decodeMoves(
        packet: ByteArray,
        payloadEnd: Int,
        now: Long,
        io: ProtocolIo,
    ): List<SmartCubeEvent> {
        val events = mutableListOf<SmartCubeEvent>()
        var offset = PAYLOAD_START
        // `offset + MOVE_RECORD_BYTES <= payloadEnd` keeps the angle
        // byte inside the payload too; a trailing odd byte is truncation
        // and is dropped rather than half-read.
        while (offset + MOVE_RECORD_BYTES <= payloadEnd && offset + MOVE_RECORD_BYTES <= packet.size) {
            val code = packet[offset].toInt() and 0xFF
            offset += MOVE_RECORD_BYTES

            val axis = code shr 1
            if (axis >= GOCUBE_TO_KOCIEMBA.size) continue
            val face = cubeFaceOf(KOCIEMBA_FACE_ORDER[GOCUBE_TO_KOCIEMBA[axis]]) ?: continue

            events += SmartCubeEvent.Move(
                face = face,
                cw = (code and 1) == 0,
                // GoCube stamps no cube clock on a move — there is no
                // uptime counter anywhere in the protocol — so the
                // receive time is the only timestamp available and
                // both fields carry it.
                cubeTimestamp = now,
                deviceTimestamp = now,
            )

            // Re-anchor periodically. Done per move rather than per
            // frame so that a burst of turns inside one notification
            // still counts toward the interval.
            movesSinceResync++
            if (movesSinceResync > RESYNC_INTERVAL) {
                movesSinceResync = 0
                io.writePlain(byteArrayOf(CMD_GET_STATE))
            }
        }
        return events
    }

    // -- State ------------------------------------------------------------

    /**
     * Type 2 — a full 54-facelet snapshot, as six 9-byte groups.
     *
     * The layout is centre-first and *rotationally* encoded, which is
     * what makes it awkward: within a group, byte 0 is the centre and
     * bytes 1..8 walk the ring of surrounding stickers clockwise — but
     * each face starts its walk at a different corner
     * ([RING_START_OFFSET]), and the groups themselves arrive in
     * GoCube's B F U D R L order rather than Kociemba's U R F D L B.
     *
     * So three permutations compose here: [GOCUBE_TO_KOCIEMBA] picks
     * the output face, [RING_CLOCKWISE] converts a ring position into a
     * facelet index within that face, and [RING_START_OFFSET] rotates
     * the ring to a common origin. Getting any one of them wrong still
     * produces 54 valid letters, so the failure shows up only as "the
     * cube is permanently in an impossible state".
     *
     * A null from [CubeState.fromKociembaFacelets] — an unreachable
     * permutation, or a colour byte out of range — skips the event
     * instead of failing the notification. The next snapshot is one
     * request away, and dropping one is cheaper than tearing down the
     * stream.
     */
    private fun decodeState(packet: ByteArray, payloadEnd: Int, now: Long): List<SmartCubeEvent> {
        if (PAYLOAD_START + STATE_BYTES > payloadEnd) return emptyList()
        if (PAYLOAD_START + STATE_BYTES > packet.size) return emptyList()

        val facelets = CharArray(STATE_BYTES)
        for (group in 0 until FACE_COUNT) {
            val groupStart = PAYLOAD_START + group * FACELETS_PER_FACE
            val base = GOCUBE_TO_KOCIEMBA[group] * FACELETS_PER_FACE

            val centre = colourLetter(packet[groupStart]) ?: return emptyList()
            facelets[base + CENTRE_INDEX] = centre

            for (i in 0 until RING_LENGTH) {
                val letter = colourLetter(packet[groupStart + 1 + i]) ?: return emptyList()
                val ringSlot = (i + RING_START_OFFSET[group]) % RING_LENGTH
                facelets[base + RING_CLOCKWISE[ringSlot]] = letter
            }
        }

        val state = CubeState.fromKociembaFacelets(facelets.concatToString()) ?: return emptyList()
        return listOf(SmartCubeEvent.Facelets(state = state, deviceTimestamp = now))
    }

    /**
     * Colour byte to Kociemba face letter, or null for a code outside
     * the six the cube is supposed to send.
     *
     * The colour numbering is GoCube's own face order ([GOCUBE_COLOURS],
     * B F U D R L), *not* [KOCIEMBA_FACE_ORDER] — indexing one with a
     * value meant for the other is a silent, plausible-looking mistake.
     */
    private fun colourLetter(value: Byte): Char? {
        val index = value.toInt() and 0xFF
        return if (index < GOCUBE_COLOURS.length) GOCUBE_COLOURS[index] else null
    }

    // -- Orientation ------------------------------------------------------

    /**
     * Type 3 — the quaternion, and the one message in this protocol
     * whose payload is **ASCII text rather than binary**: decimal
     * integers in the form `x#y#z#w`, `'#'`-separated, each optionally
     * signed. Parsing it as int16s produces a stream of garbage
     * orientations that look almost plausible, which is an expensive way
     * to discover the format.
     *
     * The components are handed to [unitQuaternion] raw. The documented
     * scale is 2^14 = 16384, but real firmware emits vectors of
     * magnitude ≈16355; normalising by the measured length is therefore
     * both exact and immune to a firmware revision changing the
     * constant, so we never divide by the nominal value.
     *
     * The axis remap is `(x, -z, -y)`, following poliva's corrected
     * mapping. **This convention is contested**: cubing.js uses a
     * different one, and the implementations disagree about which
     * handedness the cube reports. If a real device shows the model
     * mirrored or rotated about the wrong axis, this is the line to
     * flip — nothing else in the decode path is sensitive to it.
     *
     * GoCube reports no angular velocity anywhere, so [Vec3.ZERO] is the
     * honest answer rather than a placeholder awaiting a field.
     */
    private fun decodeOrientation(packet: ByteArray, payloadEnd: Int, now: Long): List<SmartCubeEvent> {
        if (payloadEnd <= PAYLOAD_START) return emptyList()
        val end = if (payloadEnd < packet.size) payloadEnd else packet.size

        val text = packet.decodeToString(PAYLOAD_START, end, throwOnInvalidSequence = false)
        val parts = text.split(ORIENTATION_SEPARATOR)
        if (parts.size != ORIENTATION_FIELDS) return emptyList()

        val x = parts[0].trim().toIntOrNull() ?: return emptyList()
        val y = parts[1].trim().toIntOrNull() ?: return emptyList()
        val z = parts[2].trim().toIntOrNull() ?: return emptyList()
        val w = parts[3].trim().toIntOrNull() ?: return emptyList()

        return listOf(
            SmartCubeEvent.Gyro(
                quat = unitQuaternion(
                    w = w.toFloat(),
                    x = x.toFloat(),
                    y = -z.toFloat(),
                    z = -y.toFloat(),
                ),
                angularVel = Vec3.ZERO,
                deviceTimestamp = now,
            ),
        )
    }

    // -- Battery ----------------------------------------------------------

    /** Type 5 — battery percentage in a single payload byte. */
    private fun decodeBattery(packet: ByteArray, payloadEnd: Int, now: Long): List<SmartCubeEvent> {
        if (PAYLOAD_START >= payloadEnd || PAYLOAD_START >= packet.size) return emptyList()
        val level = packet[PAYLOAD_START].toInt() and 0xFF
        return listOf(SmartCubeEvent.Battery(deviceTimestamp = now, level = level))
    }

    private companion object {

        /** `'*'`, the first byte of every frame the cube sends. */
        val FRAME_PREFIX: Byte = 0x2A

        val ASCII_CR: Byte = 0x0D
        val ASCII_LF: Byte = 0x0A

        /** The CRLF suffix sits outside the declared length. */
        const val CRLF_BYTES = 2

        /**
         * poliva's floor for a plausible frame: prefix, length, type,
         * one payload byte, checksum, CRLF.
         */
        const val MIN_PACKET_SIZE = 7

        /** Prefix + length + type + checksum, with an empty payload. */
        const val MIN_FRAME_LEN = 4

        /** Payload always begins immediately after the type byte. */
        const val PAYLOAD_START = 3

        const val MSG_MOVE = 1
        const val MSG_STATE = 2
        const val MSG_ORIENTATION = 3
        const val MSG_BATTERY = 5
        const val MSG_OFFLINE_STATS = 7
        const val MSG_CUBE_TYPE = 8

        // Commands, as bare bytes. 0x34 (reboot), 0x37 (disable
        // orientation), 0x39 (offline stats) and 0x57 (calibrate
        // orientation) exist too but have no [SmartCubeCommand] to map
        // from; they are listed here so the opcode space is documented
        // in one place rather than rediscovered.
        const val CMD_GET_BATTERY: Byte = 0x32
        const val CMD_GET_STATE: Byte = 0x33
        const val CMD_SET_SOLVED_STATE: Byte = 0x35
        const val CMD_ENABLE_ORIENTATION: Byte = 0x38
        const val CMD_GET_CUBE_TYPE: Byte = 0x56

        /** `code | angle`; the angle byte is intentionally ignored. */
        const val MOVE_RECORD_BYTES = 2

        const val FACE_COUNT = 6
        const val FACELETS_PER_FACE = 9
        const val STATE_BYTES = FACE_COUNT * FACELETS_PER_FACE
        const val RING_LENGTH = 8
        const val CENTRE_INDEX = 4

        /**
         * GoCube's native face order is B F U D R L; this maps a
         * GoCube face index to its index in [KOCIEMBA_FACE_ORDER]
         * (U R F D L B). Used twice — by the move decoder for
         * `code shr 1`, and by the state decoder to place a 9-byte
         * group — because both speak the same native order.
         */
        val GOCUBE_TO_KOCIEMBA = intArrayOf(5, 2, 0, 3, 1, 4)

        /**
         * Ring position to facelet index within a face: the eight
         * non-centre stickers walked clockwise from the top-left
         * corner. Position 4 (the centre) is absent by construction.
         */
        val RING_CLOCKWISE = intArrayOf(0, 1, 2, 5, 8, 7, 6, 3)

        /**
         * Where each face starts its clockwise ring walk, per GoCube
         * face index. Non-zero for F and D only, and there is no
         * principle behind the values — they are whatever the firmware
         * happens to do, and are reproduced verbatim from the spec.
         */
        val RING_START_OFFSET = intArrayOf(0, 0, 6, 2, 0, 0)

        /**
         * Sticker colour codes as Kociemba face letters, in GoCube's
         * own B F U D R L order. Not [KOCIEMBA_FACE_ORDER].
         */
        const val GOCUBE_COLOURS = "BFUDRL"

        const val ORIENTATION_SEPARATOR = '#'
        const val ORIENTATION_FIELDS = 4

        /** Resync after this many moves; see [movesSinceResync]. */
        const val RESYNC_INTERVAL = 20

        /** Start past the threshold so the first move re-anchors. */
        const val RESYNC_PRIME = 100
    }
}
