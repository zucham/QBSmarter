package com.zucham.qbsmarter.domain.driver.gan

import com.zucham.qbsmarter.domain.driver.AesCbcMacSaltEncryptor
import com.zucham.qbsmarter.domain.driver.CubeEncryptor
import com.zucham.qbsmarter.domain.driver.macSaltFromMacAddress

/**
 * Per-cube encryptor for GAN smart cubes. AES-128 CBC with a static root
 * key + IV, mixed with a 6-byte salt derived from the cube's MAC. All
 * three currently-supported GAN protocol generations (Gen2, Gen3, Gen4)
 * share the same key/IV/salt scheme – only the wire packet formats and
 * BLE service UUIDs differ.
 *
 * Thin wrapper over the vendor-agnostic [AesCbcMacSaltEncryptor]; MoYu's
 * [com.zucham.qbsmarter.domain.driver.moyu.MoyuEncryptor] is the
 * companion using a different root key/IV.
 *
 * Constants are reverse-engineered from the official Gan i Carry app
 * disassembly and verified across the gan-web-bluetooth project.
 */
class GanEncryptor(salt: ByteArray) : CubeEncryptor {

    private val delegate = AesCbcMacSaltEncryptor(GAN_ROOT_KEY, GAN_ROOT_IV, salt)

    override fun encrypt(data: ByteArray): ByteArray = delegate.encrypt(data)
    override fun decrypt(data: ByteArray): ByteArray = delegate.decrypt(data)

    private companion object {
        private val GAN_ROOT_KEY = byteArrayOf(
            0x01, 0x02, 0x42, 0x28, 0x31, 0x91.toByte(), 0x16, 0x07,
            0x20, 0x05, 0x18, 0x54, 0x42, 0x11, 0x12, 0x53,
        )
        private val GAN_ROOT_IV = byteArrayOf(
            0x11, 0x03, 0x32, 0x28, 0x21, 0x01, 0x76, 0x27,
            0x20, 0x95.toByte(), 0x78, 0x14, 0x32, 0x12, 0x02, 0x43,
        )
    }
}

/**
 * Build the per-cube salt from its BLE MAC address. The bytes are
 * reversed because that's what the GAN Gen2 protocol expects (and MoYu
 * inherited the same convention).
 *
 * Preserved at the original symbol for source compatibility; delegates
 * to the vendor-agnostic helper.
 */
fun ganSaltFromMac(mac: String): ByteArray = macSaltFromMacAddress(mac)
