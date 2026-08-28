package com.zucham.qbsmarter.domain.driver

/**
 * AES-128 CBC encryptor parameterised on a root key, a root IV, and a
 * per-device 6-byte salt. Used by both GAN Gen2+ cubes and MoYu V10 AI
 * cubes; the two share the *same* AES-128-CBC-no-padding scheme but
 * differ in their root key and IV byte arrays.
 *
 * The salt is mixed into the first 6 bytes of both key and IV via
 * `(byte + salt) % 0xFF` (yes, `0xFF`, not `0x100` – that's what the
 * GAN protocol specifies, and MoYu inherited the same quirk). For payloads
 * larger than 16 bytes, two blocks are encrypted: one at offset 0 and one
 * at offset `size - 16`. Decryption reverses the order.
 *
 * `expect class` because the AES-CBC implementation is platform-specific:
 * Android uses javax.crypto, iOS would use CommonCrypto. Only the Android
 * actual is real; JVM-desktop and Web actuals throw `NotImplementedError`.
 *
 * Per-cube instances are constructed in
 * [com.zucham.qbsmarter.data.ble.ConnectionOrchestrator] at connect time
 * from the cube's MAC. They are never cached across cubes – different MAC
 * means different salt means different effective key/IV.
 *
 * @param rootKey 16-byte root key. Vendor-specific, hard-coded.
 * @param rootIv 16-byte root IV. Vendor-specific, hard-coded.
 * @param salt 6-byte per-cube salt, derived from the BLE MAC via
 *   [macSaltFromMacAddress].
 */
expect class AesCbcMacSaltEncryptor(
    rootKey: ByteArray,
    rootIv: ByteArray,
    salt: ByteArray,
) : CubeEncryptor {
    override fun encrypt(data: ByteArray): ByteArray
    override fun decrypt(data: ByteArray): ByteArray
}

/**
 * Per-cube salt from a BLE MAC address. Both GAN and MoYu cubes follow
 * the same scheme: parse the colon-separated hex MAC as 6 bytes and
 * reverse them. The reversed-bytes choice originates with GAN; MoYu's
 * spec deliberately re-uses it.
 */
fun macSaltFromMacAddress(mac: String): ByteArray =
    mac.split(':')
        .map { it.toInt(16).toByte() }
        .toByteArray()
        .reversedArray()
