package com.zucham.qbsmarter.domain.driver.moyu

import co.touchlab.kermit.Logger
import com.zakgof.korender.math.Quaternion
import com.zakgof.korender.math.Vec3
import com.zucham.qbsmarter.domain.cube.CubeFace
import com.zucham.qbsmarter.domain.driver.BitView
import com.zucham.qbsmarter.domain.driver.CubeEncryptor
import com.zucham.qbsmarter.domain.driver.CubeTransport
import com.zucham.qbsmarter.domain.driver.CubeVendor
import com.zucham.qbsmarter.domain.driver.SmartCubeCommand
import com.zucham.qbsmarter.domain.driver.SmartCubeDriver
import com.zucham.qbsmarter.domain.driver.SmartCubeEvent
import com.zucham.qbsmarter.util.currentTimeMillis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Smart-cube driver for the MoYu WeiLong V10 AI.
 *
 * **Protocol shape.** 20-byte AES-CBC-encrypted packets in both
 * directions, message type in byte 0:
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
 * **Recovery model.** MoYu V10's move event reports the last 5 moves
 * with a single rolling 8-bit serial counter. Same pattern as GAN
 * Gen2's 7-move buffer; no targeted move-history retransmit is
 * documented in the protocol. So we mirror Gen2 exactly: on a serial
 * jump greater than [MOVE_HISTORY_SIZE] we emit
 * [SmartCubeEvent.MovesMissed], and the connection orchestrator's
 * existing debounced Facelets-resync path handles recovery.
 *
 * **Init quirk.** Per the protocol writeup: "Immediately after
 * connecting to the cube, you need to write a Cube Info (0xA1)
 * message to initialise correctly the cube." The connection
 * orchestrator already sends [SmartCubeCommand.RequestHardware] as
 * the first post-connect command, which maps here to 0xA1, so this
 * happens naturally.
 *
 * **Gyro state.** The cube ships with gyro enabled by default. We
 * proactively send `0xAC enable` after connect to ensure a known
 * state (the previous app session may have left it disabled, since
 * the setting is persistent across reconnects). See [enableGyro].
 *
 * **Reset.** The protocol writeup documents no reset opcode. The
 * `SmartCubeCommand.RequestReset` case in [buildCommand] returns null
 * and the orchestrator's reset path falls back to whatever
 * higher-level "reset visual state" the app has – which is exactly
 * what we want, since you can't software-reset a cube to "solved"
 * anyway (the cube reports the physical state; the app's notion of
 * "solved" is an internal reset of CubeState).
 *
 * **Stable events flow.** Like [com.zucham.qbsmarter.domain.driver.gan.GanCubeDriver],
 * the `events` SharedFlow is stable for the lifetime of the driver
 * (a Koin singleton). Cube swaps don't recreate it, so subscribers
 * don't need to re-bind.
 */
