package com.zucham.qbsmarter.util

private const val HEX_CHARS = "0123456789abcdef"

/** Lower-case hex string with no separators. Used for BLE packet logging. */
fun ByteArray.toHexString(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_CHARS[v ushr 4])
        sb.append(HEX_CHARS[v and 0x0F])
    }
    return sb.toString()
}
