package com.zucham.qbsmarter.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.zucham.qbsmarter.db.QbsmarterDatabase
import com.zucham.qbsmarter.domain.driver.CubeVendor
import com.zucham.qbsmarter.util.currentTimeMillis
import com.zucham.qbsmarter.util.generateUuid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Persisted paired-cube model. The hardware-info fields ([hwVersion],
 * [swVersion], [gyroSupported]) are nullable because they're populated only
 * after the cube's INFO round-trip completes. Pre-pairing or for a non-GAN
 * cube, they stay null.
 *
 * **Two names.** [advertisedName] is what the cube calls itself over BLE —
 * a property of the hardware, shared by every profile that pairs it.
 * [customName] is *this profile's* override, or null if it has none.
 * [name] resolves the two for display. The split is what keeps a rename
 * made in one profile out of every other profile's list; see CubeNames.sq
 * for the full reasoning.
 *
 * Code that needs a name to show the user wants [name]. Code that hands a
 * name back to the BLE layer wants [advertisedName] — protocol resolution
 * and the GAN key derivation both read the advertised name, and feeding
 * them a user's label would break cube detection on reconnect.
 *
 * [vendor] is the detected manufacturer-protocol family for this cube
 * (see [CubeVendor]). Populated by the connection orchestrator as soon
 * as BLE service discovery completes – well before the INFO round-trip –
 * and never null in practice for any cube the orchestrator has
 * successfully reached the service-discovery stage with. Defaults to
 * [CubeVendor.GAN] for rows created before this field existed
 * (the schema's `DEFAULT 'gan'` covers the SQL side; this enum value
 * matches it so they round-trip without surprise).
 */
data class PairedCube(
    val id: String,
    val mac: String,
    val advertisedName: String?,
    val customName: String?,
    val lastSeen: Long,
    val userId: String,
    val hwVersion: String?,
    val swVersion: String?,
    val gyroSupported: Boolean?,
    val vendor: CubeVendor,
) {
    /**
     * The name to show for this cube in this profile: the profile's own
     * override if it has one, otherwise whatever the cube advertises.
     * Null only when neither exists, which the UI renders as "Unknown".
     */
    val name: String? get() = customName ?: advertisedName
}

