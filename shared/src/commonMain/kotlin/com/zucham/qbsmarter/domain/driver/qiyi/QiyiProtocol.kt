package com.zucham.qbsmarter.domain.driver.qiyi

import com.zakgof.korender.math.Vec3
import com.zucham.qbsmarter.domain.cube.CubeState
import com.zucham.qbsmarter.domain.cube.fromKociembaFacelets
import com.zucham.qbsmarter.domain.driver.AesEcbEncryptor
import com.zucham.qbsmarter.domain.driver.CubeEncryptor
import com.zucham.qbsmarter.domain.driver.CubeVendor
import com.zucham.qbsmarter.domain.driver.SmartCubeCommand
import com.zucham.qbsmarter.domain.driver.SmartCubeEvent
import com.zucham.qbsmarter.domain.driver.protocol.CubeIdentity
import com.zucham.qbsmarter.domain.driver.protocol.CubeProtocol
import com.zucham.qbsmarter.domain.driver.protocol.KOCIEMBA_FACE_ORDER
import com.zucham.qbsmarter.domain.driver.protocol.ProtocolIo
import com.zucham.qbsmarter.domain.driver.protocol.SOLVED_FACELETS
import com.zucham.qbsmarter.domain.driver.protocol.beInt
import com.zucham.qbsmarter.domain.driver.protocol.beInt16
import com.zucham.qbsmarter.domain.driver.protocol.crc16Modbus
import com.zucham.qbsmarter.domain.driver.protocol.cubeFaceOf
import com.zucham.qbsmarter.domain.driver.protocol.unitQuaternion
import com.zucham.qbsmarter.util.currentTimeMillis

/**
 * QiYi's AES-128 key, the same on every cube the company has shipped.
 *
 * There is no key exchange and no per-device salt — unlike GAN and MoYu,
 * which mix the BLE MAC into key and IV. QiYi's "encryption" is therefore
 * obfuscation only, which is also why [qiyiEncryptor] takes no arguments
 * and why a single instance would be safe to share (we still build one
 * per connection, for symmetry with the salted vendors).
 */
private val QIYI_AES_KEY = byteArrayOf(
    0x57, 0xB1.toByte(), 0xF9.toByte(), 0xAB.toByte(),
    0xCD.toByte(), 0x5A, 0xE8.toByte(), 0xA7.toByte(),
    0x9C.toByte(), 0xB9.toByte(), 0x8C.toByte(), 0xE7.toByte(),
    0x57, 0x8C.toByte(), 0x51, 0x08,
)

/**
 * Build the encryptor for a QiYi cube. AES-128 **ECB** over the fixed
 * [QIYI_AES_KEY], applied to every whole 16-byte block of the payload.
 *
 * ECB rather than CBC is what makes QiYi's zero-padding scheme work: the
 * frame is padded up to a block boundary before encryption, and because
 * blocks are independent the receiver can decrypt everything and then
 * simply ignore the bytes past the frame's declared length.
 *
 * Takes no [CubeIdentity] because the key is vendor-wide; the registry's
 * `createEncryptor` lambda discards its argument here.
 */
internal fun qiyiEncryptor(): CubeEncryptor = AesEcbEncryptor(QIYI_AES_KEY)

/**
 * QiYi Smart Cube / QiYi-protocol Tornado V4 wire protocol.
 *
 * **The cube says nothing until spoken to.** Every other family here
 * starts streaming the moment notifications are enabled; QiYi waits for
 * a hello frame that quotes its own MAC address back at it (see
 * [onConnected]). Get the MAC wrong, or fail to parse it, and the
 * connection looks perfectly healthy while producing zero packets
 * forever — which is precisely the failure mode to watch for when a
 * platform starts reporting MACs in a new format.
 *
 * **Every frame is a full state snapshot.** Facelets and battery ride
 * along on both the hello reply and every move notification, so the
 * protocol has no request opcodes at all — see [buildCommand]. In
 * exchange, each move notification also carries the cube's last eleven
 * moves with timestamps, which is what makes packet loss recoverable
 * without any serial-number bookkeeping.
 *
 * **Two framings share one characteristic.** State frames begin `0xFE`;
 * Tornado V4 orientation packets begin `0xCC 0x10` and are *not* wrapped
 * in the `0xFE` framing at all. The gyro check therefore has to come
 * first in [decode] — testing the `0xFE` magic before it silently drops
 * every orientation packet the Tornado sends.
 *
 * Stateful: it tracks the newest move timestamp it has already emitted,
 * the last battery level it reported, and whether it has announced the
 * hardware yet. Per-connection instance, so none of that needs resetting.
 */
