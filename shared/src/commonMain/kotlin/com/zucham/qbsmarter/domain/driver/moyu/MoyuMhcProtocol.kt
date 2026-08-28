package com.zucham.qbsmarter.domain.driver.moyu

import com.zucham.qbsmarter.domain.driver.CubeVendor
import com.zucham.qbsmarter.domain.driver.SmartCubeCommand
import com.zucham.qbsmarter.domain.driver.SmartCubeEvent
import com.zucham.qbsmarter.domain.driver.protocol.CubeProtocol
import com.zucham.qbsmarter.domain.driver.protocol.KOCIEMBA_FACE_ORDER
import com.zucham.qbsmarter.domain.driver.protocol.ProtocolIo
import com.zucham.qbsmarter.domain.driver.protocol.cubeFaceOf
import com.zucham.qbsmarter.util.currentTimeMillis
import kotlin.math.roundToInt

/**
 * MoYu "MHC" wire protocol — the older WeiLong AI (2021-2023), which
 * advertises as `MHC…` on service `00001000-…`. MoYu's own internal
 * name for it is "API v1"; it shares nothing but the brand with the
 * newer [MoyuWcuProtocol].
 *
 * **Plaintext.** No AES, no salt, no obfuscation of any kind — the
 * registry supplies no encryptor for this row and the driver passes
 * bytes through untouched.
 *
 * **Four characteristics, and we can bind one.** This is the shape of
 * the cube, and the reason most of this protocol is unimplemented:
 *
 *   | Characteristic | Direction | Carries                        |
 *   |----------------|-----------|--------------------------------|
 *   | `0x1001`       | write     | API v1 request                 |
 *   | `0x1002`       | notify    | API v1 response (hardware, battery) |
 *   | `0x1003`       | notify    | move stream                    |
 *   | `0x1004`       | notify    | orientation / gyro             |
 *
 * `CubeTransport` binds exactly **one** notify characteristic per
 * connection, and the registry points this protocol at `0x1003`
 * because moves are the part the app cannot work without. The
 * consequence is concrete and worth stating plainly rather than
 * papering over:
 *
 *   • **Battery and hardware info are unreachable.** They live behind
 *     the request/response pair on `0x1001`/`0x1002`. We can neither
 *     write the request nor hear the reply, so [buildCommand] returns
 *     null for everything and the [SmartCubeEvent.Hardware] below is
 *     synthesised rather than parsed.
 *   • **Gyro is unreachable.** The cube genuinely has an orientation
 *     sensor and genuinely streams it — on `0x1004`, which we are not
 *     listening to.
 *
 * TODO(multi-characteristic transport): to lift both limitations,
 * `CubeTransport` needs to bind a *set* of notify characteristics and
 * tag each inbound packet with the characteristic it arrived on, and
 * `CubeProtocolSpec` needs to name more than one `stateCharUuid`
 * (plus a way for `decode` to see the tag). That is a change to the
 * transport, the registry row shape and the [CubeProtocol] interface —
 * deliberately out of scope here, and not something to fake with a
 * second connection.
 *
 * **No initial-state sync, at all.** Nothing reachable on `0x1003`
 * reports the cube's actual sticker state, so the app can only track
 * *relatively*: it assumes whatever state it starts from and applies
 * turns on top. Physically scramble the cube while disconnected and
 * the app's model will be wrong until the user resets it by hand.
 * cstimer has exactly the same limitation with this cube, so this is a
 * property of the protocol surface rather than of our implementation —
 * though the state *is* nominally requestable on `0x1001`/`0x1002`,
 * which the TODO above would also fix.
 *
 * **Detent accumulator, not a move stream.** The cube does not report
 * "F was turned". It reports a face's rotation angle in ninths of a
 * turn, and a quarter turn is inferred when that accumulator crosses
 * the 4/5 boundary — see [decode]. This is what lets the cube report a
 * partial turn that gets pushed back without emitting a phantom move.
 *
 * Stateful: per-face rotation accumulators and the hardware-announced
 * flag. Per-connection instance, so neither needs resetting.
 */
internal class MoyuMhcProtocol : CubeProtocol {

    override val vendor: CubeVendor = CubeVendor.MOYU

    override val id: String = "moyu-mhc"

    /**
     * Per-face rotation, in ninths of a full turn, wrapped to `0..8`.
     *
     * The cube reports rotation *increments*, so this is the running
     * position of each face within its 9-step detent cycle and the only
     * thing that decides whether a record means "a quarter turn
     * happened" or "the face wobbled".
     */
    private val faceStatus = IntArray(6)

    /** Whether [SmartCubeEvent.Hardware] has already been announced. */
    private var hardwareAnnounced: Boolean = false

    // [onConnected] is deliberately not overridden. There is nothing to
    // send: the move characteristic starts notifying as soon as it is
    // subscribed, and the request characteristic that would carry a
    // handshake is not bound (see the class KDoc).

    /**
     * Every command maps to null.
     *
     * Not a protocol gap — an architectural one. The API v1 request
     * layer lives on `0x1001` with its replies on `0x1002`, and this
     * connection is bound to the move characteristic `0x1003` instead,
     * so there is no handle to write a request to and nothing that
     * could hear the answer. See the TODO in the class KDoc.
     */
    override fun buildCommand(cmd: SmartCubeCommand): ByteArray? = null

