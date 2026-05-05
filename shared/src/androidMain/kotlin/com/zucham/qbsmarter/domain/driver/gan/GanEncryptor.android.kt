package com.zucham.qbsmarter.domain.driver.gan

import com.zucham.qbsmarter.domain.driver.CubeEncryptor
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Static GAN Gen2 key + IV.
// The salt (per-cube, derived from the BLE MAC) is mixed into the first 6
// bytes of both at construction time.
private val GAN_GEN2_KEY = byteArrayOf(
    0x01, 0x02, 0x42, 0x28, 0x31, 0x91.toByte(), 0x16, 0x07,
    0x20, 0x05, 0x18, 0x54, 0x42, 0x11, 0x12, 0x53,
)
private val GAN_GEN2_IV = byteArrayOf(
    0x11, 0x03, 0x32, 0x28, 0x21, 0x01, 0x76, 0x27,
    0x20, 0x95.toByte(), 0x78, 0x14, 0x32, 0x12, 0x02, 0x43,
)

actual class GanEncryptor actual constructor(salt: ByteArray) : CubeEncryptor {

    private val key: ByteArray
    private val iv: ByteArray

    init {
        require(salt.size == 6) { "Salt must be 6 bytes (48-bit) long" }
        key = GAN_GEN2_KEY.copyOf()
        iv = GAN_GEN2_IV.copyOf()
        // Mix the salt into bytes 0..5 of both. The `% 0xFF` (not 0x100)
        // is intentional – that's what the GAN Gen2 protocol specifies.
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
        // For payloads > 16 bytes, GAN encrypts a second 16-byte block at the tail.
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
