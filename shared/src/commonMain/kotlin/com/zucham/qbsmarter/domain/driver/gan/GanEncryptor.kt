package com.zucham.qbsmarter.domain.driver.gan

import com.zucham.qbsmarter.domain.driver.CubeEncryptor

/**
 * GAN Gen2 cube encryptor. The 16-byte AES key and IV are static (reverse-
 * engineered from the official Gan i Carry app); each cube derives a 6-byte
 * salt from its MAC address that's mixed into the first 6 bytes of both.
 * GAN Gen3 and Gen4 both use this same old encryptor.
 *
 * `expect class` because the AES-CBC implementation is platform-specific:
 * Android uses javax.crypto, iOS uses CommonCrypto, etc. Only the Android
 * actual is real; other platforms throw NotImplementedError.
 */
expect class GanEncryptor(salt: ByteArray) : CubeEncryptor {
    override fun encrypt(data: ByteArray): ByteArray
    override fun decrypt(data: ByteArray): ByteArray
}

/**
 * Build the per-cube salt from its BLE MAC address. The bytes are
 * reversed because that's what the GAN Gen2 protocol expects.
 */
fun ganSaltFromMac(mac: String): ByteArray =
    mac.split(':')
        .map { it.toInt(16).toByte() }
        .toByteArray()
        .reversedArray()
