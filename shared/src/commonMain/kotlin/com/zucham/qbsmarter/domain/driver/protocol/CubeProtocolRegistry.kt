package com.zucham.qbsmarter.domain.driver.protocol

import com.zucham.qbsmarter.domain.driver.CubeEncryptor
import com.zucham.qbsmarter.domain.driver.CubeVendor
import com.zucham.qbsmarter.domain.driver.gan.GanGen1Protocol
import com.zucham.qbsmarter.domain.driver.gan.GanGen2Protocol
import com.zucham.qbsmarter.domain.driver.gan.GanGen3Protocol
import com.zucham.qbsmarter.domain.driver.gan.GanGen4Protocol
import com.zucham.qbsmarter.domain.driver.gan.ganEncryptorFor
import com.zucham.qbsmarter.domain.driver.moyu.MoyuMhcProtocol
import com.zucham.qbsmarter.domain.driver.moyu.MoyuWcuProtocol
import com.zucham.qbsmarter.domain.driver.moyu.moyuWcuEncryptorFor
import com.zucham.qbsmarter.domain.driver.qiyi.QiyiProtocol
import com.zucham.qbsmarter.domain.driver.qiyi.qiyiEncryptor

/**
 * One row per smart-cube wire protocol. This table is the whole of the
 * app's knowledge about which cubes exist and how to talk to them.
 *
 * Adding a cube family is: write a [CubeProtocol], add an entry here.
 * Nothing else changes — not the driver, not the transport, not the
 * orchestrator, not the UI. That is the point of the table.
 *
 * @property id stable short identifier, used in logs and diagnostics.
 * @property vendor what to show the user.
 * @property serviceUuid primary GATT service. Also the main detection key.
 * @property commandCharUuid characteristic the app writes to.
 * @property stateCharUuid characteristic the cube notifies on.
 * @property namePrefixes advertised-name prefixes that identify this
 *   family before connecting. Several protocols can only be told apart
 *   by name (QiYi and GAN Gen1 both live under service `0000fff0`), and
 *   for the Devices screen this is often the only signal available.
 * @property requiresExtraServiceUuid a second service that must also be
 *   present. Only GAN Gen1 needs one, and it is what disambiguates it
 *   from QiYi on the shared `0000fff0` service.
 * @property createEncryptor builds the per-cube encryptor, or returns
 *   null for a plaintext family. Plaintext is a normal configuration:
 *   GoCube, Giiker and MoYu MHC send everything in the clear.
 * @property createProtocol builds a fresh protocol instance per
 *   connection.
 * @property supported false for families we have registered but cannot
 *   yet drive; see [CubeProtocolRegistry.unsupported].
 */
data class CubeProtocolSpec(
    val id: String,
    val vendor: CubeVendor,
    val serviceUuid: String,
    val commandCharUuid: String,
    val stateCharUuid: String,
    val namePrefixes: List<String> = emptyList(),
    val requiresExtraServiceUuid: String? = null,
    val createEncryptor: (CubeIdentity) -> CubeEncryptor? = { null },
    val createProtocol: (CubeIdentity) -> CubeProtocol,
    val supported: Boolean = true,
) {
    /**
     * Whether this spec matches a cube whose advertised services are
     * [services]. Name is consulted only to break ties between
     * protocols sharing a service UUID.
     */
    fun matches(services: Set<String>, deviceName: String?): Boolean {
        if (serviceUuid.lowercase() !in services) return false
        val extra = requiresExtraServiceUuid?.lowercase()
        if (extra != null && extra !in services) return false
        // Where two protocols share a service, the one with name
        // prefixes must actually match a name to win.
        if (namePrefixes.isNotEmpty() && sharesServiceWithAnother()) {
            return matchesName(deviceName)
        }
        return true
    }

    fun matchesName(deviceName: String?): Boolean =
        deviceName != null && namePrefixes.any { deviceName.startsWith(it, ignoreCase = true) }

    private fun sharesServiceWithAnother(): Boolean =
        CubeProtocolRegistry.all.count { it.serviceUuid.equals(serviceUuid, ignoreCase = true) } > 1
}

