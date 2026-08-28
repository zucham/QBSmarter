package com.zucham.qbsmarter.data.db

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.zucham.qbsmarter.db.Cubes
import com.zucham.qbsmarter.db.QbsmarterDatabase
import com.zucham.qbsmarter.util.currentTimeMillis
import com.zucham.qbsmarter.util.generateUuid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Persisted paired-cube model. The hardware-info fields ([hwVersion],
 * [swVersion], [gyroSupported]) are nullable because they're populated only
 * after the cube's INFO round-trip completes. Pre-pairing or for a non-GAN
 * cube, they stay null.
 */
data class PairedCube(
    val id: String,
    val mac: String,
    val name: String?,
    val lastSeen: Long,
    val userId: String,
    val hwVersion: String?,
    val swVersion: String?,
    val gyroSupported: Boolean?,
)

private fun Cubes.toModel() = PairedCube(
    id = id,
    mac = mac,
    name = name,
    lastSeen = last_seen,
    userId = user_id,
    hwVersion = hw_version,
    swVersion = sw_version,
    gyroSupported = gyro_supported?.let { it != 0L },
)

class DevicesRepository(
    private val db: QbsmarterDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun observeForUser(userId: String): Flow<List<PairedCube>> =
        db.cubesQueries.selectByUser(userId)
            .asFlow().mapToList(ioDispatcher).map { it.map(Cubes::toModel) }
            .distinctUntilChanged()

    fun byId(id: String): PairedCube? =
        db.cubesQueries.selectById(id).executeAsOneOrNull()?.toModel()

    fun byMac(mac: String): PairedCube? =
        db.cubesQueries.selectByMac(mac).executeAsOneOrNull()?.toModel()

    /** Insert-or-refresh by MAC; original UUID preserved on conflict. */
    fun rememberCube(userId: String, mac: String, name: String?): PairedCube {
        val existing = db.cubesQueries.selectByMac(mac).executeAsOneOrNull()
        val now = currentTimeMillis()
        val id = existing?.id ?: generateUuid()
        db.cubesQueries.upsert(id, mac, name ?: existing?.name, now, userId)
        return byMac(mac) ?: PairedCube(
            id = id, mac = mac, name = name ?: existing?.name,
            lastSeen = now, userId = userId,
            hwVersion = existing?.hw_version, swVersion = existing?.sw_version,
            gyroSupported = existing?.gyro_supported?.let { it != 0L },
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

    fun forget(id: String) = db.cubesQueries.deleteById(id)

    /** Snapshot all paired cubes for the user – used by export. */
    fun snapshotAllForUser(userId: String): List<PairedCube> =
        db.cubesQueries.selectByUser(userId).executeAsList().map(Cubes::toModel)

    /** Wipe all cubes for a profile. Used when overwriting via import. */
    fun deleteAllForUser(userId: String) {
        db.cubesQueries.deleteAllForUser(userId)
    }
}