internal class QiyiProtocol(private val identity: CubeIdentity) : CubeProtocol {

    override val vendor: CubeVendor = CubeVendor.QIYI

    override val id: String = "qiyi"

    /**
     * Cube-clock tick of the newest move already emitted. Everything at
     * or below this is a replay of history we have seen and must be
     * dropped, which is how the eleven-slot history buffer recovers lost
     * packets without ever duplicating a turn.
     */
    private var lastMoveTicks: Long = 0

    /**
     * Last battery level reported to the app. Battery arrives on every
     * single frame; re-emitting an unchanged value would flood the event
     * flow with noise for no benefit.
     */
    private var lastBattery: Int = -1

    /** Whether [SmartCubeEvent.Hardware] has already been announced. */
    private var hardwareAnnounced: Boolean = false

    /**
     * Send the MAC-bearing hello. Nothing arrives from the cube until
     * this lands, so a failure here is a silent dead connection rather
     * than a degraded one.
     *
     * The MAC bytes go out **reversed** — `AA:BB:CC:DD:EE:FF` is sent as
     * `FF EE DD CC BB AA`. The same reversal convention GAN uses for its
     * key salt, for the same unexplained reason.
     *
     * An unparseable MAC returns quietly. There is nothing useful to do
     * with it: the app cannot invent the address, and the connection
     * will simply never produce packets.
     */
    override suspend fun onConnected(io: ProtocolIo) {
        val mac = reversedMacBytes(identity.mac) ?: return
        io.writePlain(buildMessage(HELLO_PREFIX + mac))
    }

    /**
     * QiYi has no command surface whatsoever, so every command maps to
     * null and the driver skips the write.
     *
     * This is not a gap in the implementation. There are no request
     * opcodes in the protocol: facelets and battery are pushed on every
     * frame (making [SmartCubeCommand.RequestFacelets] and
     * [SmartCubeCommand.RequestBattery] redundant), there is no
     * hardware-info opcode at all (so [SmartCubeCommand.RequestHardware]
     * has nothing to ask for — see the synthesised event in [decode]),
     * the cube resets its own state via its physical sync gesture rather
     * than over BLE, and the eleven-slot move history makes
     * [SmartCubeCommand.RequestMoveHistory] unnecessary because the
     * backfill is unconditional.
     */
    override fun buildCommand(cmd: SmartCubeCommand): ByteArray? = null

