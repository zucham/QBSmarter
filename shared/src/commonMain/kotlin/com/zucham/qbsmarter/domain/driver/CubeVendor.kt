package com.zucham.qbsmarter.domain.driver

/**
 * Manufacturer of a smart cube, as far as the user is concerned.
 *
 * This is a *labelling* concept, not a protocol one. Several vendors
 * share a wire protocol — Rubik's Connected speaks GoCube's protocol
 * verbatim, and the MoYu AI 2023 speaks GAN Gen2 with a different AES
 * key — so a vendor may map to a protocol owned by someone else. The
 * mapping from cube to protocol lives in
 * [com.zucham.qbsmarter.domain.driver.protocol.CubeProtocolRegistry];
 * this enum only decides what the Devices screen prints on the chip.
 *
 * [key] is the stable persistence form (lowercase ASCII) stored in the
 * `cubes.vendor` column. Never rename one without a migration.
 */
enum class CubeVendor(val key: String) {
    GAN("gan"),
    MOYU("moyu");

    companion object {
        /**
         * Parse the persisted [key]. Unknown and legacy values fall back
         * to [GAN] so rows written before the column existed — or by a
         * hand-edited export — still load instead of crashing the
         * Devices screen.
         */
        fun fromKey(key: String?): CubeVendor =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: GAN
    }
}
