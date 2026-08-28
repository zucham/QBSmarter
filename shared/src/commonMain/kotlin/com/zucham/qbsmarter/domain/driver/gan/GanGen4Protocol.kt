package com.zucham.qbsmarter.domain.driver.gan

import com.zakgof.korender.math.Quaternion
import com.zakgof.korender.math.Vec3
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
import com.zucham.qbsmarter.domain.driver.protocol.ganSignMagnitude
import com.zucham.qbsmarter.util.currentTimeMillis

/**
 * GAN Gen4 wire protocol. Supported cubes:
 *   • GAN12 ui Maglev
 *   • GAN14 ui FreePlay
 *   • GAN i4
 *
 * Gen4 shares Gen3's general philosophy – move-history backfill, ordered
 * FIFO drain, byte-aligned packets – with these specific differences:
 *
 *   • **No leading magic byte.** Where Gen3 prefixes every notification
 *     with 0x55, Gen4 puts the event-type byte at offset 0 directly.
 *     Every field offset here is therefore Gen3's minus 8 bits.
 *   • **Distinct opcode set.** Commands and events use a different
 *     numeric space (0xDD / 0xDF / 0xD2 / 0xD1 commands; 0x01 / 0xD1 /
 *     0xED / 0xEC / 0xEF / 0xEA / 0xFA-0xFE events).
 *   • **Hardware info split across multiple events.** Gen4 spreads HW/
 *     SW/name/date across 4 separate events (0xFA, 0xFC, 0xFD, 0xFE);
 *     see [hwInfo] for how they are accumulated.
 *   • **Gyro support.** Unlike Gen3, Gen4 reports orientation (0xEC
 *     event) on some hardware and not on others, and carries no
 *     capability bit saying which — see [GEN4_GYRO_HARDWARE_NAMES].
 *
 * The FIFO/backfill machinery is [MoveRecoveryFifo], shared verbatim
 * with [GanGen3Protocol]; only the wire offsets and opcodes differ, and
 * those stay here.
 */
internal class GanGen4Protocol : CubeProtocol {

    override val vendor: CubeVendor = CubeVendor.GAN

    override val id: String = "gan-gen4"

    /**
     * Pending moves plus the serial bookkeeping that orders them. Owns
     * the rolling serial, the last-emitted serial, and the timestamp of
     * the last live move packet.
     */
    private val fifo = MoveRecoveryFifo()

    /**
     * Hardware-info accumulator, keyed by event opcode (0xFA / 0xFC /
     * 0xFD / 0xFE).
     *
     * A [SmartCubeEvent.Hardware] is raised as **each** fragment lands,
     * carrying everything known so far. The upstream reference instead
     * waits for all four and emits once, which means a single dropped
     * notification loses the hardware info for the whole session — no
     * retry, no timeout. In practice that surfaces as a gyro-capable
     * cube reporting its gyro support as "unknown" forever: the flag
     * rides on the hardware name, and the name never completed the set.
     *
     * Emitting incrementally makes a dropped fragment cost only that
     * fragment's own field. Consumers already treat the event as an
     * upsert (see [SmartCubeEvent.Hardware]), so later, more complete
     * copies simply supersede earlier ones.
     *
     * Never cleared mid-connection — not even on a re-issued
     * [SmartCubeCommand.RequestHardware], which the orchestrator sends
     * on a retry loop — so a retry can only add information, never
     * remove it. The instance itself is per-connection, so there is
     * nothing to clear on teardown either.
     */
    private val hwInfo: MutableMap<Int, String> = mutableMapOf()

