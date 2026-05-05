package com.zucham.qbsmarter.domain.driver.gan

import co.touchlab.kermit.Logger
import com.zucham.qbsmarter.domain.driver.CubeEncryptor
import com.zucham.qbsmarter.domain.driver.CubeTransport
import com.zucham.qbsmarter.domain.driver.SmartCubeCommand
import com.zucham.qbsmarter.domain.driver.SmartCubeDriver
import com.zucham.qbsmarter.domain.driver.SmartCubeEvent
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
 * Generation-aware GAN smart-cube driver. Replaces the old per-generation
 * `GanGen2Driver` with a single driver that holds three parsers
 * internally and routes incoming bytes to the matching one based on the
 * generation chosen at [connect] time.
 *
 * Why one driver instead of three Koin-bound implementations: the rest
 * of the app subscribes to [events] as a stable [SharedFlow]. Switching
 * cubes – even across generations – must not flip the subscriber over
 * to a different flow instance. A single driver with a stable events
 * flow gives the UI a frame-zero stable reference.
 *
 * **Generation selection.** [connect] takes an explicit [GanGeneration]
 * argument, supplied by [com.zucham.qbsmarter.data.ble.ConnectionOrchestrator]
 * after probing the cube's advertised service UUIDs. The driver stores
 * the parser for that generation and uses it for subsequent
 * [parseStatePacket] / [buildCommand] calls until the next [connect].
 *
 * **History backfill.** Gen3 and Gen4 parsers ask for missing-move
 * retransmits via a callback supplied to their `parseStatePacket`
 * method. The driver wires that callback to a coroutine that issues
 * a [SmartCubeCommand.RequestMoveHistory] through the same `send()`
 * path the rest of the app uses, ensuring the cube sees the request
 * encrypted with the active encryptor and routed through the active
 * transport. Failures are logged but do not interrupt event flow –
 * the parser will simply re-issue the request on the next packet that
 * exposes the same gap.
 */
class GanCubeDriver(
    parserDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SmartCubeDriver {

    private val log = Logger.withTag("GanCubeDriver")
    private val scope = CoroutineScope(SupervisorJob() + parserDispatcher)

    /**
     * One parser per generation. We instantiate all three eagerly because
     * they're tiny (a handful of `Int` fields and an empty deque) and
     * having them ready lets [connect] flip generations without any
     * allocation churn.
     */
    private val parsers: Map<GanGeneration, GanParser> = mapOf(
        GanGeneration.GEN2 to GanGen2Parser(),
        GanGeneration.GEN3 to GanGen3Parser(),
        GanGeneration.GEN4 to GanGen4Parser(),
    )

    private var transport: CubeTransport? = null
    private var encryptor: CubeEncryptor? = null
    private var ingestJob: Job? = null

    /**
     * Active parser for the current connection. Resolved during
     * [connect] from the supplied generation; null while disconnected.
     * All packet decoding and command building dispatches through this
     * reference.
     */
    private var activeParser: GanParser? = null

    private val _events = MutableSharedFlow<SmartCubeEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val events: SharedFlow<SmartCubeEvent> = _events.asSharedFlow()

    /**
     * Generation-aware connect. Public type stays compatible with the
     * `SmartCubeDriver` interface via the [connect] overload below; this
     * is the one [com.zucham.qbsmarter.data.ble.ConnectionOrchestrator]
     * actually calls so it can pass the detected generation.
     */
    suspend fun connect(
        transport: CubeTransport,
        encryptor: CubeEncryptor,
        generation: GanGeneration,
    ) {
        if (this.transport === transport && ingestJob?.isActive == true && activeParser != null) {
            // Idempotent reconnect – same transport and parser already
            // running. Nothing to do.
            return
        }
        disconnect()
        val parser = parsers.getValue(generation)
        parser.reset()
        activeParser = parser
        this.transport = transport
        this.encryptor = encryptor
        transport.enableNotifications()
        ingestJob = scope.launch {
            log.d { "Driver collecting from transport (generation=$generation)" }
            transport.incoming.collect { raw ->
                runCatching {
                    val plain = encryptor.decrypt(raw)
                    // `this@GanCubeDriver::historyRequester` is the bound
                    // member reference – the parser invokes it with
                    // (startSerial, count) when it detects a gap. Bound
                    // explicitly so it's obvious the callback closes
                    // over the driver instance, not the collect lambda.
                    val events = parser.parseStatePacket(
                        plain,
                        this@GanCubeDriver::historyRequester,
                    )
                    log.d { "decrypted -> ${events.size} events" }
                    events.forEach { event ->
                        log.d { "emit $event" }
                        _events.tryEmit(event)
                    }
                }.onFailure { log.e(it) { "Failed to parse GAN packet" } }
            }
            log.w { "Driver collect ended" }
        }
    }

    /**
     * Backwards-compatible overload from [SmartCubeDriver]. Defaults the
     * generation to [GanGeneration.GEN2] – the orchestrator should
     * always call the explicit generation overload above; this default
     * keeps source compatibility with any older code path that still
     * targets the interface.
     */
    override suspend fun connect(transport: CubeTransport, encryptor: CubeEncryptor) {
        connect(transport, encryptor, GanGeneration.GEN2)
    }

    override suspend fun send(command: SmartCubeCommand) {
        val t = transport ?: return
        val e = encryptor ?: return
        val parser = activeParser ?: return
        // Parsers may legitimately return null for commands the
        // generation doesn't support (Gen2's RequestMoveHistory, for
        // instance). A null payload simply skips the GATT write.
        val cmd = parser.buildCommand(command) ?: return
        t.write(e.encrypt(cmd))
    }

    /**
     * Callback handed to parsers' `parseStatePacket`. Translates a
     * (startSerial, count) parser-side request into a real
     * [SmartCubeCommand.RequestMoveHistory] sent through the active
     * transport. Errors are absorbed: re-attempting on the next packet
     * is the parser's job, not the driver's, so we don't tear down on
     * a transient GATT-write failure.
     */
    private suspend fun historyRequester(startSerial: Int, count: Int) {
        runCatching {
            send(SmartCubeCommand.RequestMoveHistory(startSerial, count))
        }.onFailure {
            log.w(it) { "RequestMoveHistory($startSerial, $count) failed" }
        }
    }

    override suspend fun disconnect() {
        ingestJob?.cancel()
        ingestJob = null
        transport = null
        encryptor = null
        activeParser = null
    }
}
