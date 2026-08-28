package com.zucham.qbsmarter.domain.driver.moyu

import com.zakgof.korender.math.Vec3
import com.zucham.qbsmarter.domain.cube.CubeFace
import com.zucham.qbsmarter.domain.driver.BitView
import com.zucham.qbsmarter.domain.driver.CubeVendor
import com.zucham.qbsmarter.domain.driver.SmartCubeCommand
import com.zucham.qbsmarter.domain.driver.SmartCubeEvent
import com.zucham.qbsmarter.domain.driver.protocol.CubeProtocol
import com.zucham.qbsmarter.domain.driver.protocol.ProtocolIo
import com.zucham.qbsmarter.domain.driver.protocol.leInt32
import com.zucham.qbsmarter.domain.driver.protocol.unitQuaternion
import com.zucham.qbsmarter.util.currentTimeMillis

/**
 * MoYu WCU wire protocol — the WeiLong V10 / V11 AI family, advertised
 * as `WCU_MY3x` on service `0783b03e-…cb0`.
 *
 * One of two unrelated protocols MoYu ships under one brand; the other
 * is the older, plaintext [MoyuMhcProtocol]. Everything here is
 * AES-CBC encrypted with a MAC-salted key and IV (see
 * [moyuWcuEncryptorFor]), in 20-byte packets both directions, with the
 * message type in byte 0:
 *
 *   | Hex   | Meaning                                              |
 *   |-------|------------------------------------------------------|
 *   | 0xA1  | Cube Info – model name, HW/SW versions, gyro flags   |
 *   | 0xA3  | Cube Status (Facelets) – 48 sticker colours × 3 bits |
 *   | 0xA4  | Cube Power – battery 0..100                          |
 *   | 0xA5  | Cube Move – last 5 moves with timestamps + serial    |
 *   | 0xAB  | Gyroscope – packed quaternion                        |
 *   | 0xAC  | Gyro on/off acknowledgement                          |
 *
 * **Recovery model.** The move event reports the last 5 moves with a
 * single rolling 8-bit serial counter. Same pattern as GAN Gen2's
 * 7-move buffer; no targeted move-history retransmit is documented in
 * the protocol. So we mirror Gen2 exactly: on a serial jump greater
 * than [MOVE_HISTORY_SIZE] we emit [SmartCubeEvent.MovesMissed], and
 * the connection orchestrator's existing debounced Facelets-resync
 * path handles recovery.
 *
 * **Init quirk.** Per the protocol writeup: "Immediately after
 * connecting to the cube, you need to write a Cube Info (0xA1)
 * message to initialise correctly the cube." [onConnected] does that
 * and rather more besides — see there for the doubled request burst
 * some V10 variants need before they will stream anything at all.
 *
 * **Gyro state.** The cube ships with gyro enabled by default, but the
 * setting is persistent across reconnects, so a previous client
 * session may have left it off. [onConnected] therefore sends the
 * `0xAC` enable unconditionally to force a known state.
 *
 * **Reset.** The protocol writeup documents no reset opcode. The
 * [SmartCubeCommand.RequestReset] case in [buildCommand] returns null
 * and the orchestrator's reset path falls back to whatever
 * higher-level "reset visual state" the app has – which is exactly
 * what we want, since you can't software-reset a cube to "solved"
 * anyway (the cube reports the physical state; the app's notion of
 * "solved" is an internal reset of CubeState).
 *
 * Stateful: it tracks the rolling move serial, the wall-clock of the
 * last emitted move and the accumulated cube clock. Per-connection
 * instance, so none of that needs resetting.
 */
internal class MoyuWcuProtocol : CubeProtocol {

    override val vendor: CubeVendor = CubeVendor.MOYU

    override val id: String = "moyu-wcu"

    /** Last move serial we successfully processed. -1 before the first
     *  Facelets snapshot establishes the baseline. Same anchor rule as
     *  GAN Gen2: move events ignored until the first Facelets event
     *  arrives, because before that lastSerial is undefined. */
    private var lastSerial: Int = -1