/**
 * The protocol table, and the resolution logic that picks a row.
 *
 * **Resolution is service-first, name-second.** Advertised service UUIDs
 * are authoritative and unspoofable in practice; names are a heuristic
 * needed only because GAN reuses the generic `0000fff0` service that
 * QiYi also uses, and because the Devices screen has to classify cubes
 * before any connection exists.
 */
object CubeProtocolRegistry {

    /** GAN Gen2/3/4, MoYu WCU and QiYi all share this UUID tail. */
    private const val BT_BASE = "-0000-1000-8000-00805f9b34fb"

    val all: List<CubeProtocolSpec> = listOf(

        // -- GAN ---------------------------------------------------------
        // Three generations of the same vendor, told apart purely by
        // service UUID. Marketing names are no guide: "GAN12 ui" is Gen2
        // while "GAN12 ui Maglev" is Gen4.
        CubeProtocolSpec(
            id = "gan-gen2",
            vendor = CubeVendor.GAN,
            serviceUuid = "6e400001-b5a3-f393-e0a9-e50e24dc4179",
            commandCharUuid = "28be4a4a-cd67-11e9-a32f-2a2ae2dbcce4",
            stateCharUuid = "28be4cb6-cd67-11e9-a32f-2a2ae2dbcce4",
            namePrefixes = listOf("GAN", "MG", "AiCube"),
            createEncryptor = { ganEncryptorFor(it) },
            createProtocol = { GanGen2Protocol() },
        ),
        CubeProtocolSpec(
            id = "gan-gen3",
            vendor = CubeVendor.GAN,
            serviceUuid = "8653000a-43e6-47b7-9cb0-5fc21d4ae340",
            commandCharUuid = "8653000c-43e6-47b7-9cb0-5fc21d4ae340",
            stateCharUuid = "8653000b-43e6-47b7-9cb0-5fc21d4ae340",
            namePrefixes = listOf("GAN"),
            createEncryptor = { ganEncryptorFor(it) },
            createProtocol = { GanGen3Protocol() },
        ),
        CubeProtocolSpec(
            id = "gan-gen4",
            vendor = CubeVendor.GAN,
            serviceUuid = "00000010-0000-fff7-fff6-fff5fff4fff0",
            commandCharUuid = "0000fff5$BT_BASE",
            stateCharUuid = "0000fff6$BT_BASE",
            namePrefixes = listOf("GAN"),
            createEncryptor = { ganEncryptorFor(it) },
            createProtocol = { GanGen4Protocol() },
        ),

        // -- MoYu --------------------------------------------------------
        // Two unrelated protocols under one brand. WCU is the V10/V11
        // family (encrypted, GAN-style AES); MHC is the older WeiLong AI
        // (plaintext, entirely different framing).
        CubeProtocolSpec(
            id = "moyu-wcu",
            vendor = CubeVendor.MOYU,
            serviceUuid = "0783b03e-7735-b5a0-1760-a305d2795cb0",
            commandCharUuid = "0783b03e-7735-b5a0-1760-a305d2795cb2",
            stateCharUuid = "0783b03e-7735-b5a0-1760-a305d2795cb1",
            namePrefixes = listOf("WCU_MY"),
            createEncryptor = { moyuWcuEncryptorFor(it) },
            createProtocol = { MoyuWcuProtocol() },
        ),
        CubeProtocolSpec(
            id = "moyu-mhc",
            vendor = CubeVendor.MOYU,
            serviceUuid = "00001000$BT_BASE",
            commandCharUuid = "00001001$BT_BASE",
            stateCharUuid = "00001003$BT_BASE",
            namePrefixes = listOf("MHC"),
            createProtocol = { MoyuMhcProtocol() },
        ),

        // -- QiYi --------------------------------------------------------
        // Shares service 0000fff0 with GAN Gen1, so both carry name
        // prefixes and `matches` requires a name hit for either to win.
        CubeProtocolSpec(
            id = "qiyi",
            vendor = CubeVendor.QIYI,
            serviceUuid = "0000fff0$BT_BASE",
            commandCharUuid = "0000fff6$BT_BASE",
            stateCharUuid = "0000fff6$BT_BASE",
            namePrefixes = listOf("QY-QYSC", "XMD-TornadoV4-i"),
            createEncryptor = { qiyiEncryptor() },
            createProtocol = { QiyiProtocol(it) },
        ),

        // -- GAN Gen1 ----------------------------------------------------
        // Registered but not drivable: see [unsupported]. Last in the
        // list so it never shadows QiYi on the shared service.
        CubeProtocolSpec(
            id = "gan-gen1",
            vendor = CubeVendor.GAN,
            serviceUuid = "0000fff0$BT_BASE",
            commandCharUuid = "0000fff5$BT_BASE",
            stateCharUuid = "0000fff5$BT_BASE",
            namePrefixes = listOf("GAN", "Gan"),
            requiresExtraServiceUuid = "0000180a$BT_BASE",
            createProtocol = { GanGen1Protocol() },
            supported = false,
        ),
    )

