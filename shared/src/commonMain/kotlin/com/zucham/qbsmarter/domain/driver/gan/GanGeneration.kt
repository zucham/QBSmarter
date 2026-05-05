package com.zucham.qbsmarter.domain.driver.gan

/**
 * GAN smart-cube protocol generation. Each generation defines its own BLE
 * service / characteristic UUIDs and its own wire packet format, but all
 * three use the same static AES-128 CBC key and IV (per the upstream
 * gan-web-bluetooth project's definitions file). The per-cube salt
 * derivation from the BLE MAC is also identical across generations.
 *
 * Today three generations exist in the wild:
 *
 *  | Generation | Cubes (non-exhaustive)                         |
 *  |------------|-------------------------------------------------|
 *  | Gen2       | GAN356 i Carry, i Carry S, i 3, GAN12 ui,      |
 *  |            | GAN Mini ui FreePlay, Monster Go 3Ai            |
 *  | Gen3       | GAN356 i Carry 2                                |
 *  | Gen4       | GAN12 ui Maglev, GAN14 ui FreePlay              |
 *
 * The orchestrator probes BLE service UUIDs on connect and picks the
 * matching generation; the driver then instantiates the right parser.
 * If a future generation appears, only this enum + a new parser need
 * to be added – the driver, transport, and encryptor stay generic.
 *
 * **Encryption.** All three generations share key/IV via [GAN_GEN_KEY]
 * and [GAN_GEN_IV]. The encryptor itself is generation-agnostic; the
 * file name `GanGen2Encryptor` is preserved for incremental migration
 * and the class is reused for Gen3/Gen4 unchanged.
 */
enum class GanGeneration(
    val serviceUuid: String,
    val commandCharUuid: String,
    val stateCharUuid: String,
) {
    GEN2(
        serviceUuid = "6e400001-b5a3-f393-e0a9-e50e24dc4179",
        commandCharUuid = "28be4a4a-cd67-11e9-a32f-2a2ae2dbcce4",
        stateCharUuid = "28be4cb6-cd67-11e9-a32f-2a2ae2dbcce4",
    ),
    GEN3(
        serviceUuid = "8653000a-43e6-47b7-9cb0-5fc21d4ae340",
        commandCharUuid = "8653000c-43e6-47b7-9cb0-5fc21d4ae340",
        stateCharUuid = "8653000b-43e6-47b7-9cb0-5fc21d4ae340",
    ),
    GEN4(
        serviceUuid = "00000010-0000-fff7-fff6-fff5fff4fff0",
        commandCharUuid = "0000fff5-0000-1000-8000-00805f9b34fb",
        stateCharUuid = "0000fff6-0000-1000-8000-00805f9b34fb",
    );

    companion object {
        /**
         * Pick the generation whose service UUID is present in the cube's
         * advertised service list, or null if none match. The list comes
         * from `BluetoothGatt.discoverServices()` on Android.
         *
         * Comparison is case-insensitive – BLE platform layers normalise
         * UUIDs differently (some emit lowercase, some uppercase) and
         * we don't want to miss a match because of a casing nit.
         */
        fun detect(advertisedServices: Iterable<String>): GanGeneration? {
            val normalized = advertisedServices.map { it.lowercase() }.toSet()
            return entries.firstOrNull { it.serviceUuid.lowercase() in normalized }
        }
    }
}
