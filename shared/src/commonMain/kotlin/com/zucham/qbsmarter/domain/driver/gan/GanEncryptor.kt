package com.zucham.qbsmarter.domain.driver.gan

import com.zucham.qbsmarter.domain.driver.AesCbcMacSaltEncryptor
import com.zucham.qbsmarter.domain.driver.CubeEncryptor
import com.zucham.qbsmarter.domain.driver.macSaltFromMacAddress
import com.zucham.qbsmarter.domain.driver.protocol.CubeIdentity

/**
 * A GAN static key/IV pair, before the per-cube salt is mixed in.
 *
 * Two exist in the wild, and which one a cube uses is not carried in any
 * protocol field — it has to be inferred from the advertised name,
 * because the key must be chosen before the very first packet can be
 * decrypted. Both reference implementations do exactly this.
 */
enum class GanKeySet(val rootKey: ByteArray, val rootIv: ByteArray) {

    /**
     * Every genuine GAN cube, across Gen2, Gen3 and Gen4. The
     * generations differ in packet layout and BLE UUIDs but share this
     * key material exactly.
     */
    STANDARD(
        rootKey = byteArrayOf(
            0x01, 0x02, 0x42, 0x28, 0x31, 0x91.toByte(), 0x16, 0x07,
            0x20, 0x05, 0x18, 0x54, 0x42, 0x11, 0x12, 0x53,
        ),
        rootIv = byteArrayOf(
            0x11, 0x03, 0x32, 0x28, 0x21, 0x01, 0x76, 0x27,
            0x20, 0x95.toByte(), 0x78, 0x14, 0x32, 0x12, 0x02, 0x43,
        ),
    ),

    /**
     * The MoYu AI 2023, which speaks the GAN Gen2 protocol verbatim but
     * with its own key material — a MoYu cube on GAN's wire format, and
     * the reason [CubeIdentity.name] has to reach the encryptor at all.
     *
     * Getting this wrong is quiet rather than loud: the connection
     * succeeds, the handshake writes go out, and every reply decrypts to
     * noise. The cube pairs and then does nothing.
     */
    MOYU_AI(
        rootKey = byteArrayOf(
            0x05, 0x12, 0x02, 0x45, 0x02, 0x01, 0x29, 0x56,
            0x12, 0x78, 0x12, 0x76, 0x81.toByte(), 0x01, 0x08, 0x03,
        ),
        rootIv = byteArrayOf(
            0x01, 0x44, 0x28, 0x06, 0x86.toByte(), 0x21, 0x22, 0x28,
            0x51, 0x05, 0x08, 0x31, 0x82.toByte(), 0x02, 0x21, 0x06,
        ),
    );

    companion object {
        /**
         * Advertised-name prefix identifying a MoYu AI 2023. Both
         * cstimer and gan-web-bluetooth key off this exact string.
         */
        const val MOYU_AI_NAME_PREFIX = "AiCube"

        /**
         * Pick the key set from a cube's advertised name. An absent or
         * unrecognised name falls back to [STANDARD], which is correct
         * for every actual GAN cube.
         */
        fun forDeviceName(deviceName: String?): GanKeySet =
            if (deviceName?.startsWith(MOYU_AI_NAME_PREFIX, ignoreCase = true) == true) {
                MOYU_AI
            } else {
                STANDARD
            }
    }
}

/**
 * Per-cube encryptor for GAN smart cubes. AES-128 CBC over a static root
 * key + IV, mixed with a 6-byte salt derived from the cube's MAC.
 *
 * All three drivable GAN generations (Gen2, Gen3, Gen4) share the same
 * scheme; only the packet formats and BLE UUIDs differ. Gen1 is the
 * exception — it salts from the Device Information System ID and uses no
 * IV at all — which is one of the reasons it needs its own path (see
 * [GanGen1Protocol]).
 *
 * Thin wrapper over the vendor-agnostic [AesCbcMacSaltEncryptor].
 * Constants are reverse-engineered from the official GAN app and
 * verified against the gan-web-bluetooth project.
 */
class GanEncryptor(
    salt: ByteArray,
    keySet: GanKeySet = GanKeySet.STANDARD,
) : CubeEncryptor {

    private val delegate = AesCbcMacSaltEncryptor(keySet.rootKey, keySet.rootIv, salt)

    override fun encrypt(data: ByteArray): ByteArray = delegate.encrypt(data)
    override fun decrypt(data: ByteArray): ByteArray = delegate.decrypt(data)
}

/**
 * Build the per-cube salt from its BLE MAC address. The bytes are
 * reversed because that's what the GAN protocol expects (and MoYu
 * inherited the same convention).
 */
fun ganSaltFromMac(mac: String): ByteArray = macSaltFromMacAddress(mac)

/**
 * Build the encryptor for a GAN-protocol cube from its [CubeIdentity].
 *
 * The registry's `createEncryptor` hook for all three drivable GAN
 * generations: key, IV and salt derivation are identical across Gen2,
 * Gen3 and Gen4, so one factory serves all of them. A fresh instance per
 * connection, because the salt is per-cube.
 *
 * The name is consulted only to pick the key set — see
 * [GanKeySet.forDeviceName].
 */
internal fun ganEncryptorFor(identity: CubeIdentity): CubeEncryptor =
    GanEncryptor(
        salt = ganSaltFromMac(identity.mac),
        keySet = GanKeySet.forDeviceName(identity.name),
    )
