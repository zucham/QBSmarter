package com.zucham.qbsmarter.domain.driver

/**
 * Bit-level view of a smart-cube wire packet. Storing the bits as a
 * binary string is wasteful but simple, target-portable, and not on the
 * hot path (one packet per ~16 ms at most). Reads are 1..32 bits wide.
 *
 * Big-endian by default for [word]; for [wordLE] the bytes within a
 * 16/32-bit field are reversed at decode time, matching the
 * `getBitWord(..., true)` little-endian variant in the upstream
 * gan-web-bluetooth implementation. GAN Gen2 packs everything
 * big-endian; GAN Gen3 and Gen4 mix in little-endian fields for cube
 * timestamps and 16-bit serial numbers; MoYu V10 AI is mostly big-
 * endian as well but reads single 32-bit little-endian gyro components
 * outside this utility (in the MoYu driver directly, since [wordLE]
 * is unsigned and the gyro fields are signed).
 *
 * Shared across all smart-cube parsers (GAN's per-generation parsers
 * and MoYu's single-generation driver).
 */
internal class BitView(message: ByteArray) {
    private val bits: String = buildString(message.size * 8) {
        for (b in message) {
            val v = b.toInt() and 0xFF
            for (i in 7 downTo 0) append(if ((v shr i) and 1 == 1) '1' else '0')
        }
    }

    /** Big-endian unsigned word read. [bitLength] in 1..32. */
    fun word(startBit: Int, bitLength: Int): Long {
        require(bitLength in 1..32) { "bitLength must be 1..32" }
        return bits.substring(startBit, startBit + bitLength).toLong(2)
    }

    /**
     * Little-endian unsigned word read. Only valid for [bitLength] of
     * 16 or 32 – the underlying notion of byte-order doesn't apply
     * meaningfully to non-byte-aligned widths.
     *
     * Implementation: read each byte big-endian as usual, then reverse
     * the byte order to assemble the LE value.
     */
    fun wordLE(startBit: Int, bitLength: Int): Long {
        require(bitLength == 16 || bitLength == 32) {
            "wordLE only supports 16 or 32 bit widths; got $bitLength"
        }
        val byteCount = bitLength / 8
        var result = 0L
        for (i in 0 until byteCount) {
            val byteVal = bits.substring(startBit + i * 8, startBit + i * 8 + 8).toLong(2)
            // Place this byte at position `i` in little-endian order.
            result = result or (byteVal shl (8 * i))
        }
        return result
    }
}