    override suspend fun decode(packet: ByteArray, io: ProtocolIo): List<SmartCubeEvent> {
        val now = currentTimeMillis()

        // Orientation first: Tornado V4 gyro packets carry their own
        // framing and would fail the 0xFE magic test below.
        if (packet.size >= 2 && packet[0] == GYRO_MAGIC_0 && packet[1] == GYRO_MAGIC_1) {
            return decodeGyro(packet, now)
        }

        if (packet.size < 3) return emptyList()
        if (packet[0] != FRAME_MAGIC) return emptyList()

        // The declared length counts magic + length + content + CRC but
        // *not* the AES zero padding, so it is the only way to tell real
        // bytes from padding after decryption.
        val declaredLen = packet[1].toInt() and 0xFF
        if (declaredLen < MIN_FRAME_LEN || declaredLen > packet.size) return emptyList()
        // Residue trick: CRC-16/MODBUS across a frame that already
        // carries its own little-endian CRC comes out zero.
        if (crc16Modbus(packet, 0, declaredLen) != 0) return emptyList()

        // Every real frame carries an opcode and a timestamp; anything
        // shorter is a truncation we decline rather than index past.
        if (declaredLen < HEADER_LEN || packet.size < HEADER_LEN) return emptyList()

        val opcode = packet[2].toInt() and 0xFF
        // Cube uptime in 0.625 ms ticks, read as a Long. cstimer builds
        // this with `<<24` and goes negative once the cube has been
        // awake for ~15.5 days, which turns move ordering inside out.
        val frameTicks = packet.beInt(TIMESTAMP_OFFSET, 4)

        // Acknowledge before decoding: the cube stops streaming until it
        // is acked, so a decode that bails early must not also cost us
        // the ack.
        if (shouldAck(opcode, packet, declaredLen)) {
            io.writePlain(buildMessage(packet.copyOfRange(2, 7)))
        }

        val body = when (opcode) {
            OP_HELLO -> decodeHello(packet, declaredLen, frameTicks, now)
            OP_STATE_CHANGE -> decodeStateChange(packet, declaredLen, frameTicks, now)
            OP_SYNC_CONFIRM -> decodeSyncConfirm(declaredLen, now)
            else -> return emptyList()
        }

        // The cube never identifies itself, so synthesise the Hardware
        // event once we know it is talking to us at all. gyroSupported
        // is null rather than false: the plain Smart Cube has no gyro
        // and the Tornado V4 does, nothing on the wire distinguishes
        // them, and the app upgrades to true the first time an
        // orientation packet arrives. A premature false would hide the
        // orientation controls permanently.
        if (!hardwareAnnounced) {
            hardwareAnnounced = true
            return listOf(
                SmartCubeEvent.Hardware(
                    deviceTimestamp = now,
                    name = identity.name ?: "QiYi",
                    hwVersion = "",
                    swVersion = "",
                    gyroSupported = null,
                    vendor = CubeVendor.QIYI,
                ),
            ) + body
        }
        return body
    }

    // -- Opcodes ----------------------------------------------------------

    /**
     * Opcode 0x02 — the reply to our hello, carrying the cube's current
     * state. Always acked.
     *
     * Also the point where [lastMoveTicks] is anchored to the cube's
     * current clock. The eleven history slots in later 0x03 frames are
     * populated from before we connected, and without an anchor the
     * first turn the user makes would replay up to eleven stale moves
     * into the solve. Anchoring costs nothing: any move genuinely made
     * after this instant necessarily has a larger tick value.
     */
    private fun decodeHello(
        packet: ByteArray,
        declaredLen: Int,
        frameTicks: Long,
        now: Long,
    ): List<SmartCubeEvent> {
        lastMoveTicks = frameTicks

        val events = mutableListOf<SmartCubeEvent>()
        faceletsEvent(packet, declaredLen, now)?.let { events += it }
        batteryEvent(packet, declaredLen, now, force = true)?.let { events += it }
        return events
    }

    /**
     * Opcode 0x03 — a turn happened. Carries the resulting facelets, the
     * move that produced them, the battery, and the cube's eleven most
     * recent timestamped moves.
     *
     * Moves are emitted before the facelets deliberately: the facelets
     * describe the state *after* the moves, so the app can animate the
     * turns and then resync to a snapshot that already agrees with them.
     */
    private fun decodeStateChange(
        packet: ByteArray,
        declaredLen: Int,
        frameTicks: Long,
        now: Long,
    ): List<SmartCubeEvent> {
        val events = mutableListOf<SmartCubeEvent>()
        events += replayMoves(packet, declaredLen, frameTicks, now)
        faceletsEvent(packet, declaredLen, now)?.let { events += it }
        batteryEvent(packet, declaredLen, now, force = false)?.let { events += it }
        return events
    }

    /**
     * Opcode 0x04 — the cube confirming its sync gesture, i.e. "I am
     * solved now, whatever you thought I was".
     *
     * The frame carries no facelet block, so the solved state is implied
     * by the opcode and its exact length. Never acked: the cube does not
     * wait for one, and an unsolicited ack confuses the firmware into
     * resending.
     */
    private fun decodeSyncConfirm(declaredLen: Int, now: Long): List<SmartCubeEvent> {
        if (declaredLen != SYNC_CONFIRM_LEN) return emptyList()
        val state = CubeState.fromKociembaFacelets(SOLVED_FACELETS) ?: return emptyList()
        return listOf(SmartCubeEvent.Facelets(state = state, deviceTimestamp = now))
    }

