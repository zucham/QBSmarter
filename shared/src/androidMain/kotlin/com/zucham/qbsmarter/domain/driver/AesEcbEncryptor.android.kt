package com.zucham.qbsmarter.domain.driver

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Android actual for [AesEcbEncryptor]. Uses `javax.crypto` with
 * `AES/ECB/NoPadding`.
 *
 * ECB is stateless between blocks, so a single [Cipher] instance is
 * initialised once per call and reused across every block of the
 * buffer — unlike the CBC encryptor, which must re-init before each
 * chunk to reset the IV.
 *
 * Whole blocks only: a buffer of, say, 20 bytes has bytes 0..15
 * transformed and bytes 16..19 passed through unchanged. See the
 * `expect` declaration for why that is preferable to throwing.
 */
actual class AesEcbEncryptor actual constructor(key: ByteArray) : CubeEncryptor {

    private val keySpec: SecretKeySpec

    init {
        require(key.size == 16) { "AES-128 key must be 16 bytes; got ${key.size}" }
        // Defensive copy: the caller's array is usually a shared vendor
        // constant, and SecretKeySpec does copy, but the intent should
        // be visible at the call site of a long-lived object.
        keySpec = SecretKeySpec(key.copyOf(), "AES")
    }

    actual override fun encrypt(data: ByteArray): ByteArray = transform(data, Cipher.ENCRYPT_MODE)

    actual override fun decrypt(data: ByteArray): ByteArray = transform(data, Cipher.DECRYPT_MODE)

    private fun transform(data: ByteArray, mode: Int): ByteArray {
        val res = data.copyOf()
        val blocks = res.size / 16
        if (blocks == 0) return res
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(mode, keySpec)
        for (block in 0 until blocks) {
            val offset = block * 16
            val chunk = cipher.doFinal(res, offset, 16)
            System.arraycopy(chunk, 0, res, offset, 16)
        }
        return res
    }
}
