package com.zucham.qbsmarter.domain.driver.gan

import com.zucham.qbsmarter.domain.driver.CubeVendor
import com.zucham.qbsmarter.domain.driver.SmartCubeCommand
import com.zucham.qbsmarter.domain.driver.SmartCubeEvent
import com.zucham.qbsmarter.domain.driver.protocol.CubeProtocol
import com.zucham.qbsmarter.domain.driver.protocol.CubeProtocolRegistry
import com.zucham.qbsmarter.domain.driver.protocol.ProtocolIo

/**
 * GAN Gen1 — GAN356 i, GAN356 i Play, GAN356 i2 — registered so the app
 * can *name* the cube, deliberately inert because it cannot yet be
 * driven. Registered with `supported = false` in [CubeProtocolRegistry];
 * [buildCommand] returns null for everything and [decode] returns no
 * events.
 *
 * **Gen1 is not a variation on Gen2/3/4. It is a different machine.**
 * The three supported generations are notification-driven: enable
 * notifications on one characteristic, and the cube pushes moves,
 * facelets, battery and hardware as they happen. Gen1 pushes nothing at
 * all. It is *polled*, over four read-only characteristics that the host
 * must read on a timer:
 *
 *  | Char.  | Contents                                              |
 *  |--------|-------------------------------------------------------|
 *  | 0xFFF2 | full cube state — 48 facelets, 3 bits each            |
 *  | 0xFFF5 | move counter (u8) + the last 6 moves                  |
 *  | 0xFFF6 | inter-move time offsets — 9 × uint16 LE               |
 *  | 0xFFF7 | battery level                                         |
 *
 * Two structural facts make this incompatible with the current stack:
 *
 *  1. **No notifications.** [ProtocolIo] is a write channel and
 *     `ProtocolCubeDriver` ingests from a single notification flow.
 *     There is no path by which a protocol can ask "read 0xFFF5 now",
 *     which is the only way Gen1 produces a move.
 *  2. **Four characteristics, one binding.** `CubeTransport` binds one
 *     service with one command characteristic and one state
 *     characteristic. Gen1 needs four simultaneous read handles, and
 *     move decoding needs 0xFFF5 and 0xFFF6 read as a pair — the move
 *     counter is meaningless without the matching time offsets.
 *
 * Its encryption differs too. Gen2/3/4 derive the AES salt from the BLE
 * MAC, apply CBC with a static IV, and reduce with `% 255`. Gen1 salts
 * from the **Device Information service's System ID (0x2A23)** rather
 * than the MAC — a value that requires reading a second GATT service
 * before a single packet can be decrypted — uses **no IV**, and reduces
 * with `& 0xff`. [ganEncryptorFor] is therefore wrong for Gen1 in three
 * independent ways, which is why the registry gives Gen1 no encryptor
 * rather than the GAN one.
 *
 * Being registered-but-inert is the honest state: connecting to a
 * GAN356 i reports "recognised, not supported" instead of "unknown
 * device", and the Devices screen still labels it a GAN cube. The
 * alternative — omitting the row — would make a cube we can identify
 * perfectly look like a stranger.
 *
 * TODO: wire Gen1 up. The concrete work, in dependency order:
 *
 *  1. **A polling transport.** Extend `CubeTransport` (or add a sibling)
 *     that can bind multiple characteristics and expose an explicit
 *     read, then drive 0xFFF5 + 0xFFF6 on a ~50 ms timer and 0xFFF2 /
 *     0xFFF7 far more slowly. Everything below is dead code without it.
 *  2. **A System-ID-salted encryptor variant.** Read Device Information
 *     0x2A23, build the salt from those bytes, and run AES **without**
 *     an IV, reducing with `& 0xff` instead of `% 255`. This is a
 *     sibling of `AesCbcMacSaltEncryptor`, not a parameter on it.
 *  3. **The 0xFFF2 state layout.** 48 facelets at 3 bits each, packed
 *     into 18 bytes. The bytes arrive in swapped pairs: byte `i` of the
 *     logical layout is byte `i xor 1` on the wire, so the buffer must
 *     be un-swapped before any bit slicing. Decode into the same
 *     `CubeState` the other generations produce.
 *  4. **The 6-move 0xFFF5 encoding.** One byte per move: face is
 *     `m / 3` indexed into `GAN_FACE_ORDER`, power is `m % 3` where
 *     0 = clockwise, 1 = 180°, 2 = counter-clockwise. Note the 180°
 *     case — Gen1 is the only GAN generation that can express a half
 *     turn as a single move, and `SmartCubeEvent.Move` has no
 *     representation for it, so it must be emitted as two quarter
 *     turns (or the event type extended). Deduplicate against the
 *     leading move counter, which is what tells you how many of the
 *     six slots are new since the last poll.
 *  5. **The 0xFFF6 timing.** Nine uint16 little-endian inter-move
 *     offsets, in milliseconds, aligned to the move slots in 0xFFF5.
 *     They are the only source of a cube clock on Gen1 — there is no
 *     absolute timestamp anywhere in the protocol — so `cubeTimestamp`
 *     has to be accumulated from them.
 */
internal class GanGen1Protocol : CubeProtocol {

    override val vendor: CubeVendor = CubeVendor.GAN

    override val id: String = "gan-gen1"

    /**
     * Always null. Gen1 has no command surface at all: every
     * characteristic in the table above is read-only, and the host
     * drives the conversation by polling rather than by writing.
     */
    override fun buildCommand(cmd: SmartCubeCommand): ByteArray? = null

    /**
     * Always empty. Nothing can reach this — Gen1 sends no
     * notifications, so the driver's ingest flow stays silent for the
     * whole connection — and decoding a packet that arrived by some
     * unexpected route would be worse than ignoring it.
     */
    override suspend fun decode(packet: ByteArray, io: ProtocolIo): List<SmartCubeEvent> = emptyList()
}
