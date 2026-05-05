package com.zucham.qbsmarter.domain.driver

import kotlinx.coroutines.flow.SharedFlow

/**
 * Generation-agnostic smart cube driver. Implementations bind to a cube,
 * decrypt + parse byte traffic, and emit unified events.
 *
 * Why per-connect encryptor: the GAN Gen2 cube derives its AES salt from
 * its MAC address, so each cube needs its own encryptor. We pass it at
 * connect time so the driver itself can stay a singleton – the SharedFlow
 * of events therefore stays stable across cube swaps, and any subscriber
 * on the Solve screen automatically sees events from whichever cube is
 * currently connected.
 */
interface SmartCubeDriver {

    val events: SharedFlow<SmartCubeEvent>

    /** Begin consuming bytes. Idempotent. */
    suspend fun connect(transport: CubeTransport, encryptor: CubeEncryptor)

    suspend fun send(command: SmartCubeCommand)

    suspend fun disconnect()
}