    /**
     * Decode a move packet from `0x1003`.
     *
     * Byte 0 is the record count; each of the `n` records is 6 bytes:
     * 4 bytes of timestamp, one face index, one signed rotation delta.
     * Records are oldest-first, and a single notification routinely
     * carries several of them — the cube batches whatever happened
     * since the last packet, which is how it keeps up with fast
     * turning.
     *
     * Verified against cstimer's `moyucube.js` and poliva's
     * `moyu-mhc.ts`.
     */
    override suspend fun decode(packet: ByteArray, io: ProtocolIo): List<SmartCubeEvent> {
        if (packet.isEmpty()) return emptyList()
        val recordCount = packet[0].toInt() and 0xFF
        // A short packet is a truncated or corrupt frame; decoding what
        // is there would feed the accumulators garbage they never
        // recover from, since there is no state resync to fall back on.
        if (packet.size < 1 + recordCount * RECORD_SIZE) return emptyList()

        val now = currentTimeMillis()
        val events = mutableListOf<SmartCubeEvent>()

        // A well-formed frame is the only proof we have that this is a
        // real MHC cube, so it doubles as the hardware announcement.
        if (!hardwareAnnounced) {
            hardwareAnnounced = true
            events += SmartCubeEvent.Hardware(
                deviceTimestamp = now,
                // The cube never volunteers a name, and the reply that
                // would carry one is on the unreachable 0x1002.
                name = "MoYu (MHC)",
                hwVersion = "",
                swVersion = "",
                // null, not false. false is a positive statement that
                // the hardware has no sensor (what Giiker reports); this
                // cube definitely *has* a gyro — we simply cannot reach
                // the characteristic it streams on. Saying false would
                // persist a lie all the way to the database.
                gyroSupported = null,
                vendor = CubeVendor.MOYU,
            )
        }

        for (i in 0 until recordCount) {
            val offset = 1 + i * RECORD_SIZE

            // Timestamp: two 16-bit halves, HIGH half first, each half
            // little-endian *internally*. Not a byte order any sane
            // format would choose, but it is what both reference
            // implementations read, and it is stable across firmware.
            // Long arithmetic throughout: the top bit of byte 1 lands
            // at bit 31 and would make an Int negative.
            val ticks =
                ((packet[offset + 1].toLong() and 0xFF) shl 24) or
                    ((packet[offset + 0].toLong() and 0xFF) shl 16) or
                    ((packet[offset + 3].toLong() and 0xFF) shl 8) or
                    (packet[offset + 2].toLong() and 0xFF)
            // Raw unit is 1/65536 s.
            val cubeMs = ticks * 1000L / TICKS_PER_SECOND

            val faceIndex = packet[offset + 4].toInt() and 0xFF
            // Range-check before indexing: there is no checksum on this
            // frame, so a corrupt byte must drop the record, not the
            // ingest coroutine.
            if (faceIndex !in 0..5) continue

            // Rotation delta in ninths of a turn, read as a **signed**
            // int8 — `Byte.toInt()` sign-extends, which is the whole
            // point. cstimer reads this with `getUint8`, so its
            // counter-clockwise branch (`prevRot >= 5 && curRot <= 4`)
            // can never be taken: an unsigned delta only ever increases
            // the accumulator, so cstimer's decoder is structurally
            // incapable of emitting a counter-clockwise turn. poliva's
            // signed read is the correct one and is what we follow.
            val delta = (packet[offset + 5].toInt() / DEGREES_PER_DETENT).roundToInt()

            val prevRot = faceStatus[faceIndex]
            val curRot = prevRot + delta
            // Wrap into 0..8 — Kotlin's `%` keeps the sign of the
            // dividend, hence the `+ 9` before the second modulo.
            faceStatus[faceIndex] = ((curRot % DETENTS) + DETENTS) % DETENTS

            // A quarter turn is only committed when the accumulated
            // rotation crosses the 4/5 boundary of the detent cycle.
            // Anything else is a face part-way through a turn (or being
            // pushed back), which must not produce an event.
            val cw = if (prevRot >= 5 && curRot <= 4) {
                false
            } else if (prevRot <= 4 && curRot >= 5) {
                true
            } else {
                continue
            }

            // The cube's native face order is D, L, B, R, F, U; this
            // remaps it onto [KOCIEMBA_FACE_ORDER] (U, R, F, D, L, B).
            val face = cubeFaceOf(KOCIEMBA_FACE_ORDER[MHC_FACE_ORDER[faceIndex]]) ?: continue

            events += SmartCubeEvent.Move(
                face = face,
                cw = cw,
                cubeTimestamp = cubeMs,
                deviceTimestamp = now,
            )
        }

        return events
    }

    private companion object {

        /** Bytes per move record: 4 timestamp, 1 face, 1 delta. */
        const val RECORD_SIZE = 6

        /** Timestamp resolution: the raw unit is 1/65536 of a second. */
        const val TICKS_PER_SECOND = 65536L

        /** Detent steps in one full face rotation. */
        const val DETENTS = 9

        /**
         * Degrees per detent step: 360 / 9. The wire delta is an angle
         * in degrees; dividing and rounding gives the step count.
         */
        const val DEGREES_PER_DETENT = 36.0

        /**
         * Index into [KOCIEMBA_FACE_ORDER] for each of the cube's own
         * face indices 0..5, whose native order is D, L, B, R, F, U.
         */
        val MHC_FACE_ORDER = intArrayOf(3, 4, 5, 1, 2, 0)
    }
}
