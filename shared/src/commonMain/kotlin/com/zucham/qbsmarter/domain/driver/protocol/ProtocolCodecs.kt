package com.zucham.qbsmarter.domain.driver.protocol

import com.zakgof.korender.math.Quaternion
import com.zakgof.korender.math.Vec3
import com.zucham.qbsmarter.domain.cube.CubeFace
import kotlin.math.sqrt

/**
 * Small decoding primitives shared across cube protocols.
 *
 * Everything here is vendor-neutral by construction: a checksum, a
 * couple of number formats, a quaternion normaliser. Vendor-specific
 * *choices* (which offsets, which axis order) stay in the protocol that
 * makes them.
 */

// -- Integrity ------------------------------------------------------------

/**
 * CRC-16/MODBUS: init `0xFFFF`, reflected polynomial `0xA001`, no final
 * XOR. Used by QiYi on both its outer frames and its orientation packets.
 *
 * Verification uses the residue trick rather than a comparison: running
 * the CRC across a frame that already carries its own little-endian CRC
 * yields zero. [crc16Modbus] over the whole frame `== 0` therefore means
 * "intact", which avoids having to slice the trailer off first.
 */
fun crc16Modbus(data: ByteArray, from: Int = 0, until: Int = data.size): Int {
    var crc = 0xFFFF
    for (i in from until until) {
        crc = crc xor (data[i].toInt() and 0xFF)
        repeat(8) {
            crc = if (crc and 1 != 0) (crc shr 1) xor 0xA001 else crc shr 1
        }
    }
    return crc and 0xFFFF
}

// -- Number formats -------------------------------------------------------

/** Unsigned big-endian integer of [length] bytes starting at [offset]. */
fun ByteArray.beInt(offset: Int, length: Int): Long {
    var v = 0L
    for (i in 0 until length) v = (v shl 8) or (this[offset + i].toLong() and 0xFF)
    return v
}

/** Signed little-endian 32-bit integer. */
fun ByteArray.leInt32(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

/** Signed big-endian 16-bit integer. */
fun ByteArray.beInt16(offset: Int): Int {
    val v = ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)
    return if (v >= 0x8000) v - 0x10000 else v
}

/** IEEE-754 little-endian float32. */
fun ByteArray.leFloat32(offset: Int): Float = Float.fromBits(leInt32(offset))

/**
 * Decode a GAN sign-magnitude field: the top bit is the sign and the
 * remaining bits are the magnitude, scaled to `[-1, 1]`.
 *
 * Note this is **not** two's complement — reading these fields as signed
 * integers produces plausible-looking garbage rather than an obvious
 * failure, which makes the mistake expensive to spot.
 */
fun ganSignMagnitude(value: Int, bits: Int): Float {
    val sign = 1 - (value shr (bits - 1)) * 2
    val magnitude = value and ((1 shl (bits - 1)) - 1)
    val denominator = (1 shl (bits - 1)) - 1
    return sign * magnitude.toFloat() / denominator
}

// -- Orientation ----------------------------------------------------------

/**
 * Build a unit quaternion, normalising by the measured magnitude rather
 * than trusting the vendor's nominal scale factor.
 *
 * This matters in practice. GoCube's documented scale is 2^14 = 16384,
 * but real firmware emits vectors of magnitude ≈16355, so dividing by
 * the documented constant leaves a quaternion 0.17% short of unit
 * length; QiYi's nominal 1000 is really closer to 1002.6. Those errors
 * compound through composition. Dividing by the actual magnitude is both
 * exact and immune to the vendor changing the constant.
 *
 * Returns identity for a zero-length input, which is what a
 * still-initialising sensor sends.
 */
fun unitQuaternion(w: Float, x: Float, y: Float, z: Float): Quaternion {
    val length = sqrt(w * w + x * x + y * y + z * z)
    if (length < 1e-6f) return Quaternion.IDENTITY
    return Quaternion(w / length, Vec3(x / length, y / length, z / length))
}

// -- Facelets -------------------------------------------------------------

/**
 * Face order used by Kociemba facelet strings, and the order every
 * protocol here converts *into*: U, R, F, D, L, B.
 */
const val KOCIEMBA_FACE_ORDER = "URFDLB"

/** Solved-state facelet string, useful as a protocol-level sanity check. */
const val SOLVED_FACELETS =
    "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB"

/**
 * GAN's face index order, `U R F D L B`, matching
 * [KOCIEMBA_FACE_ORDER]. Shared by every GAN generation: Gen2 encodes
 * the face as a direct 3-bit index into this list, Gen3/Gen4 as a
 * one-hot field that resolves to the same index.
 */
val GAN_FACE_ORDER: List<CubeFace> = listOf(
    CubeFace.U, CubeFace.R, CubeFace.F, CubeFace.D, CubeFace.L, CubeFace.B,
)

/**
 * Map a face letter from [KOCIEMBA_FACE_ORDER] to a [CubeFace], or null
 * if it isn't one. Protocols that report faces as characters (rather
 * than indices) funnel through here.
 */
fun cubeFaceOf(letter: Char): CubeFace? = when (letter) {
    'U' -> CubeFace.U
    'R' -> CubeFace.R
    'F' -> CubeFace.F
    'D' -> CubeFace.D
    'L' -> CubeFace.L
    'B' -> CubeFace.B
    else -> null
}
