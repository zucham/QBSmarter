package com.zucham.qbsmarter.domain.driver.gan

import com.zucham.qbsmarter.domain.cube.CubeState
import com.zucham.qbsmarter.domain.cube.N_CORNERS
import com.zucham.qbsmarter.domain.cube.N_EDGES
import com.zucham.qbsmarter.domain.driver.BitView
import com.zucham.qbsmarter.domain.driver.CubeVendor
import com.zucham.qbsmarter.domain.driver.SmartCubeCommand
import com.zucham.qbsmarter.domain.driver.SmartCubeEvent
import com.zucham.qbsmarter.domain.driver.protocol.CubeProtocol
import com.zucham.qbsmarter.domain.driver.protocol.GAN_FACE_ORDER
import com.zucham.qbsmarter.domain.driver.protocol.MoveRecoveryFifo
import com.zucham.qbsmarter.domain.driver.protocol.ProtocolIo
import com.zucham.qbsmarter.util.currentTimeMillis

/**
 * GAN Gen3 wire protocol. Supported cubes:
 *   • GAN356 i Carry 2
 *
 * Gen3 introduced significant changes from Gen2:
 *
 *   • **Different packet format.** Each notification starts with a magic
 *     byte 0x55, followed by a 1-byte event-type and a 1-byte data
 *     length. Field offsets within events are byte-aligned where Gen2's
 *     were bit-packed.
 *
 *   • **Move-history backfill.** Where Gen2 limits its replay buffer to
 *     7 moves and falls back to a full Facelets resync on overflow,
 *     Gen3 supports an opcode 0x68 0x03 that asks the cube to retransmit
 *     a window of moves identified by serial number. This protocol
 *     leverages it via [MoveRecoveryFifo]: when a move event arrives out
 *     of sequence it gets buffered, the FIFO asks for the missing window
 *     through the `requestHistory` callback, and once the cube's
 *     MOVE_HISTORY (0x06) event lands the missing moves are injected at
 *     the correct buffer position and the FIFO drains contiguously.
 *
 *   • **No gyro.** The Gen3 protocol omits gyroscope data – the i Carry 2
 *     hardware doesn't include the sensor. Gyro events are simply never
 *     produced.
 *
 *   • **Move encoding.** Faces are packed as a 6-bit one-hot field;
 *     direction is 2 bits at the same offset (split across the same
 *     byte). The face's URFDLB index is recovered by `indexOf` on the
 *     [GAN_GEN3_4_FACE_ONE_HOT] table.
 *
 * **Overflow rule.** If the FIFO grows past its overflow limit, the cube
 * has either lost connection coherence or is generating moves faster
 * than we can backfill. Our orchestrator has a global
 * [SmartCubeEvent.MovesMissed] → Facelets path that achieves the same
 * recovery without forcing the user back through a full pair flow, so
 * [MoveRecoveryFifo] emits MovesMissed rather than disconnecting.
 *
 * Per-connection instance, so the FIFO never carries a stale serial
 * baseline from a previous cube.
 */
internal class GanGen3Protocol : CubeProtocol {

    override val vendor: CubeVendor = CubeVendor.GAN

    override val id: String = "gan-gen3"

    /**
     * Pending moves plus the serial bookkeeping that orders them. Owns
     * the rolling serial, the last-emitted serial, and the timestamp of
     * the last live move packet.
     */
    private val fifo = MoveRecoveryFifo()

    override fun buildCommand(cmd: SmartCubeCommand): ByteArray? = when (cmd) {
        SmartCubeCommand.RequestFacelets ->
            ByteArray(16).also { it[0] = 0x68; it[1] = 0x01 }
        SmartCubeCommand.RequestHardware ->
            ByteArray(16).also { it[0] = 0x68; it[1] = 0x04 }
        SmartCubeCommand.RequestBattery ->
            ByteArray(16).also { it[0] = 0x68; it[1] = 0x07 }
        SmartCubeCommand.RequestReset -> byteArrayOf(
            0x68, 0x05, 0x05, 0x39, 0x77, 0x00, 0x00, 0x01,
            0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0x00, 0x00, 0x00,
        )
        is SmartCubeCommand.RequestMoveHistory -> buildMoveHistoryCommand(cmd.startSerial, cmd.count)
    }

    /**
     * Build the Gen3 MOVE_HISTORY request (opcode 0x68 0x03).
     *
     * Firmware quirks the request must work around:
     *   • move-history responses are byte-aligned and always start at
     *     the nearest *odd* serial below the requested one
     *   • the response always packs an even number of moves
     *   • requesting moves spanning the 255 → 0 wrap returns garbage
     *     (zeroed `D` moves) – clip to `serial + 1` to avoid that
     */
    private fun buildMoveHistoryCommand(startSerial: Int, count: Int): ByteArray {
        // Round serial DOWN to the nearest odd number; round count UP to
        // the nearest even number. & 0xFF wraps within the 8-bit serial
        // space.
        val alignedSerial = if (startSerial % 2 == 0) (startSerial - 1) and 0xFF else startSerial
        val alignedCount = if (count % 2 == 1) count + 1 else count
        // Don't overflow the 255 → 0 boundary.
        val safeCount = minOf(alignedCount, alignedSerial + 1)
        return ByteArray(16).also {
            it[0] = 0x68
            it[1] = 0x03
            it[2] = (alignedSerial and 0xFF).toByte()
            it[3] = 0
            it[4] = (safeCount and 0xFF).toByte()
            it[5] = 0
        }
    }