class DevicesRepository(
    private val db: QbsmarterDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * The profile's paired cubes, each already carrying that profile's
     * name override. Backed by one joined query, so a rename re-emits
     * this flow exactly like a pairing does.
     */
    fun observeForUser(userId: String): Flow<List<PairedCube>> =
        pairedCubesQuery(userId).asFlow().mapToList(ioDispatcher)
            .distinctUntilChanged()

    /**
     * Insert-or-refresh by MAC; the original UUID is preserved on
     * conflict, so anything holding an id stays valid across re-pairs.
     *
     * [advertisedName] is the name the cube reports over BLE. It is
     * stored on the shared `cubes` row and does not touch any profile's
     * rename — passing null (a cube that advertised no name this time)
     * leaves the last one we saw in place.
     */
    fun rememberCube(userId: String, mac: String, advertisedName: String?) {
        val existingId = db.cubesQueries.selectByMac(mac).executeAsOneOrNull()?.id
        db.cubesQueries.upsert(
            id = existingId ?: generateUuid(),
            mac = mac,
            name = advertisedName,
            lastSeen = currentTimeMillis(),
            userId = userId,
        )
    }

    /**
     * Stamp HW/SW versions and the gyro flag onto an already-paired cube.
     *
     * Safe to call repeatedly within one connection — GAN Gen4 reports
     * its hardware in instalments and each one is persisted as it lands.
     *
     * A null [gyroSupported] means "not established yet" and leaves any
     * previously-recorded value untouched (the query COALESCEs), so a
     * later partial reply can't downgrade a known capability back to
     * unknown.
     */
    fun updateHardwareInfo(
        mac: String,
        hwVersion: String,
        swVersion: String,
        gyroSupported: Boolean?,
    ) {
        db.cubesQueries.updateHardwareInfo(
            hwVersion = hwVersion,
            swVersion = swVersion,
            gyroSupported = gyroSupported?.let { if (it) 1L else 0L },
            mac = mac,
        )
    }

    /**
     * Record that a cube has a gyroscope on the strength of it having
     * actually sent gyro data. See
     * [com.zucham.qbsmarter.data.ble.ConnectionOrchestrator] for why this
     * exists alongside the capability the cube declares.
     */
    fun markGyroSupported(mac: String) {
        db.cubesQueries.markGyroSupported(mac)
    }

    /**
     * Stamp the detected vendor onto an already-paired cube. Called by
     * [com.zucham.qbsmarter.data.ble.ConnectionOrchestrator] right after
     * BLE service discovery picks a [CubeVendor]. The vendor never
     * changes for a given physical cube; this is one write per pair
     * (or per re-pair under a different profile).
     */
    fun updateVendor(mac: String, vendor: CubeVendor) {
        db.cubesQueries.updateVendor(vendor = vendor.key, mac = mac)
    }

    /**
     * Give a cube a name **for one profile**. Other profiles that pair
     * the same physical cube are unaffected — the override is stored
     * against (userId, mac) in `cube_names`, never on the shared `cubes`
     * row.
     *
     * A blank name removes the override rather than storing an empty
     * string: "no name of my own" is the absence of a row, so the cube
     * goes back to showing whatever it advertises. That is the only
     * sensible reading of clearing the field, and it keeps one state
     * from having two representations.
     *
     * No BLE work is involved and the name is not sent to the cube, so
     * this is safe while connected and takes effect immediately.
     */
    fun rename(userId: String, mac: String, name: String?) {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed == null) db.cubeNamesQueries.remove(userId = userId, mac = mac)
        else db.cubeNamesQueries.put(userId = userId, mac = mac, name = trimmed)
    }

    /**
     * Forget a paired cube, taking the owning profile's name override
     * with it. Forgetting is the user saying they're done with this
     * cube; leaving their label behind to resurface if they ever pair it
     * again would be a small haunting.
     *
     * Ordering matters and is why this is a transaction: the name delete
     * looks the MAC and owner up *through* the cube row, so it has to run
     * first.
     */
    fun forget(id: String) {
        db.transaction {
            db.cubeNamesQueries.removeForCubeId(id)
            db.cubesQueries.deleteById(id)
        }
    }

    /**
     * Snapshot all paired cubes for the user – used by export. Carries
     * the same per-profile names [observeForUser] does.
     */
    fun snapshotAllForUser(userId: String): List<PairedCube> =
        pairedCubesQuery(userId).executeAsList()

    /** Wipe all cubes for a profile. Used when overwriting via import. */
    fun deleteAllForUser(userId: String) {
        db.transaction {
            db.cubeNamesQueries.deleteAllForUser(userId)
            db.cubesQueries.deleteAllForUser(userId)
        }
    }

    /**
     * The one place the joined row is turned into a [PairedCube], shared
     * by the reactive and snapshot readers so they can't drift apart.
     *
     * Written against the generated *mapper* overload rather than the
     * generated row class: the projection is columns-plus-one-join, and
     * naming its result type here would tie this file to a detail of how
     * SQLDelight names such classes for no benefit.
     */
    private fun pairedCubesQuery(userId: String) =
        db.cubesQueries.selectByUser(userId) {
            id, mac, name, lastSeen, rowUserId, hwVersion, swVersion,
            gyroSupported, vendor, customName ->
            PairedCube(
                id = id,
                mac = mac,
                advertisedName = name,
                customName = customName,
                lastSeen = lastSeen,
                userId = rowUserId,
                hwVersion = hwVersion,
                swVersion = swVersion,
                gyroSupported = gyroSupported?.let { it != 0L },
                vendor = CubeVendor.fromKey(vendor),
            )
        }
}