    override fun buildCommand(cmd: SmartCubeCommand): ByteArray? = when (cmd) {
        SmartCubeCommand.RequestFacelets -> ByteArray(20).also {
            it[0] = 0xDD.toByte(); it[1] = 0x04; it[2] = 0x00; it[3] = 0xED.toByte()
            it[4] = 0x00; it[5] = 0x00
        }
        // The accumulator is deliberately NOT cleared here – see
        // [hwInfo]. A re-request is a chance to fill gaps, not a reason
        // to throw away fragments that already arrived.
        SmartCubeCommand.RequestHardware -> ByteArray(20).also {
            it[0] = 0xDF.toByte(); it[1] = 0x03; it[2] = 0x00; it[3] = 0x00; it[4] = 0x00
        }
        SmartCubeCommand.RequestBattery -> ByteArray(20).also {
            it[0] = 0xDD.toByte(); it[1] = 0x04; it[2] = 0x00; it[3] = 0xEF.toByte()
            it[4] = 0x00; it[5] = 0x00
        }
        SmartCubeCommand.RequestReset -> byteArrayOf(
            0xD2.toByte(), 0x0D, 0x05, 0x39, 0x77, 0x00, 0x00, 0x01,
            0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        is SmartCubeCommand.RequestMoveHistory -> buildMoveHistoryCommand(cmd.startSerial, cmd.count)
    }

    /** Mirror of [GanGen3Protocol]'s move-history request with the Gen4 opcode. */
    private fun buildMoveHistoryCommand(startSerial: Int, count: Int): ByteArray {
        val alignedSerial = if (startSerial % 2 == 0) (startSerial - 1) and 0xFF else startSerial
        val alignedCount = if (count % 2 == 1) count + 1 else count
        val safeCount = minOf(alignedCount, alignedSerial + 1)
        return ByteArray(20).also {
            it[0] = 0xD1.toByte()
            it[1] = 0x04
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

        val eventType = msg.word(0, 8).toInt()
        val dataLength = msg.word(8, 8).toInt()

        return when (eventType) {
            0x01 -> parseMove(msg, ts, requestHistory)
            0xD1 -> parseMoveHistory(msg, ts, dataLength)
            0xED -> parseFacelets(msg, ts, requestHistory)
            in 0xFA..0xFE -> parseHardwareFragment(msg, ts, eventType, dataLength)
            0xEC -> parseGyro(msg, ts)
            0xEF -> parseBattery(msg, ts, dataLength)
            0xEA -> listOf(SmartCubeEvent.Disconnect)
            else -> emptyList()
        }
    }

    private suspend fun parseMove(
        v: BitView,
        ts: Long,
        requestHistory: suspend (Int, Int) -> Unit,
    ): List<SmartCubeEvent> {
        if (fifo.lastSerial == -1) return emptyList()
        fifo.lastLocalTimestamp = ts

        // Same logical layout as Gen3 but shifted by the 8 bits Gen3
        // spent on its 0x55 magic byte. The cube timestamp moves from
        // bit 24 to bit 16; the serial from bit 56 to bit 48; etc.
        // (Note: dataLength was already at bit 8 in both formats.)
        val cubeTs = v.wordLE(16, 32)
        val s = v.wordLE(48, 16).toInt() and 0xFF
        // Set unconditionally: an unrecognised face mask skips the push
        // below, and the cube's serial still advanced.
        fifo.serial = s
        val direction = v.word(64, 2).toInt()
        val faceMask = v.word(66, 6).toInt()
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
        val startSerial = v.word(16, 8).toInt()
        val count = (dataLength - 1) * 2

        for (i in 0 until count) {
            val faceMask = v.word(24 + 4 * i, 3).toInt()
            val direction = v.word(27 + 4 * i, 1).toInt()
            val faceIdx = GAN_GEN3_4_HISTORY_FACE_ORDER.indexOf(faceMask)
            val face = GAN_FACE_ORDER.getOrNull(faceIdx) ?: continue
            val histSerial = (startSerial - i) and 0xFF
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

        return fifo.drain(allowHistoryRequest = false, ts = ts, requestHistory = { _, _ -> })
    }

    private suspend fun parseFacelets(
        v: BitView,
        ts: Long,
        requestHistory: suspend (Int, Int) -> Unit,
    ): List<SmartCubeEvent> {
        val s = v.wordLE(16, 16).toInt() and 0xFF
        fifo.serial = s

        if (fifo.lastSerial != -1 && fifo.lastLocalTimestamp != 0L &&
            (ts - fifo.lastLocalTimestamp) > FACELETS_DEBOUNCE_MS
        ) {
            fifo.requestMissedIfBehind(requestHistory)
        }

        if (fifo.lastSerial == -1) fifo.lastSerial = s

        // Field offsets shifted -8 from Gen3 (no magic byte).
        val cp = IntArray(N_CORNERS)
        val co = IntArray(N_CORNERS)
        var cpSum = 0
        var coSum = 0
        for (i in 0 until 7) {
            cp[i] = v.word(32 + i * 3, 3).toInt()
            co[i] = v.word(53 + i * 2, 2).toInt()
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
            ep[i] = v.word(69 + i * 4, 4).toInt()
            eo[i] = v.word(113 + i, 1).toInt()
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

    /**
     * One slice of the multi-event hardware-info reply. The cube emits
     * 0xFA (production date), 0xFC (hardware name), 0xFD (software
     * version), and 0xFE (hardware version) sequentially in response to
     * a single RequestHardware command. Each fragment writes into
     * [hwInfo] and then re-emits the accumulated [SmartCubeEvent.Hardware]
     * so far; a fragment that adds nothing new emits nothing.
     */
    private fun parseHardwareFragment(
        v: BitView,
        ts: Long,
        eventType: Int,
        dataLength: Int,
    ): List<SmartCubeEvent> {
        val before = hwInfo[eventType]
        when (eventType) {
            0xFA -> {
                // Production date: year (LE u16), month (u8), day (u8).
                val year = v.wordLE(24, 16).toInt()
                val month = v.word(40, 8).toInt()
                val day = v.word(48, 8).toInt()
                hwInfo[eventType] = "${pad4(year)}-${pad2(month)}-${pad2(day)}"
            }
            0xFC -> {
                // Hardware name: ASCII chars, length = dataLength - 1.
                val nameBuilder = StringBuilder()
                repeat(dataLength - 1) { i ->
                    nameBuilder.append(v.word(24 + i * 8, 8).toInt().toChar())
                }
                hwInfo[eventType] = nameBuilder.toString()
            }
            0xFD -> {
                val swMajor = v.word(24, 4).toInt()
                val swMinor = v.word(28, 4).toInt()
                hwInfo[eventType] = "$swMajor.$swMinor"
            }
            0xFE -> {
                val hwMajor = v.word(24, 4).toInt()
                val hwMinor = v.word(28, 4).toInt()
                hwInfo[eventType] = "$hwMajor.$hwMinor"
            }
        }

        // Nothing new in this packet (a duplicate from a retry, or an
        // opcode in the 0xFA..0xFE range we don't decode) – don't
        // bother the rest of the app with it.
        if (hwInfo[eventType] == before) return emptyList()

        val name = hwInfo[0xFC]
        return listOf(
            SmartCubeEvent.Hardware(
                deviceTimestamp = ts,
                name = name ?: "",
                hwVersion = hwInfo[0xFE] ?: "",
                swVersion = hwInfo[0xFD] ?: "",
                // Gen4 carries no gyro capability bit the way Gen2 does;
                // support is inferred from the hardware name. Until the
                // name fragment arrives the honest answer is "don't
                // know" – reporting false here would persist as a hard
                // "no gyro" and hide the feature on a cube that has it.
                //
                // The allow-list is a fast path, not the whole answer;
                // see [GEN4_GYRO_HARDWARE_NAMES] for why it is known to
                // be incomplete and why that is safe.
                gyroSupported = name?.let { it in GEN4_GYRO_HARDWARE_NAMES },
                vendor = CubeVendor.GAN,
            ),
        )
    }

    private fun parseGyro(v: BitView, ts: Long): List<SmartCubeEvent> {
        val qw = v.word(16, 16).toInt()
        val qx = v.word(32, 16).toInt()
        val qy = v.word(48, 16).toInt()
        val qz = v.word(64, 16).toInt()

        val vx = v.word(80, 4).toInt()
        val vy = v.word(84, 4).toInt()
        val vz = v.word(88, 4).toInt()

        return listOf(
            SmartCubeEvent.Gyro(
                quat = Quaternion(
                    ganSignMagnitude(qw, 16),
                    Vec3(ganSignMagnitude(qx, 16), ganSignMagnitude(qy, 16), ganSignMagnitude(qz, 16)),
                ),
                angularVel = Vec3(
                    ganSignMagnitude(vx, 4),
                    ganSignMagnitude(vy, 4),
                    ganSignMagnitude(vz, 4),
                ),
                deviceTimestamp = ts,
            ),
        )
    }

    /**
     * Battery event. The byte position depends on the encoded data
     * length – the upstream reference computes it as
     * `(8 + dataLength * 8)` bits, i.e. immediately after the
     * length-encoded data block. Field width is a single byte.
     */
    private fun parseBattery(v: BitView, ts: Long, dataLength: Int): List<SmartCubeEvent> {
        val level = v.word(8 + dataLength * 8, 8).toInt().coerceAtMost(100)
        return listOf(SmartCubeEvent.Battery(deviceTimestamp = ts, level = level))
    }

    private fun pad2(n: Int): String = n.toString().padStart(2, '0')
    private fun pad4(n: Int): String = n.toString().padStart(4, '0')

    private companion object {
        /**
         * Debounce period before a periodic Facelets event triggers a
         * move-history request. Inside this window the cube is likely
         * still streaming live MOVE events the FIFO hasn't processed
         * yet, and asking for them would request moves already in
         * flight.
         */
        const val FACELETS_DEBOUNCE_MS = 500L

        /**
         * Gen4 hardware names confirmed against real hardware to carry a
         * gyroscope. Membership makes [SmartCubeEvent.Hardware] report
         * `gyroSupported = true` the moment the name fragment lands,
         * which is a little earlier than the cube's first orientation
         * packet would.
         *
         * **This list is knowingly incomplete, and must not be read as
         * the definitive answer.** The GAN i4 is the documented
         * counter-example: it reports its hardware name as `GANi4`,
         * is absent from this list, and nonetheless streams 0xEC gyro
         * packets in verified wire captures. It is deliberately *not*
         * added — the point of the counter-example is that any
         * name-based list is a guess about models we have never seen,
         * and enumerating them is not a solvable problem.
         *
         * What makes the omission harmless is that `gyroSupported` is a
         * `Boolean?` and stays null until the name arrives: a name not
         * on the list yields false, but gyro notifications are
         * unsolicited, so `ConnectionOrchestrator` upgrades the cube to
         * gyro-capable on observing an actual 0xEC packet. The
         * allow-list only ever buys latency; the wire is the authority.
         */
        val GEN4_GYRO_HARDWARE_NAMES = setOf("GAN12uiM")
    }
}