    override suspend fun decode(packet: ByteArray, io: ProtocolIo): List<SmartCubeEvent> {
        val requestHistory: suspend (Int, Int) -> Unit = { start, count ->
            io.send(SmartCubeCommand.RequestMoveHistory(start, count))
        }
        val ts = currentTimeMillis()
        val msg = BitView(packet)

        val magic = msg.word(0, 8)
        val eventType = msg.word(8, 8)
        val dataLength = msg.word(16, 8)

        if (magic != 0x55L || dataLength == 0L) return emptyList()

        return when (eventType.toInt()) {
            0x01 -> parseMove(msg, ts, requestHistory)
            0x06 -> parseMoveHistory(msg, ts, dataLength.toInt())
            0x02 -> parseFacelets(msg, ts, requestHistory)
            0x07 -> parseHardware(msg, ts)
            0x10 -> parseBattery(msg, ts)
            0x11 -> listOf(SmartCubeEvent.Disconnect)
            else -> emptyList()
        }
    }

    private suspend fun parseMove(
        v: BitView,
        ts: Long,
        requestHistory: suspend (Int, Int) -> Unit,
    ): List<SmartCubeEvent> {
        // Accept move events only after the first facelets state event:
        // before that, lastSerial is undefined and we have no anchor for
        // the diff calculation. Same constraint as Gen2.
        if (fifo.lastSerial == -1) return emptyList()

        fifo.lastLocalTimestamp = ts
        val cubeTs = v.wordLE(24, 32)
        val s = v.wordLE(56, 16).toInt() and 0xFF  // serial wraps at 256 even though field is 16 bits
        // Set unconditionally: an unrecognised face mask skips the push
        // below, and the cube's serial still advanced.
        fifo.serial = s

        val direction = v.word(72, 2).toInt()
        // 6-bit one-hot face encoding. Lookup table: 2→U, 32→R, 8→F,
        // 1→D, 16→L, 4→B (URFDLB indexing). indexOf returns -1 for an
        // unrecognised mask – treat that as a malformed packet and
        // skip event emission.
        val faceMask = v.word(74, 6).toInt()
        val faceIdx = GAN_GEN3_4_FACE_ONE_HOT.indexOf(faceMask)
        val face = GAN_FACE_ORDER.getOrNull(faceIdx)

        if (face != null) {
            fifo.push(
                SmartCubeEvent.Move(
                    face = face,
                    cw = direction == 0,
                    cubeTimestamp = cubeTs,
                    deviceTimestamp = ts,
                ),
                s,
            )
        }

        return fifo.drain(allowHistoryRequest = true, ts = ts, requestHistory = requestHistory)
    }

    private suspend fun parseMoveHistory(
        v: BitView,
        ts: Long,
        dataLength: Int,
    ): List<SmartCubeEvent> {
        val startSerial = v.word(24, 8).toInt()
        val count = (dataLength - 1) * 2

        // Inject in the order the cube sends them (newest → oldest).
        // The injection function checks each candidate against the
        // current buffer head and prepends only when it fits.
        for (i in 0 until count) {
            val faceMask = v.word(32 + 4 * i, 3).toInt()
            val direction = v.word(35 + 4 * i, 1).toInt()
            // Different lookup table from the live MOVE event – Gen3's
            // MOVE_HISTORY uses a 3-bit zero-based face index, ordered
            // [1,5,3,0,4,2] → URFDLB. Why a different ordering for the
            // same logical data? The wire designers presumably squeezed
            // 4-bit pairs to fit twice as many moves per packet; the
            // ordering happens to match a different sort.
            val faceIdx = GAN_GEN3_4_HISTORY_FACE_ORDER.indexOf(faceMask)
            val face = GAN_FACE_ORDER.getOrNull(faceIdx) ?: continue
            val histSerial = (startSerial - i) and 0xFF
            // Cube hardware timestamp for missed moves is unrecoverable.
            // We use 0 here for simplicity – the cube-clock based duration
            // computation in [SolveTimer] uses first/last timestamps so
            // historical zeros only matter if a backfill move is the
            // first or last event of the solve, which is rare.
            fifo.injectRecovered(
                move = SmartCubeEvent.Move(
                    face = face,
                    cw = direction == 0,
                    cubeTimestamp = 0L,
                    deviceTimestamp = ts,
                ),
                serialOfMove = histSerial,
            )
        }

        // No history request during the drain here – we're already inside
        // a history-response path, and re-requesting would loop.
        return fifo.drain(allowHistoryRequest = false, ts = ts, requestHistory = { _, _ -> })
    }