class MoyuCubeDriver(
    parserDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SmartCubeDriver {

    private val log = Logger.withTag("MoyuCubeDriver")
    private val scope = CoroutineScope(SupervisorJob() + parserDispatcher)

    private var transport: CubeTransport? = null
    private var encryptor: CubeEncryptor? = null
    private var ingestJob: Job? = null

    private val _events = MutableSharedFlow<SmartCubeEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val events: SharedFlow<SmartCubeEvent> = _events.asSharedFlow()

    // ----- Parser state -----
    //
    // Reset on every (re-)connect via [resetParserState] so stale
    // serials from a previous cube don't affect the new session.

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

    override suspend fun connect(transport: CubeTransport, encryptor: CubeEncryptor) {
        if (this.transport === transport && ingestJob?.isActive == true) {
            // Idempotent reconnect – same transport already running.
            return
        }
        disconnect()
        resetParserState()
        this.transport = transport
        this.encryptor = encryptor
        transport.enableNotifications()
        ingestJob = scope.launch {
            log.d { "Driver collecting from MoYu transport" }
            transport.incoming.collect { raw ->
                runCatching {
                    val plain = encryptor.decrypt(raw)
                    val events = parsePacket(plain)
                    log.d { "decrypted -> ${events.size} events" }
                    events.forEach { event ->
                        log.d { "emit $event" }
                        _events.tryEmit(event)
                    }
                }.onFailure { log.e(it) { "Failed to parse MoYu packet" } }
            }
            log.w { "MoYu driver collect ended" }
        }
    }

    override suspend fun send(command: SmartCubeCommand) {
        val t = transport ?: return
        val e = encryptor ?: return
        val cmd = buildCommand(command) ?: return
        t.write(e.encrypt(cmd))
    }

    /**
     * Send the proprietary `0xAC` gyro-enable command. The cube
     * acknowledges with a `0xAC` reply (we ignore the ack – we just
     * needed to make sure the cube is in a known gyro-on state).
     * Called by the connection orchestrator after the post-connect
     * handshake to ensure gyro is on regardless of whatever state a
     * previous client session left the cube in.
     */
    suspend fun enableGyro() {
        val t = transport ?: return
        val e = encryptor ?: return
        val payload = ByteArray(20).apply {
            this[0] = MSG_GYRO_CONFIG.toByte()
            this[2] = 0x01  // 1 = enable; 0 = disable
        }
        runCatching { t.write(e.encrypt(payload)) }
            .onFailure { log.w(it) { "enableGyro failed" } }
    }

    override suspend fun disconnect() {
        ingestJob?.cancel()
        ingestJob = null
        transport = null
        encryptor = null
        resetParserState()
    }

    private fun resetParserState() {
        lastSerial = -1
        lastMoveTimestamp = 0
        cubeTimestamp = 0
    }

    // ---------------------------------------------------------------
    // Wire encoding / decoding
    // ---------------------------------------------------------------

    private fun buildCommand(cmd: SmartCubeCommand): ByteArray? = when (cmd) {
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

    private fun parsePacket(message: ByteArray): List<SmartCubeEvent> {
        if (message.isEmpty()) return emptyList()
        val ts = currentTimeMillis()
        return when (val type = message[0].toInt() and 0xFF) {
            MSG_CUBE_INFO -> parseCubeInfo(message, ts)
            MSG_CUBE_STATUS -> parseFacelets(message, ts)
            MSG_CUBE_POWER -> parseBattery(message, ts)
            MSG_CUBE_MOVE -> parseMove(message, ts)
            MSG_GYROSCOPE -> parseGyro(message, ts)
            MSG_GYRO_CONFIG -> emptyList()  // ack-only; ignore
            else -> {
                log.d { "ignoring unknown MoYu packet type 0x${type.toString(16)}" }
                emptyList()
            }
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
        val state = MoyuFaceletDecoder.decode(stickers)
        if (state == null) {
            log.w { "Facelets packet did not decode to a valid CubeState; ignoring" }
            return emptyList()
        }
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
        val w = readS32LE(message, 1) / Q30
        val x = readS32LE(message, 5) / Q30
        val negZ = readS32LE(message, 9) / Q30
        val y = readS32LE(message, 13) / Q30
        val z = -negZ
        return listOf(
            SmartCubeEvent.Gyro(
                // Korender's Quaternion is (w, Vec3(x, y, z)); see
                // gan-parser usage of the same constructor.
                quat = Quaternion(w, Vec3(x, y, z)),
                // MoYu doesn't report angular velocity – pass zero.
                angularVel = Vec3(0f, 0f, 0f),
                deviceTimestamp = ts,
            ),
        )
    }

    private fun readS32LE(buf: ByteArray, offset: Int): Float {
        val b0 = buf[offset].toInt() and 0xFF
        val b1 = buf[offset + 1].toInt() and 0xFF
        val b2 = buf[offset + 2].toInt() and 0xFF
        val b3 = buf[offset + 3].toInt() and 0xFF
        // Assemble big-endian Int (so the resulting Int has the same
        // numeric value the bytes would form when read as a signed
        // 32-bit LE integer – the top byte b3 is the sign-bearing one).
        val asInt = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        return asInt.toFloat()
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
         *  precision; quaternions are normalised downstream by Korender
         *  for rendering. */
        const val Q30: Float = (1 shl 30).toFloat()

        // Message type bytes. Stored as Int (rather than Byte) so the
        // `when` dispatch in [parsePacket] compares Int-to-Int after
        // the unsigned-byte conversion of `message[0]`. Where they're
        // assigned into a ByteArray (in [buildCommand] and
        // [enableGyro]), `.toByte()` does the down-cast at the use
        // site.
        const val MSG_CUBE_INFO: Int = 0xA1
        const val MSG_CUBE_STATUS: Int = 0xA3
        const val MSG_CUBE_POWER: Int = 0xA4
        const val MSG_CUBE_MOVE: Int = 0xA5
        const val MSG_GYROSCOPE: Int = 0xAB
        const val MSG_GYRO_CONFIG: Int = 0xAC

        /** Move-code → (face, cw) table. Codes 0..11 in protocol order:
         *  F, F', B, B', U, U', D, D', L, L', R, R'. */
        val MOVE_CODE_TABLE: List<MoyuMove> = listOf(
            MoyuMove(CubeFace.F, cw = true),
            MoyuMove(CubeFace.F, cw = false),
            MoyuMove(CubeFace.B, cw = true),
            MoyuMove(CubeFace.B, cw = false),
            MoyuMove(CubeFace.U, cw = true),
            MoyuMove(CubeFace.U, cw = false),
            MoyuMove(CubeFace.D, cw = true),
            MoyuMove(CubeFace.D, cw = false),
            MoyuMove(CubeFace.L, cw = true),
            MoyuMove(CubeFace.L, cw = false),
            MoyuMove(CubeFace.R, cw = true),
            MoyuMove(CubeFace.R, cw = false),
        )
    }
}

/**
 * (face, cw) tuple for the MoYu move-code table. Tiny data class only
 * because the dispatch table is more readable as `MoyuMove(F, true)`
 * than as a `Pair<CubeFace, Boolean>`.
 */
private data class MoyuMove(val face: CubeFace, val cw: Boolean)