    /** Wall-clock of the most-recent move we emitted. Used to backfill
     *  a `0`-elapsed cube timestamp (the cube reports per-move
     *  millisecond deltas; the very first move after connect doesn't
     *  have a prior to delta against). */
    private var lastMoveTimestamp: Long = 0

    /** Accumulated cube-clock timestamp (monotonic, in ms). Sums per-
     *  move `elapsed` values from the MOVE packet. Drives the
     *  [SmartCubeEvent.Move.cubeTimestamp] field, which the timer
     *  uses for its drift-free duration calculation. */
    private var cubeTimestamp: Long = 0

    /**
     * Post-connect handshake: the documented init sequence, then the
     * gyro enable, then one more state request.
     *
     * The three requests (0xA1 hardware, 0xA3 facelets, 0xA4 battery)
     * go out **twice** before the gyro enable. That doubled burst is a
     * documented workaround from poliva/smartcube-web-bluetooth: some
     * cheap V10 variants ignore the first round entirely and never
     * start streaming at all unless the sequence is repeated. On
     * hardware that does not need it the extra round is harmless — the
     * cube simply answers each request twice, and every consumer of
     * [SmartCubeEvent.Hardware], [SmartCubeEvent.Facelets] and
     * [SmartCubeEvent.Battery] already treats them as upserts.
     *
     * The requests are issued through [ProtocolIo.send] so they reuse
     * the very same 20-byte encodings [buildCommand] produces; there is
     * no second copy of the wire format here to drift out of sync.
     */
    override suspend fun onConnected(io: ProtocolIo) {
        io.send(SmartCubeCommand.RequestHardware)   // 0xA1 = 161
        io.send(SmartCubeCommand.RequestFacelets)   // 0xA3 = 163
        io.send(SmartCubeCommand.RequestBattery)    // 0xA4 = 164
        // Documented workaround — see the KDoc above. Not a copy-paste
        // slip: variants exist that otherwise never begin notifying.
        io.send(SmartCubeCommand.RequestHardware)
        io.send(SmartCubeCommand.RequestFacelets)
        io.send(SmartCubeCommand.RequestBattery)
        // 0xAC = 172 gyro config: byte 2 is the on/off flag. The cube
        // acknowledges with its own 0xAC, which [decode] ignores — we
        // only needed to put the cube in a known gyro-on state.
        io.writePlain(
            ByteArray(20).apply {
                this[0] = MSG_GYRO_CONFIG.toByte()
                this[2] = 0x01  // 1 = enable; 0 = disable
            },
        )
        // Re-ask for state last: enabling the gyro is the point at
        // which a sulking variant finally starts answering.
        io.send(SmartCubeCommand.RequestFacelets)
    }

    // ---------------------------------------------------------------
    // Wire encoding / decoding
    // ---------------------------------------------------------------

    override fun buildCommand(cmd: SmartCubeCommand): ByteArray? = when (cmd) {
        SmartCubeCommand.RequestFacelets -> ByteArray(20).apply { this[0] = MSG_CUBE_STATUS.toByte() }
        SmartCubeCommand.RequestHardware -> ByteArray(20).apply { this[0] = MSG_CUBE_INFO.toByte() }
        SmartCubeCommand.RequestBattery -> ByteArray(20).apply { this[0] = MSG_CUBE_POWER.toByte() }
        // MoYu V10's protocol writeup documents no reset opcode. The
        // orchestrator's reset path is a no-op on the cube; what's
        // user-facing is the app-side state reset, which the caller
        // handles independently. Returning null skips the GATT write
        // cleanly (same pattern Gen2 uses for RequestMoveHistory).
        SmartCubeCommand.RequestReset -> null
        // No targeted move-history retransmit. The orchestrator's
        // MovesMissed → RequestFacelets path is the equivalent recovery.
        is SmartCubeCommand.RequestMoveHistory -> null
    }

