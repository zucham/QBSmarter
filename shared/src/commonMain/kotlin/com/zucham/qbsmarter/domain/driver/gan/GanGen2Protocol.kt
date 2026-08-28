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
import com.zucham.qbsmarter.domain.driver.protocol.ProtocolIo
import com.zucham.qbsmarter.domain.driver.protocol.ganSignMagnitude
import com.zucham.qbsmarter.util.currentTimeMillis

/**
 * GAN Gen2 wire protocol. Supported cubes:
 *   • GAN356 i Carry, i Carry S, i 3
 *   • GAN12 ui, GAN Mini ui FreePlay
 *   • Monster Go 3Ai
 *
 * **Everything is bit-packed.** Gen2 predates the byte-aligned framing
 * Gen3 and Gen4 use: the opcode is a 4-bit field, moves are 5 bits each
 * and facelet permutations straddle byte boundaries throughout. Every
 * offset here is a bit offset into [BitView], and the numbers are not
 * negotiable.
 *
 * **Recovery is a full resync, not a backfill.** A single notification
 * reports up to [MOVE_HISTORY_SIZE] turns since the last one, indexed by
 * a rolling serial. Beyond that depth the cube has already overwritten
 * the moves and there is no targeted retransmit opcode to ask for them
 * — so this protocol emits [SmartCubeEvent.MovesMissed] and lets the
 * orchestrator recover with a full [SmartCubeCommand.RequestFacelets].
 * That is why [buildCommand] returns null for
 * [SmartCubeCommand.RequestMoveHistory] and why [decode] never asks the
 * driver to write anything: the serial-gap backfill Gen3 and Gen4
 * run through `MoveRecoveryFifo` has no Gen2 equivalent.
 *
 * Stateful: it tracks the previous serial and the running cube clock.
 * Per-connection instance, so none of that needs resetting.
 */
internal class GanGen2Protocol : CubeProtocol {

    override val vendor: CubeVendor = CubeVendor.GAN

    override val id: String = "gan-gen2"

    private var lastSerial: Int = -1
    private var lastMoveTimestamp: Long = 0
    private var cubeTimestamp: Long = 0

