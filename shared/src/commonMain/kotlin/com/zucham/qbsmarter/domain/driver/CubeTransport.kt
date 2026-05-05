package com.zucham.qbsmarter.domain.driver

import kotlinx.coroutines.flow.Flow

/**
 * Bidirectional byte transport. Drivers don't know about BLE; they consume
 * an [incoming] flow and call [write] to send. The platform-specific BLE
 * adapter implements this and is responsible for hopping off the binder
 * thread before emitting.
 */
interface CubeTransport {
    /** Stream of raw notifications from the cube. Already off the BLE thread. */
    val incoming: Flow<ByteArray>

    /** Send a 20-byte command. Suspends if the BLE stack is busy. */
    suspend fun write(payload: ByteArray)

    /** Subscribe to GATT notifications on the state characteristic. */
    suspend fun enableNotifications()
}