    override suspend fun decode(packet: ByteArray, io: ProtocolIo): List<SmartCubeEvent> {
        if (packet.isEmpty()) return emptyList()
        val ts = currentTimeMillis()
        return when (packet[0].toInt() and 0xFF) {
            MSG_CUBE_INFO -> parseCubeInfo(packet, ts)
            MSG_CUBE_STATUS -> parseFacelets(packet, ts)
            MSG_CUBE_POWER -> parseBattery(packet, ts)
            MSG_CUBE_MOVE -> parseMove(packet, ts)
            MSG_GYROSCOPE -> parseGyro(packet, ts)
            MSG_GYRO_CONFIG -> emptyList()  // ack-only; ignore
            // Unknown packet type. The driver already logs an empty
            // decode result, which is enough to diagnose a new variant.
            else -> emptyList()
        }
    }

    // -- 0xA1 Cube Info ----------------------------------------------

    private fun parseCubeInfo(message: ByteArray, ts: Long): List<SmartCubeEvent> {
        // Bits 8..71 = 8 ASCII bytes of model name (e.g. "WCU_MY32").
        // Bits 72..103 = HW major/minor + SW major/minor (4 × u8).
        // Bit 105 = gyro enabled; bit 106 = gyro functional.
        // Bits 109..116 = move counter / serial.
        val nameBytes = ByteArray(8) { i -> message[1 + i] }
        // Strip trailing zeros / non-printable bytes.
        val name = nameBytes
            .takeWhile { it != 0.toByte() }
            .joinToString("") { (it.toInt() and 0xFF).toChar().toString() }
            .ifEmpty { "MoYu" }

        val hwMajor = message[9].toInt() and 0xFF
        val hwMinor = message[10].toInt() and 0xFF
        val swMajor = message[11].toInt() and 0xFF
        val swMinor = message[12].toInt() and 0xFF

        // Flag bits are packed at bit offset 104. Bit 105 = enabled,
        // bit 106 = functional. We surface gyro support as
        // `enabled && functional` – matches GAN's "gyroSupported"
        // semantics (the rest of the app reads this as "can the cube
        // emit useful gyro events").
        //
        // This is a real capability read off the wire, not a guess, so
        // it stays a hard true/false rather than the null that GAN Gen4
        // and GoCube report from their name heuristics. Note that a
        // "Lite"/cheap V10 variant with no gyro hardware answers with
        // `functional = 0` here, so a false from this cube means what
        // it says; the app still upgrades to true on observing a real
        // 0xAB packet, so a firmware that lies low costs nothing.
        val v = BitView(message)
        val gyroEnabled = v.word(105, 1) != 0L
        val gyroFunctional = v.word(106, 1) != 0L
        val gyroSupported = gyroEnabled && gyroFunctional

        // NOTE: the Cube Info packet also carries a move-counter at
        // bits 109..116. We deliberately do NOT seed `lastSerial` from
        // it. Same rule as GAN Gen2/Gen3: until a Facelets packet has
        // arrived, we don't know the cube's physical state, so applying
        // moves on top of our default SOLVED `CubeState` would drift
        // visualisation away from reality. Move events arriving before
        // Facelets are intentionally dropped; the orchestrator's
        // post-connect handshake sends RequestHardware then
        // RequestFacelets back-to-back with a small inter-command gap,
        // so the anchor lands well within the typical user-attention
        // window.

        return listOf(
            SmartCubeEvent.Hardware(
                deviceTimestamp = ts,
                name = name,
                hwVersion = "$hwMajor.$hwMinor",
                swVersion = "$swMajor.$swMinor",
                gyroSupported = gyroSupported,
                vendor = CubeVendor.MOYU,
            ),
        )
    }

    // -- 0xA3 Cube Status / Facelets --------------------------------

