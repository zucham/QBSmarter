package com.zucham.qbsmarter.domain.driver.moyu

import com.zucham.qbsmarter.domain.driver.AesCbcMacSaltEncryptor
import com.zucham.qbsmarter.domain.driver.CubeEncryptor
import com.zucham.qbsmarter.domain.driver.macSaltFromMacAddress

/**
 * Per-cube encryptor for MoYu WeiLong V10 AI smart cubes. AES-128 CBC
 * using the same scheme as GAN cubes – static root key + IV mixed with
 * a 6-byte salt derived from the cube's MAC – but with a different
 * root key and IV.
 *
 * Constants sourced from the WeiLong V10 AI protocol writeup
 * (https://github.com/lukeburong/weilong-v10-ai-protocol):
 *
 *   • Root key: 15773A5C670E2D1F17672A139B675257
 *   • Root IV:  11232625862A2C3B55067F317E672157
 *
 * Thin wrapper over the vendor-agnostic [AesCbcMacSaltEncryptor]; the
 * GAN companion is [com.zucham.qbsmarter.domain.driver.gan.GanEncryptor].
 */
class MoyuEncryptor(salt: ByteArray) : CubeEncryptor {

    private val delegate = AesCbcMacSaltEncryptor(MOYU_ROOT_KEY, MOYU_ROOT_IV, salt)

    override fun encrypt(data: ByteArray): ByteArray = delegate.encrypt(data)
    override fun decrypt(data: ByteArray): ByteArray = delegate.decrypt(data)

    private companion object {
        private val MOYU_ROOT_KEY = byteArrayOf(
            0x15, 0x77, 0x3A, 0x5C, 0x67, 0x0E, 0x2D, 0x1F,
            0x17, 0x67, 0x2A, 0x13, 0x9B.toByte(), 0x67, 0x52, 0x57,
        )
        private val MOYU_ROOT_IV = byteArrayOf(
            0x11, 0x23, 0x26, 0x25, 0x86.toByte(), 0x2A, 0x2C, 0x3B,
            0x55, 0x06, 0x7F, 0x31, 0x7E, 0x67, 0x21, 0x57,
        )
    }
}

/**
 * Build the per-cube salt from a MoYu cube's BLE MAC address. MoYu V10
 * inherits the GAN convention of reversing the MAC bytes, so this
 * delegates to the vendor-agnostic [macSaltFromMacAddress].
 *
 * Kept as a named helper for symmetry with [ganSaltFromMac]; readers
 * grepping for the cube vendor find the construction site.
 */
fun moyuSaltFromMac(mac: String): ByteArray = macSaltFromMacAddress(mac)
