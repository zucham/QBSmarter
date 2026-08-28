package com.zucham.qbsmarter.domain.driver.protocol

import com.zucham.qbsmarter.domain.driver.CubeVendor
import com.zucham.qbsmarter.domain.driver.SmartCubeCommand
import com.zucham.qbsmarter.domain.driver.SmartCubeEvent

/**
 * Everything that is specific to one smart-cube wire protocol, and
 * nothing that isn't.
 *
 * A protocol is a pure codec plus a handshake: it turns decrypted
 * notification bytes into [SmartCubeEvent]s and turns high-level
 * [SmartCubeCommand]s into wire payloads. It owns no coroutines, no BLE
 * handles and no lifecycle — [ProtocolCubeDriver] supplies all of that,
 * identically for every vendor. Adding a cube family therefore means
 * writing one implementation of this interface and adding one row to
 * [CubeProtocolRegistry]; it never means writing another driver.
 *
 * **Instances are per-connection.** The registry constructs a fresh
 * protocol for each connect, handing it the cube's [CubeIdentity]. That
 * removes the whole class of bugs where a rolling serial number or a
 * half-filled hardware accumulator leaks from one cube into the next —
 * previously guarded by a `reset()` method that every implementation had
 * to remember to write correctly.
 *
 * **Implementations may be stateful** and are only ever touched from the
 * driver's single ingest coroutine, so they need no synchronisation.
 */
interface CubeProtocol {

    /** Vendor to stamp on [SmartCubeEvent.Hardware] events. */
    val vendor: CubeVendor

    /**
     * Short stable id for logs and diagnostics, e.g. `gan-gen2`.
     * Matches the corresponding [CubeProtocolRegistry] entry.
     */
    val id: String

    /**
     * Protocol-specific handshake, run once after notifications are live
     * and before the orchestrator's generic command burst.
     *
     * This is where a protocol does whatever it uniquely requires:
     * QiYi must send a MAC-bearing hello before the cube says anything
     * at all; GoCube must ask for its state and opt in to the
     * orientation stream; MoYu WCU must enable gyro notifications. The
     * default is to do nothing, which is right for GAN.
     */
    suspend fun onConnected(io: ProtocolIo) {}

    /**
     * Encode a high-level command, or null when this protocol has no
     * equivalent.
     *
     * Null is normal and not an error: GAN Gen2 has no targeted
     * move-history retransmit, Giiker has no command surface beyond a
     * battery poll, and GoCube has no reset. The driver simply skips the
     * write.
     */
    fun buildCommand(cmd: SmartCubeCommand): ByteArray?

    /**
     * Decode one decrypted notification into zero or more events.
     *
     * Returning an empty list is normal — an opcode this protocol
     * doesn't implement, a duplicate, or a packet that only advances
     * internal state. The driver logs empty results at debug level so an
     * unknown cube can still be diagnosed from a log.
     *
     * Suspending because some protocols must write during decode:
     * GAN Gen3/Gen4 request a move-history refill on a serial gap, and
     * QiYi must acknowledge frames the cube flags as needing an ACK.
     */
    suspend fun decode(packet: ByteArray, io: ProtocolIo): List<SmartCubeEvent>
}

/**
 * The cube a protocol instance is bound to. Supplied at construction
 * because several protocols need it before the first packet: QiYi puts
 * the MAC in its hello, GAN derives its AES salt from it, and GoCube
 * decides whether to enable the gyro stream from the advertised name.
 *
 * @property mac colon-separated BLE MAC, as the platform reports it.
 * @property name advertised BLE name, or null if the scan never got one.
 */
data class CubeIdentity(
    val mac: String,
    val name: String?,
)

/**
 * The write side of the connection, handed to a protocol so it can talk
 * back without knowing anything about BLE or encryption.
 *
 * Both methods encrypt via the connection's encryptor when the protocol
 * has one, and pass bytes through untouched when it doesn't — so an
 * implementation never branches on whether its cube family is encrypted.
 */
interface ProtocolIo {
    /** Encode [command] via the active protocol, then send it. No-op if unsupported. */
    suspend fun send(command: SmartCubeCommand)

    /** Send an already-built plaintext payload. */
    suspend fun writePlain(payload: ByteArray)
}