    /**
     * Opcode 0x02 is always acked. Opcode 0x03 is acked only when the
     * cube asks, via a flag byte sitting immediately after the move
     * history — cstimer acks every 0x03 unconditionally, which works but
     * doubles the write traffic during a speedsolve for no reason.
     * Opcode 0x04 is never acked.
     */
    private fun shouldAck(opcode: Int, packet: ByteArray, declaredLen: Int): Boolean = when (opcode) {
        OP_HELLO -> declaredLen > ACK_MIN_LEN
        OP_STATE_CHANGE ->
            declaredLen > ACK_FLAG_OFFSET && packet[ACK_FLAG_OFFSET].toInt() != 0
        else -> false
    }

    // -- Payload fields ---------------------------------------------------

    /**
     * Decode the 27-byte facelet block into a [SmartCubeEvent.Facelets],
     * or null when it is absent or does not describe a reachable cube.
     *
     * A null return is not an error worth propagating. A half-scrambled
     * snapshot from a cube mid-turn, or a stray colour code, would
     * otherwise take down the whole notification; skipping the event
     * leaves the app on its last good state until the next frame lands
     * a millisecond later.
     */
    private fun faceletsEvent(
        packet: ByteArray,
        declaredLen: Int,
        now: Long,
    ): SmartCubeEvent.Facelets? {
        if (declaredLen < FACELET_OFFSET + FACELET_BYTES) return null
        val state = decodeFacelets(packet) ?: return null
        return SmartCubeEvent.Facelets(state = state, deviceTimestamp = now)
    }

    /**
     * 54 facelets packed two per byte.
     *
     * The nibble order is the trap: facelet `i` lives in the **low**
     * nibble for even `i` and the **high** nibble for odd `i`, i.e. the
     * pair is stored little-endian within the byte. Reading it the other
     * way round yields a string that still passes a length check and
     * still contains plausible letters, so the mistake surfaces only as
     * "the cube is always in an impossible state".
     *
     * The cube's facelet indices are already in Kociemba URFDLB order,
     * so no re-ordering is needed — only the colour alphabet differs:
     * QiYi numbers its colours [QIYI_COLOUR_FACES] (L R D U F B), not
     * URFDLB.
     */
    private fun decodeFacelets(packet: ByteArray): CubeState? {
        val out = CharArray(54)
        for (i in 0 until 54) {
            val byte = packet[FACELET_OFFSET + (i shr 1)].toInt() and 0xFF
            val nibble = if (i and 1 == 0) byte and 0x0F else (byte shr 4) and 0x0F
            if (nibble >= QIYI_COLOUR_FACES.length) return null
            out[i] = QIYI_COLOUR_FACES[nibble]
        }
        return CubeState.fromKociembaFacelets(out.concatToString())
    }

    /** Battery percentage, emitted on change only unless [force]. */
    private fun batteryEvent(
        packet: ByteArray,
        declaredLen: Int,
        now: Long,
        force: Boolean,
    ): SmartCubeEvent.Battery? {
        if (declaredLen <= BATTERY_OFFSET) return null
        val level = packet[BATTERY_OFFSET].toInt() and 0xFF
        if (!force && level == lastBattery) return null
        lastBattery = level
        return SmartCubeEvent.Battery(deviceTimestamp = now, level = level)
    }

    // -- Moves ------------------------------------------------------------