    private suspend fun parseFacelets(
        v: BitView,
        ts: Long,
        requestHistory: suspend (Int, Int) -> Unit,
    ): List<SmartCubeEvent> {
        val s = v.wordLE(24, 16).toInt() and 0xFF
        fifo.serial = s

        // Recover any missed moves: if the periodic facelets event
        // reports a serial higher than what we've evicted, ask for the
        // gap. Debounce so we don't fire a recovery in the middle of a
        // burst of live moves – wait for 500 ms of move silence first.
        if (fifo.lastSerial != -1 && fifo.lastLocalTimestamp != 0L &&
            (ts - fifo.lastLocalTimestamp) > FACELETS_DEBOUNCE_MS
        ) {
            fifo.requestMissedIfBehind(requestHistory)
        }

        if (fifo.lastSerial == -1) fifo.lastSerial = s

        val cp = IntArray(N_CORNERS)
        val co = IntArray(N_CORNERS)
        var cpSum = 0
        var coSum = 0
        for (i in 0 until 7) {
            cp[i] = v.word(40 + i * 3, 3).toInt()
            co[i] = v.word(61 + i * 2, 2).toInt()
            cpSum += cp[i]
            coSum += co[i]
        }
        cp[7] = 28 - cpSum
        co[7] = (3 - coSum % 3) % 3

        val ep = IntArray(N_EDGES)
        val eo = IntArray(N_EDGES)
        var epSum = 0
        var eoSum = 0
        for (i in 0 until 11) {
            ep[i] = v.word(77 + i * 4, 4).toInt()
            eo[i] = v.word(121 + i, 1).toInt()
            epSum += ep[i]
            eoSum += eo[i]
        }
        ep[11] = 66 - epSum
        eo[11] = (2 - eoSum % 2) % 2

        return listOf(
            SmartCubeEvent.Facelets(
                state = CubeState(cp, co, ep, eo),
                deviceTimestamp = ts,
            ),
        )
    }

    private fun parseHardware(v: BitView, ts: Long): List<SmartCubeEvent> {
        val swMajor = v.word(72, 4).toInt()
        val swMinor = v.word(76, 4).toInt()
        val hwMajor = v.word(80, 4).toInt()
        val hwMinor = v.word(84, 4).toInt()

        // 5-byte hardware-name field starting at byte 4.
        val nameBuilder = StringBuilder()
        repeat(5) { i ->
            nameBuilder.append(v.word(32 + i * 8, 8).toInt().toChar())
        }

        return listOf(
            SmartCubeEvent.Hardware(
                deviceTimestamp = ts,
                name = nameBuilder.toString(),
                hwVersion = "$hwMajor.$hwMinor",
                swVersion = "$swMajor.$swMinor",
                // Gen3 hardware (i Carry 2) has no gyro sensor.
                gyroSupported = false,
                vendor = CubeVendor.GAN,
            ),
        )
    }

    private fun parseBattery(v: BitView, ts: Long): List<SmartCubeEvent> {
        val level = v.word(24, 8).toInt().coerceAtMost(100)
        return listOf(SmartCubeEvent.Battery(deviceTimestamp = ts, level = level))
    }

    private companion object {
        /**
         * Debounce period before a periodic Facelets event triggers a
         * move-history request. Inside this window, the cube is likely
         * still streaming live MOVE events that the FIFO hasn't yet
         * processed – we don't want to ask for moves we're about to
         * receive normally.
         */
        const val FACELETS_DEBOUNCE_MS = 500L
    }
}

/**
 * Gen3/Gen4 live-move face encoding. Each entry is the 6-bit one-hot
 * mask the cube emits; index in this list is the URFDLB index used by
 * [GAN_FACE_ORDER]. So `indexOf(mask)` recovers the face.
 *
 *   index 0 → 2  → U
 *   index 1 → 32 → R
 *   index 2 → 8  → F
 *   index 3 → 1  → D
 *   index 4 → 16 → L
 *   index 5 → 4  → B
 *
 * Lives here rather than in a shared codec file because it is the one
 * table both GAN generations that use one-hot faces share, and nothing
 * else in the app has any use for it. [GanGen4Protocol] reads it from
 * this same package.
 */
internal val GAN_GEN3_4_FACE_ONE_HOT = listOf(2, 32, 8, 1, 16, 4)

/**
 * Gen3/Gen4 MOVE_HISTORY face encoding (3-bit indexed). Different from
 * the live-move encoding above – the wire designers used a different
 * order to pack two moves per byte. `indexOf(rawValue)` recovers the
 * URFDLB index.
 */
internal val GAN_GEN3_4_HISTORY_FACE_ORDER = listOf(1, 5, 3, 0, 4, 2)
