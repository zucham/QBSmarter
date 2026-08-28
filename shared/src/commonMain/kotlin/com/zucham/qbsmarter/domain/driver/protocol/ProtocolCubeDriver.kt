package com.zucham.qbsmarter.domain.driver.protocol

import co.touchlab.kermit.Logger
import com.zucham.qbsmarter.domain.driver.CubeEncryptor
import com.zucham.qbsmarter.domain.driver.CubeTransport
import com.zucham.qbsmarter.domain.driver.SmartCubeCommand
import com.zucham.qbsmarter.domain.driver.SmartCubeDriver
import com.zucham.qbsmarter.domain.driver.SmartCubeEvent
import com.zucham.qbsmarter.util.toHexString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * The one and only smart-cube driver.
 *
 * Every vendor and every protocol generation runs through this class;
 * what varies between them lives entirely in a [CubeProtocol]. This
 * replaces the previous arrangement of one driver per vendor, where
 * the GAN driver and the MoYu driver each carried their own copy of
 * the same scope, ingest job, event flow, decrypt call, error handling
 * and connect/disconnect bookkeeping — and would have grown a third and
 * fourth copy as brands were added.
 *
 * **Stable event flow.** [events] is a single [SharedFlow] that outlives
 * every connection, so subscribers (the Solve screen, `AppLifecycle`)
 * bind once at startup and keep working across cube swaps, vendor swaps
 * and protocol swaps without re-subscribing. This is why the driver is a
 * singleton and the *protocol* is the thing that gets replaced.
 *
 * **Threading.** The driver owns a scope on [parserDispatcher] (default
 * [Dispatchers.Default]) so decryption and parsing never run on the BLE
 * binder thread. The protocol is touched only from the single ingest
 * coroutine, so protocol implementations need no synchronisation.
 *
 * @param parserDispatcher where decrypt + parse run.
 */
class ProtocolCubeDriver(
    parserDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SmartCubeDriver {

    private val log = Logger.withTag("CubeDriver")
    private val scope = CoroutineScope(SupervisorJob() + parserDispatcher)

    private var transport: CubeTransport? = null

    /**
     * Null for plaintext protocols (GoCube, Giiker, MoYu MHC). Absence
     * of an encryptor is a normal configuration, not a missing
     * dependency — [io] passes payloads through untouched.
     */
    private var encryptor: CubeEncryptor? = null

    private var protocol: CubeProtocol? = null
    private var ingestJob: Job? = null

    private val _events = MutableSharedFlow<SmartCubeEvent>(
        replay = 0,
        // Generous enough that a momentarily-paused subscriber (user
        // navigated away and back) doesn't drop moves.
        extraBufferCapacity = 64,
    )
    override val events: SharedFlow<SmartCubeEvent> = _events.asSharedFlow()

    /**
     * The write side handed to the protocol. A stable object rather than
     * one allocated per call, so a protocol may hold onto it (QiYi keeps
     * it to acknowledge frames from inside `decode`).
     */
    private val io = object : ProtocolIo {
        override suspend fun send(command: SmartCubeCommand) {
            val payload = protocol?.buildCommand(command) ?: return
            writePlain(payload)
        }

        override suspend fun writePlain(payload: ByteArray) {
            val t = transport ?: return
            // Encrypt only when this protocol family is encrypted.
            t.write(encryptor?.encrypt(payload) ?: payload)
        }
    }

    /**
     * Bind to a cube and start consuming its traffic.
     *
     * The caller resolves which [protocol] to run (see
     * [CubeProtocolRegistry]) and supplies a matching [encryptor], or
     * null for a plaintext family. Any previous connection is torn down
     * first, so this is safe to call when switching cubes.
     */
    suspend fun connect(
        transport: CubeTransport,
        encryptor: CubeEncryptor?,
        protocol: CubeProtocol,
    ) {
        disconnect()
        this.transport = transport
        this.encryptor = encryptor
        this.protocol = protocol

        transport.enableNotifications()
        ingestJob = scope.launch {
            log.d { "Ingest started (protocol=${protocol.id})" }
            transport.incoming.collect { raw -> ingest(raw, protocol) }
            log.w { "Ingest ended (protocol=${protocol.id})" }
        }

        // Handshake after the collector is live, so a cube that answers
        // instantly can't beat us to the subscription.
        runCatching { protocol.onConnected(io) }
            .onFailure { log.w(it) { "Protocol handshake failed (${protocol.id})" } }
    }

    /**
     * Decrypt and decode one notification.
     *
     * Failures are contained to the offending packet: a malformed frame
     * must never tear down a working connection, because the next one is
     * usually fine. Both the failure and the unhandled-packet case log
     * the bytes — those two paths used to be silent, which is precisely
     * what makes an unreproducible cube impossible to diagnose remotely.
     */
    private suspend fun ingest(raw: ByteArray, protocol: CubeProtocol) {
        runCatching {
            val plain = encryptor?.decrypt(raw) ?: raw
            val decoded = protocol.decode(plain, io)
            if (decoded.isEmpty()) {
                log.d { "unhandled ${protocol.id} packet: ${plain.toHexString()}" }
            } else {
                decoded.forEach { event ->
                    log.d { "emit $event" }
                    _events.tryEmit(event)
                }
            }
        }.onFailure {
            log.e(it) { "Failed to parse ${protocol.id} packet (${raw.size} bytes)" }
        }
    }

    override suspend fun send(command: SmartCubeCommand) = io.send(command)

    /**
     * Backwards-compatible [SmartCubeDriver] entry point. Unusable as-is
     * because it carries no protocol; the orchestrator always calls the
     * three-argument [connect] above. Kept so the generic interface
     * stays implementable, and loud so a mistaken call is obvious.
     */
    override suspend fun connect(transport: CubeTransport, encryptor: CubeEncryptor) {
        error("ProtocolCubeDriver requires an explicit CubeProtocol; use connect(transport, encryptor, protocol)")
    }

    override suspend fun disconnect() {
        ingestJob?.cancel()
        ingestJob = null
        transport = null
        encryptor = null
        protocol = null
    }
}