    private fun parseFacelets(message: ByteArray, ts: Long): List<SmartCubeEvent> {
        // 48 stickers × 3 bits, starting at bit 8 (immediately after
        // the message type byte). Trailing u8 at bits 152..159 is the
        // serial counter that anchors our move-buffer diff.
        val v = BitView(message)
        val stickers = IntArray(48) { i ->
            v.word(8 + i * 3, 3).toInt()
        }
        // A packet that doesn't decode to a valid CubeState is dropped:
        // resyncing the visualisation to garbage is worse than missing
        // the resync entirely.
        val state = MoyuFaceletDecoder.decode(stickers) ?: return emptyList()
        // Anchor lastSerial on every Facelets event (same lesson as
        // GAN Gen2: the cube has encoded all moves up to this serial
        // into the snapshot, so subsequent Move events must diff
        // against THIS point – not the older lastSerial we had before
        // requesting the resync).
        val newSerial = v.word(152, 8).toInt() and 0xFF
        lastSerial = newSerial
        return listOf(SmartCubeEvent.Facelets(state = state, deviceTimestamp = ts))
    }

    // -- 0xA4 Cube Power --------------------------------------------

    private fun parseBattery(message: ByteArray, ts: Long): List<SmartCubeEvent> {
        val level = (message[1].toInt() and 0xFF).coerceAtMost(100)
        return listOf(SmartCubeEvent.Battery(deviceTimestamp = ts, level = level))
    }

    // -- 0xA5 Cube Move ---------------------------------------------

    private fun parseMove(message: ByteArray, ts: Long): List<SmartCubeEvent> {
        // Bits 8..87  = 5 × u16 (elapsed ms per move), big-endian
        //                (the writeup's default unless stated otherwise).
        // Bits 88..95 = u8 serial counter (newest move's serial).
        // Bits 96..120 = 5 × 5-bit move codes (most-recent first;
        //                 each code is 0..11 per [MOVE_CODE_TABLE]).
        if (lastSerial == -1) {
            // No anchor yet – Facelets / Info hasn't arrived. Drop the
            // moves; the next Facelets resync (which the orchestrator
            // requests as part of the connect handshake) will re-anchor.
            return emptyList()
        }

        val v = BitView(message)
        val serial = v.word(88, 8).toInt() and 0xFF
        val rawDiff = (serial - lastSerial) and 0xFF
        val diff = minOf(rawDiff, MOVE_HISTORY_SIZE)
        val missed = (rawDiff - MOVE_HISTORY_SIZE).coerceAtLeast(0)
        lastSerial = serial

        val events = mutableListOf<SmartCubeEvent>()
        if (diff > 0) {
            // The 5 move codes are packed newest-first; we want to emit
            // oldest-to-newest so the consumer sees them in causal
            // order. Iterate `diff - 1` down to 0 (mirroring Gen2's
            // emission loop).
            for (i in diff - 1 downTo 0) {
                val code = v.word(96 + 5 * i, 5).toInt() and 0x1F
                val mapping = MOVE_CODE_TABLE.getOrNull(code) ?: continue
                // Per-move elapsed in the same newest-first order at
                // 16 bits each, starting at bit 8.
                val elapsedRaw = v.word(8 + 16 * i, 16)
                val elapsed = if (elapsedRaw == 0L) {
                    // First move after connect / wraparound. Use the
                    // wall-clock delta as a best-effort fallback.
                    ts - lastMoveTimestamp
                } else {
                    elapsedRaw
                }
                cubeTimestamp += elapsed
                events += SmartCubeEvent.Move(
                    face = mapping.face,
                    cw = mapping.cw,
                    cubeTimestamp = cubeTimestamp,
                    deviceTimestamp = ts,
                )
            }
            lastMoveTimestamp = ts
        }
        if (missed > 0) {
            // Tell the orchestrator: "the [diff] moves above are
            // everything we know about; there may have been [missed]
            // more that the cube already overwrote." MoYu's 5-move
            // buffer is shorter than GAN Gen2's 7-move one, so
            // MovesMissed is slightly more likely on a flaky link,
            // but the recovery path is identical (Facelets resync).
            events += SmartCubeEvent.MovesMissed(missedCount = missed, deviceTimestamp = ts)
        }
        return events
    }