    /** Build a command's raw 20-byte payload (pre-encryption). */
    override fun buildCommand(cmd: SmartCubeCommand): ByteArray? = when (cmd) {
        SmartCubeCommand.RequestFacelets -> ByteArray(20).apply { this[0] = 0x04 }
        SmartCubeCommand.RequestHardware -> ByteArray(20).apply { this[0] = 0x05 }
        SmartCubeCommand.RequestBattery -> ByteArray(20).apply { this[0] = 0x09 }
        SmartCubeCommand.RequestReset -> byteArrayOf(
            0x0A, 0x05, 0x39, 0x77, 0x00, 0x00, 0x01, 0x23,
            0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        // Gen2 has no targeted move-history retransmit; we just drop the
        // command. The orchestrator's MovesMissed → RequestFacelets path
        // is the Gen2-equivalent recovery.
        is SmartCubeCommand.RequestMoveHistory -> null
    }

    /** Decode one decrypted notification into 0..N events. */
    override suspend fun decode(packet: ByteArray, io: ProtocolIo): List<SmartCubeEvent> {
        val timestamp = currentTimeMillis()
        val msg = BitView(packet)
        return when (msg.word(0, 4).toInt()) {
            0x01 -> parseGyro(msg, timestamp)
            0x02 -> parseMove(msg, timestamp)
            0x04 -> parseFacelets(msg, timestamp)
            0x05 -> parseHardware(msg, timestamp)
            0x09 -> listOf(parseBattery(msg, timestamp))
            0x0D -> listOf(SmartCubeEvent.Disconnect)
            else -> emptyList()
        }
    }

    private fun parseGyro(v: BitView, ts: Long): List<SmartCubeEvent> {
        val qw = v.word(4, 16).toInt()
        val qx = v.word(20, 16).toInt()
        val qy = v.word(36, 16).toInt()
        val qz = v.word(52, 16).toInt()
        val vx = v.word(68, 4).toInt()
        val vy = v.word(72, 4).toInt()
        val vz = v.word(76, 4).toInt()

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

    private fun parseMove(v: BitView, ts: Long): List<SmartCubeEvent> {
        val serial = v.word(4, 8).toInt()
        // The cube's on-board buffer holds 7 most recent moves. If we've
        // missed strictly more than 7 notifications since our last sync,
        // the older ones are gone – we get the most recent 7 and a
        // signal that something earlier was lost.
        val rawDiff = if (lastSerial == -1) 1 else (serial - lastSerial) and 0xFF
        val diff = minOf(rawDiff, MOVE_HISTORY_SIZE)
        val missed = (rawDiff - MOVE_HISTORY_SIZE).coerceAtLeast(0)
        lastSerial = serial

        val events = mutableListOf<SmartCubeEvent>()
        if (diff > 0) {
            for (i in diff - 1 downTo 0) {
                val faceIdx = v.word(12 + 5 * i, 4).toInt()
                val dir = v.word(16 + 5 * i, 1).toInt()
                val elapsed = v.word(47 + 16 * i, 16).let { e ->
                    if (e == 0L) ts - lastMoveTimestamp else e
                }
                cubeTimestamp += elapsed
                val face = GAN_FACE_ORDER.getOrNull(faceIdx) ?: continue
                events += SmartCubeEvent.Move(
                    face = face,
                    cw = dir == 0,
                    cubeTimestamp = cubeTimestamp,
                    deviceTimestamp = ts,
                )
            }
            lastMoveTimestamp = ts
        }
        if (missed > 0) {
            // Tell the rest of the app: "the 7 moves above are everything
            // we know about; there may have been [missed] more that the
            // cube already overwrote." The connection orchestrator listens
            // for this and triggers a Facelets resync.
            events += SmartCubeEvent.MovesMissed(missedCount = missed, deviceTimestamp = ts)
        }
        return events
    }

    private fun parseFacelets(v: BitView, ts: Long): List<SmartCubeEvent> {
        val serial = v.word(4, 8).toInt()
        // ALWAYS update lastSerial. The Facelets state represents the cube
        // through this serial number, so subsequent Move packets must
        // diff against THIS point – not against the older lastSerial we
        // had before requesting the resync.
        lastSerial = serial

        val cp = IntArray(N_CORNERS)
        val co = IntArray(N_CORNERS)
        var cpSum = 0
        var coSum = 0
        for (i in 0 until 7) {
            cp[i] = v.word(12 + i * 3, 3).toInt()
            co[i] = v.word(33 + i * 2, 2).toInt()
            cpSum += cp[i]
            coSum += co[i]
        }
        // Last corner is determined by the parity invariants.
        cp[7] = 28 - cpSum
        co[7] = (3 - coSum % 3) % 3

        val ep = IntArray(N_EDGES)
        val eo = IntArray(N_EDGES)
        var epSum = 0
        var eoSum = 0
        for (i in 0 until 11) {
            ep[i] = v.word(47 + i * 4, 4).toInt()
            eo[i] = v.word(91 + i, 1).toInt()
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
        val hwMajor = v.word(8, 8).toInt()
        val hwMinor = v.word(16, 8).toInt()
        val swMajor = v.word(24, 8).toInt()
        val swMinor = v.word(32, 8).toInt()
        val gyroSup = v.word(104, 1) != 0L

        val nameBuilder = StringBuilder()
        repeat(8) { i ->
            nameBuilder.append(v.word(40 + i * 8, 8).toInt().toChar())
        }

        return listOf(
            SmartCubeEvent.Hardware(
                deviceTimestamp = ts,
                name = nameBuilder.toString(),
                hwVersion = "$hwMajor.$hwMinor",
                swVersion = "$swMajor.$swMinor",
                gyroSupported = gyroSup,
                vendor = CubeVendor.GAN,
            ),
        )
    }

    private fun parseBattery(v: BitView, ts: Long): SmartCubeEvent.Battery {
        val level = v.word(8, 8).toInt().coerceAtMost(100)
        return SmartCubeEvent.Battery(deviceTimestamp = ts, level = level)
    }

    private companion object {
        /**
         * The GAN cube's on-board move buffer. Every state notification
         * carries the most recent moves up to this depth, indexed by a
         * rolling serial number. If our `lastSerial` lags by more than
         * this much, the cube's already overwritten the missing ones
         * and we need a Facelets resync to recover.
         */
        const val MOVE_HISTORY_SIZE = 7
    }
}