    /**
     * Reconstruct the move sequence from the current move plus the
     * cube's eleven-slot history, dropping anything already emitted.
     *
     * This is the whole of QiYi's packet-loss recovery. Every 0x03 frame
     * repeats the last eleven turns with their timestamps, so a dropped
     * notification costs nothing as long as the user does not make
     * twelve moves inside one lost packet. There is no serial number to
     * diff and no retransmit to request — the timestamps are the
     * ordering and [lastMoveTicks] is the watermark.
     *
     * Sorting is not decorative: the history slots are a ring buffer, so
     * they arrive rotated by however many moves have been made, and the
     * newest move is in the current-move field rather than the history.
     */
    private fun replayMoves(
        packet: ByteArray,
        declaredLen: Int,
        frameTicks: Long,
        now: Long,
    ): List<SmartCubeEvent> {
        val candidates = mutableListOf<Pair<Long, Int>>()

        if (declaredLen > CURRENT_MOVE_OFFSET) {
            candidates += frameTicks to (packet[CURRENT_MOVE_OFFSET].toInt() and 0xFF)
        }
        for (slot in 0 until HISTORY_SLOTS) {
            val offset = HISTORY_OFFSET + slot * HISTORY_SLOT_BYTES
            if (offset + HISTORY_SLOT_BYTES > declaredLen) break
            // An unused slot is all-0xFF, not all-zero — a zero
            // timestamp would be indistinguishable from a move made in
            // the cube's first tick of uptime.
            if (isEmptySlot(packet, offset)) continue
            candidates += packet.beInt(offset, 4) to (packet[offset + 4].toInt() and 0xFF)
        }

        val fresh = candidates
            .filter { (ticks, code) -> ticks > lastMoveTicks && code in 1..12 }
            .sortedBy { it.first }
        if (fresh.isEmpty()) return emptyList()

        val events = mutableListOf<SmartCubeEvent>()
        for ((ticks, code) in fresh) {
            val face = cubeFaceOf(KOCIEMBA_FACE_ORDER[MOVE_CODE_TO_FACE[(code - 1) shr 1]]) ?: continue
            events += SmartCubeEvent.Move(
                face = face,
                // Even codes are clockwise, odd codes are
                // counter-clockwise. Backwards from the usual
                // convention, and worth stating explicitly because a
                // sign error here produces a cube that mirrors every
                // turn rather than an obvious crash.
                cw = (code and 1) == 0,
                cubeTimestamp = ticksToMillis(ticks),
                deviceTimestamp = now,
            )
        }
        lastMoveTicks = fresh.last().first
        return events
    }

    private fun isEmptySlot(packet: ByteArray, offset: Int): Boolean {
        for (i in 0 until HISTORY_SLOT_BYTES) {
            if (packet[offset + i] != EMPTY_SLOT_BYTE) return false
        }
        return true
    }

    // -- Orientation ------------------------------------------------------

    /**
     * Tornado V4 orientation packet: a fixed 16 bytes, unframed, with
     * its own CRC. The plain QiYi Smart Cube never sends one.
     *
     * The axis remap `(x, -z, y)` converts the cube's own frame into
     * ours. The raw int16 components are fed to [unitQuaternion] as-is:
     * the nominal scale is 1000, the measured magnitude is about 1002.6,
     * and normalising by the real length makes the constant irrelevant.
     *
     * QiYi reports no angular velocity at all, so [Vec3.ZERO] is the
     * honest answer rather than a placeholder.
     */
    private fun decodeGyro(packet: ByteArray, now: Long): List<SmartCubeEvent> {
        if (packet.size < GYRO_PACKET_LEN) return emptyList()
        if (crc16Modbus(packet, 0, GYRO_PACKET_LEN) != 0) return emptyList()

        val x = packet.beInt16(GYRO_X_OFFSET)
        val y = packet.beInt16(GYRO_X_OFFSET + 2)
        val z = packet.beInt16(GYRO_X_OFFSET + 4)
        val w = packet.beInt16(GYRO_X_OFFSET + 6)

        return listOf(
            SmartCubeEvent.Gyro(
                quat = unitQuaternion(
                    w = w.toFloat(),
                    x = x.toFloat(),
                    y = -z.toFloat(),
                    z = y.toFloat(),
                ),
                angularVel = Vec3.ZERO,
                deviceTimestamp = now,
            ),
        )
    }

    // -- Framing ----------------------------------------------------------

    /**
     * Wrap [content] in QiYi's outer frame and pad it for AES.
     *
     * Layout is `FE | len | content | crcLo | crcHi`, where `len` counts
     * everything except the padding — magic, itself, content and the two
     * CRC bytes. The CRC is CRC-16/MODBUS stored **little-endian**,
     * which is what lets the receiver verify with the residue trick
     * instead of slicing the trailer off.
     *
     * The zero padding to a 16-byte boundary exists purely because
     * AES-ECB cannot encrypt a partial block; the cube ignores it, as
     * do we, on the strength of the declared length.
     */
    private fun buildMessage(content: ByteArray): ByteArray {
        val length = 4 + content.size
        val frame = ByteArray(length)
        frame[0] = FRAME_MAGIC
        frame[1] = length.toByte()
        content.copyInto(frame, 2)
        val crc = crc16Modbus(frame, 0, length - 2)
        frame[length - 2] = (crc and 0xFF).toByte()
        frame[length - 1] = ((crc shr 8) and 0xFF).toByte()

        val padded = ByteArray((length + 15) / 16 * 16)
        frame.copyInto(padded)
        return padded
    }