    /**
     * Protocols known about but not yet drivable. Kept in the table
     * rather than omitted so that connecting to one produces an honest
     * "recognised, not supported" rather than "unknown device", and so
     * the Devices screen can still label the cube correctly.
     */
    val unsupported: List<CubeProtocolSpec> get() = all.filter { !it.supported }

    /**
     * Resolve the protocol for a cube whose GATT services have been
     * discovered. Returns null when nothing matches.
     *
     * Ordering within [all] is the tie-break of last resort, which is
     * why GAN Gen1 sits at the end.
     */
    fun resolve(advertisedServices: Iterable<String>, identity: CubeIdentity): CubeProtocolSpec? {
        val services = advertisedServices.map { it.lowercase() }.toSet()
        return all.firstOrNull { it.matches(services, identity.name) }
            // Fall back to ignoring the name when nothing matched: a
            // cube on a known service with an unexpected name is far
            // more likely to be a new model than a different vendor.
            ?: all.firstOrNull { spec ->
                spec.serviceUuid.lowercase() in services &&
                    spec.requiresExtraServiceUuid?.lowercase()?.let { it in services } != false
            }
    }

    /**
     * Best-effort vendor for a scan hit, before any connection exists.
     *
     * Three signals, strongest first: advertised service UUIDs
     * (authoritative — the same check [resolve] makes), device-name
     * prefix (what every reference client uses), then MAC OUI as a last
     * resort. Any one of these is incomplete on its own; the union is
     * not. Returns null for genuinely unrecognised peripherals.
     */
    fun detectVendorFromScan(
        name: String?,
        macAddress: String,
        advertisedServices: Iterable<String>,
    ): CubeVendor? {
        val services = advertisedServices.map { it.lowercase() }.toSet()
        if (services.isNotEmpty()) {
            all.firstOrNull { it.matches(services, name) }?.let { return it.vendor }
        }
        all.firstOrNull { it.matchesName(name) }?.let { return it.vendor }
        if (GAN_MAC_OUI_PREFIXES.any { macAddress.startsWith(it, ignoreCase = true) }) {
            return CubeVendor.GAN
        }
        return null
    }

    /**
     * MAC OUI prefixes observed on GAN cubes. Last-resort signal only —
     * GAN uses several radio modules and this list will never be
     * complete, which is why it sits behind the service and name checks.
     */
    private val GAN_MAC_OUI_PREFIXES = listOf("AB:12:34", "CC:A3:00", "D4:AF:2D")
}
