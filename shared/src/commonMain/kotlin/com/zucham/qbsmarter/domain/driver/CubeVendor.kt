package com.zucham.qbsmarter.domain.driver

import com.zucham.qbsmarter.domain.driver.gan.GanGeneration
import com.zucham.qbsmarter.domain.driver.moyu.MoyuConstants

/**
 * Vendor (manufacturer) of a smart cube. Today two vendors are supported:
 *
 *   | Vendor | Cubes (non-exhaustive)                               |
 *   |--------|-------------------------------------------------------|
 *   | GAN    | i Carry, i Carry S, i 3, i Carry 2, GAN12 ui,        |
 *   |        | GAN12 ui Maglev, GAN14 ui FreePlay, GAN Mini ui      |
 *   |        | FreePlay, Monster Go 3Ai                              |
 *   | MOYU   | MoYu WeiLong V10 AI (device name prefix `WCU_MY`)    |
 *
 * A vendor's wire protocol is its own – they share neither service UUIDs
 * nor packet formats, and only by coincidence do GAN and MoYu use the
 * same AES-128 CBC scheme (different root keys + IVs). Each vendor gets
 * its own [SmartCubeDriver] implementation. The
 * [com.zucham.qbsmarter.data.ble.ConnectionOrchestrator] picks one at
 * connect time by matching the cube's advertised BLE service UUIDs.
 *
 * The string [key] is the stable persistence form (lowercase, ASCII).
 * Adding a new vendor means: a new entry here, a new driver, a new
 * encryptor (if applicable), and the orchestrator's detection table.
 */
enum class CubeVendor(val key: String) {
    GAN("gan"),
    MOYU("moyu");

    companion object {
        /** Parse the persisted [key] back into the enum; defaults to [GAN]
         *  for legacy / unknown values so older paired-cube rows that
         *  pre-date the column (or carry a stray value from a hand-edited
         *  export) still parse. */
        fun fromKey(key: String?): CubeVendor =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: GAN

        /**
         * Pick the vendor whose service UUID is present in the cube's
         * advertised service list, or null if none match. The list comes
         * from [com.zucham.qbsmarter.data.ble.BleManager.discoveredServices]
         * (sourced from `BluetoothGatt.discoverServices()` on Android).
         *
         * GAN's three protocol generations all live under the GAN vendor;
         * the further Gen2-vs-Gen3-vs-Gen4 split happens inside the GAN
         * driver via [GanGeneration.detect]. The orchestrator therefore
         * calls *this* method first to choose a driver, then – inside the
         * GAN branch only – calls [GanGeneration.detect] to pick the
         * right parser within that driver.
         *
         * Comparison is case-insensitive – BLE platform layers normalise
         * UUIDs differently (some emit lowercase, some uppercase) and we
         * don't want to miss a match because of a casing nit.
         */
        fun detect(advertisedServices: Iterable<String>): CubeVendor? {
            val normalized = advertisedServices.map { it.lowercase() }.toSet()
            if (GanGeneration.entries.any { it.serviceUuid.lowercase() in normalized }) {
                return GAN
            }
            if (MoyuConstants.SERVICE_UUID.lowercase() in normalized) {
                return MOYU
            }
            return null
        }

        /**
         * Best-effort vendor for a device seen in a scan, before any
         * connection exists.
         *
         * Three signals, strongest first:
         *
         *  1. **Advertised service UUIDs** — authoritative when present.
         *     Identical to the post-connect check, just available a step
         *     earlier.
         *  2. **Device-name prefix** — what both reference clients
         *     (cstimer, gan-web-bluetooth) use, and the reliable
         *     fallback when a cube advertises no services.
         *  3. **MAC OUI prefix** — a weak hint, kept only for cubes that
         *     advertise neither services nor a recognisable name.
         *
         * Layering them matters. An earlier version relied on the OUI
         * list alone, and that list held a single prefix; every GAN cube
         * built on a different radio module was therefore invisible to
         * the Devices screen — not sorted to the top, no vendor chip —
         * even though connecting to it worked perfectly. Any one of
         * these signals is incomplete on its own; the union is not.
         *
         * Returns null for genuinely unrecognised peripherals (phones,
         * earbuds, watches), which the Devices screen lists plainly.
         */
        fun detectFromScan(
            name: String?,
            macAddress: String,
            advertisedServices: Iterable<String>,
        ): CubeVendor? {
            detect(advertisedServices)?.let { return it }
            NAME_PREFIXES.firstOrNull { (prefix, _) ->
                name?.startsWith(prefix, ignoreCase = true) == true
            }?.let { return it.second }
            if (GAN_MAC_OUI_PREFIXES.any { macAddress.startsWith(it, ignoreCase = true) }) {
                return GAN
            }
            return null
        }

        /**
         * BLE advertised-name prefixes that identify a cube vendor.
         *
         * GAN ships three: `GAN` for the main line, `MG` for Monster Go,
         * and `AiCube` for the MoYu AI 2023 — which, confusingly, speaks
         * GAN's protocol, so it maps to [GAN] rather than [MOYU]. The
         * MoYu entry covers the V10 AI's own protocol.
         *
         * Matched case-insensitively; a cube advertising no name simply
         * yields no match here and falls through to the OUI check.
         */
        private val NAME_PREFIXES: List<Pair<String, CubeVendor>> = listOf(
            "GAN" to GAN,
            "MG" to GAN,
            "AiCube" to GAN,
            MoyuConstants.DEVICE_NAME_PREFIX to MOYU,
        )

        /**
         * MAC OUI prefixes observed on GAN cubes. Last-resort signal
         * only — GAN uses several radio modules and this list will never
         * be complete, which is precisely why it sits behind the
         * service-UUID and name checks rather than in front of them.
         */
        private val GAN_MAC_OUI_PREFIXES = listOf("AB:12:34", "CC:A3:00", "D4:AF:2D")
    }
}