    /**
     * Parse a BLE MAC into its six bytes, reversed, or null if it is not
     * a MAC at all.
     *
     * Separator-tolerant on purpose: platforms report `AA:BB:CC:DD:EE:FF`,
     * `AA-BB-CC-DD-EE-FF` and bare `AABBCCDDEEFF`, and a cube that never
     * answers is far too quiet a way to discover that the format changed.
     */
    private fun reversedMacBytes(mac: String): ByteArray? {
        val hex = mac.filterNot { it == ':' || it == '-' }
        if (hex.length != 12) return null
        val out = ByteArray(6)
        for (i in 0 until 6) {
            val value = hex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            out[5 - i] = value.toByte()
        }
        return out
    }

    private companion object {

        /** Start byte of a state frame. */
        val FRAME_MAGIC: Byte = 0xFE.toByte()

        /** First two bytes of a Tornado V4 orientation packet. */
        val GYRO_MAGIC_0: Byte = 0xCC.toByte()
        val GYRO_MAGIC_1: Byte = 0x10.toByte()

        /** Magic + length + opcode: the smallest thing that can be a frame. */
        const val MIN_FRAME_LEN = 3

        /** Magic + length + opcode + 4 timestamp bytes. */
        const val HEADER_LEN = 7

        /** Frames shorter than this have no timestamp to echo in an ack. */
        const val ACK_MIN_LEN = 6

        const val TIMESTAMP_OFFSET = 3
        const val FACELET_OFFSET = 7
        const val FACELET_BYTES = 27
        const val CURRENT_MOVE_OFFSET = 34
        const val BATTERY_OFFSET = 35

        /** Eleven 5-byte slots: `uint32 BE timestamp | move code`. */
        const val HISTORY_OFFSET = 36
        const val HISTORY_SLOTS = 11
        const val HISTORY_SLOT_BYTES = 5
        val EMPTY_SLOT_BYTE: Byte = 0xFF.toByte()

        /** Non-zero here means "this 0x03 frame wants an ack". */
        const val ACK_FLAG_OFFSET = 91

        const val OP_HELLO = 0x02
        const val OP_STATE_CHANGE = 0x03
        const val OP_SYNC_CONFIRM = 0x04

        /** A 0x04 frame is only a sync confirmation at exactly this length. */
        const val SYNC_CONFIRM_LEN = 38

        const val GYRO_PACKET_LEN = 16
        const val GYRO_X_OFFSET = 6

        /**
         * QiYi's colour numbering, as Kociemba face letters: 0=L, 1=R,
         * 2=D, 3=U, 4=F, 5=B. Not URFDLB — indexing this with a colour
         * code and indexing [KOCIEMBA_FACE_ORDER] with it are two
         * different operations.
         */
        const val QIYI_COLOUR_FACES = "LRDUFB"

        /**
         * Move code (1..12) to an index into [KOCIEMBA_FACE_ORDER].
         * Codes come in clockwise/counter-clockwise pairs, so the face
         * is `(code - 1) shr 1`, in QiYi's own L R D U F B axis order.
         */
        val MOVE_CODE_TO_FACE = intArrayOf(4, 1, 3, 0, 2, 5)

        /**
         * The cube's hello frame, minus the trailing reversed MAC. Its
         * meaning beyond "0x00 0x6B is the hello opcode pair" is
         * undocumented; the byte string is reproduced verbatim from
         * working captures and must not be tidied up.
         */
        val HELLO_PREFIX = byteArrayOf(
            0x00, 0x6B, 0x01, 0x00, 0x00, 0x22, 0x06, 0x00, 0x02, 0x08, 0x00,
        )

        /**
         * Cube ticks are 0.625 ms, so milliseconds are `ticks * 5 / 8`.
         * Kept in Long arithmetic: a cube left awake for weeks overflows
         * an Int here long before the user notices anything else wrong.
         */
        fun ticksToMillis(ticks: Long): Long = ticks * 5 / 8
    }
}
