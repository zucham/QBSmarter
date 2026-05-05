package com.zucham.qbsmarter.data.ble

import co.touchlab.kermit.Logger
import com.zucham.qbsmarter.domain.driver.CubeTransport
import com.zucham.qbsmarter.util.toHexString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class BleCubeTransport(
    private val ble: BleManager,
    private val serviceUuid: String,
    private val commandCharUuid: String,
    private val stateCharUuid: String,
) : CubeTransport {

    private val log = Logger.withTag("BleTransport")

    override val incoming: Flow<ByteArray> =
        ble.characteristicData
            .map { it[stateCharUuid] }
            .filter { it != null }
            .map { it!!.copyOf() }
            .distinctUntilChanged { a, b -> a.contentEquals(b) }
            .onEach { log.d { "rx ${it.size}B: ${it.toHexString()}" } }

    override suspend fun write(payload: ByteArray) {
        log.d { "tx ${payload.size}B: ${payload.toHexString()}" }
        ble.writeCharacteristic(serviceUuid, commandCharUuid, payload)
    }

    override suspend fun enableNotifications() {
        log.d { "enableNotifications service=$serviceUuid char=$stateCharUuid" }
        ble.enableNotifications(serviceUuid, stateCharUuid)
    }
}