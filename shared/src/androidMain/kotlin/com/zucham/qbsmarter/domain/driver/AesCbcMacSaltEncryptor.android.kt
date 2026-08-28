package com.zucham.qbsmarter.domain.driver

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android actual for [AesCbcMacSaltEncryptor]. Uses `javax.crypto` with
 * `AES/CBC/NoPadding`. The salt-mixing rule, two-block tail encryption,
 * and `% 0xFF` (not `0x100`) modulus are all part of the wire protocol
 * spec inherited from GAN Gen2; MoYu V10 AI reuses the same scheme so
 * the implementation lives in [com.zucham.qbsmarter.domain.driver]
 * unchanged at the byte-mangling layer – only the constants differ
 * between vendors.
 */
actual class AesCbcMacSaltEncryptor actual constructor(
    rootKey: ByteArray,
    rootIv: ByteArray,
    salt: ByteArray,
) : CubeEncryptor {

    private val key: ByteArray
    private val iv: ByteArray

    init {
        require(rootKey.size == 16) { "Root key must be 16 bytes; got ${rootKey.size}" }
        require(rootIv.size == 16) { "Root IV must be 16 bytes; got ${rootIv.size}" }
        require(salt.size == 6) { "Salt must be 6 bytes (48-bit) long; got ${salt.size}" }
        key = rootKey.copyOf()
        iv = rootIv.copyOf()
        // Mix the salt into bytes 0..5 of both. The `% 0xFF` (not 0x100)
        // is intentional – that's what the GAN/MoYu protocols specify.
        for (i in 0 until 6) {
            val k = (key[i].toInt() and 0xFF) + (salt[i].toInt() and 0xFF)
            val v = (iv[i].toInt() and 0xFF) + (salt[i].toInt() and 0xFF)
            key[i] = (k % 0xFF).toByte()
            iv[i] = (v % 0xFF).toByte()
        }
    }

    actual override fun encrypt(data: ByteArray): ByteArray {
        require(data.size >= 16) { "Data must be ≥ 16 bytes" }
        val res = data.copyOf()
        encryptChunk(res, 0)
        // For payloads > 16 bytes, encrypt a second 16-byte block at the tail.
        if (res.size > 16) encryptChunk(res, res.size - 16)
        return res
    }

    actual override fun decrypt(data: ByteArray): ByteArray {
        require(data.size >= 16) { "Data must be ≥ 16 bytes" }
        val res = data.copyOf()
        if (res.size > 16) decryptChunk(res, res.size - 16)
        decryptChunk(res, 0)
        return res
    }

    private fun encryptChunk(buffer: ByteArray, offset: Int) {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val chunk = cipher.doFinal(buffer, offset, 16)
        System.arraycopy(chunk, 0, buffer, offset, 16)
    }

    private fun decryptChunk(buffer: ByteArray, offset: Int) {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val chunk = cipher.doFinal(buffer, offset, 16)
        System.arraycopy(chunk, 0, buffer, offset, 16)
    }
}