    // -- 0xAB Gyroscope ---------------------------------------------

    private fun parseGyro(message: ByteArray, ts: Long): List<SmartCubeEvent> {
        // 4 × signed 32-bit little-endian, in order (w, x, -z, y).
        // Divide each by 2^30 to get a float in roughly [-1, 1].
        // The writeup flags a known firmware quirk in the official
        // implementation where the signed-shift causes off-by-one;
        // we follow the corrected interpretation per the writeup.
        if (message.size < 17) return emptyList()  // 1 type + 16 data
        val w = message.leInt32(1).toFloat() / Q30
        val x = message.leInt32(5).toFloat() / Q30
        val negZ = message.leInt32(9).toFloat() / Q30
        val y = message.leInt32(13).toFloat() / Q30
        val z = -negZ
        return listOf(
            SmartCubeEvent.Gyro(
                // Measured norm on this cube is exactly 1.0, so the
                // normalisation is cosmetic here — it is used for
                // consistency with the other protocols, and because it
                // returns identity rather than a degenerate all-zero
                // quaternion from a still-initialising sensor.
                quat = unitQuaternion(w, x, y, z),
                // MoYu doesn't report angular velocity – pass zero.
                angularVel = Vec3(0f, 0f, 0f),
                deviceTimestamp = ts,
            ),
        )
    }

    // ---------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------

    private companion object {

        /** Maximum on-cube move buffer depth. The cube reports the last
         *  five moves in each MOVE packet; if our `lastSerial` lags by
         *  more than this much, earlier moves are lost and we surface
         *  [SmartCubeEvent.MovesMissed] for the orchestrator to drive a
         *  Facelets resync. */
        const val MOVE_HISTORY_SIZE = 5

        /** Floating-point divisor for the 30-bit fixed-point gyro
         *  components. Cast to Float to keep arithmetic in single
         *  precision. */
        const val Q30: Float = (1 shl 30).toFloat()

        // Message type bytes. Stored as Int (rather than Byte) so the
        // `when` dispatch in [decode] compares Int-to-Int after the
        // unsigned-byte conversion of `packet[0]`. Where they're
        // assigned into a ByteArray (in [buildCommand] and
        // [onConnected]), `.toByte()` does the down-cast at the use
        // site.
        const val MSG_CUBE_INFO: Int = 0xA1
        const val MSG_CUBE_STATUS: Int = 0xA3
        const val MSG_CUBE_POWER: Int = 0xA4
        const val MSG_CUBE_MOVE: Int = 0xA5
        const val MSG_GYROSCOPE: Int = 0xAB
        const val MSG_GYRO_CONFIG: Int = 0xAC

        /** Move-code → (face, cw) table. Codes 0..11 in protocol order:
         *  F, F', B, B', U, U', D, D', L, L', R, R'. */
        val MOVE_CODE_TABLE: List<MoyuWcuMove> = listOf(
            MoyuWcuMove(CubeFace.F, cw = true),
            MoyuWcuMove(CubeFace.F, cw = false),
            MoyuWcuMove(CubeFace.B, cw = true),
            MoyuWcuMove(CubeFace.B, cw = false),
            MoyuWcuMove(CubeFace.U, cw = true),
            MoyuWcuMove(CubeFace.U, cw = false),
            MoyuWcuMove(CubeFace.D, cw = true),
            MoyuWcuMove(CubeFace.D, cw = false),
            MoyuWcuMove(CubeFace.L, cw = true),
            MoyuWcuMove(CubeFace.L, cw = false),
            MoyuWcuMove(CubeFace.R, cw = true),
            MoyuWcuMove(CubeFace.R, cw = false),
        )
    }
}

/**
 * (face, cw) tuple for the WCU move-code table. Tiny data class only
 * because the dispatch table is more readable as `MoyuWcuMove(F, true)`
 * than as a `Pair<CubeFace, Boolean>`.
 */
private data class MoyuWcuMove(val face: CubeFace, val cw: Boolean)
