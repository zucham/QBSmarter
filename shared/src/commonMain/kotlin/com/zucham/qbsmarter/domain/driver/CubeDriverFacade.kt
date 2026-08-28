package com.zucham.qbsmarter.domain.driver

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Vendor-agnostic [SmartCubeDriver] facade that the rest of the app
 * (SolveViewModel, AppLifecycle) binds against via Koin. Forwards
 * `send` to whichever real driver
 * ([com.zucham.qbsmarter.domain.driver.gan.GanCubeDriver] or
 * [com.zucham.qbsmarter.domain.driver.moyu.MoyuCubeDriver]) the
 * [com.zucham.qbsmarter.data.ble.ConnectionOrchestrator] has currently
 * activated, and re-publishes the active driver's events on its own
 * stable [SharedFlow].
 *
 * **Why a facade.** Each vendor's driver is its own Koin singleton
 * because each owns its own decoding state and per-vendor connect
 * lifecycle (the GAN driver routes between Gen2/3/4 parsers
 * internally; MoYu is single-generation). The rest of the app should
 * not need to know which is currently in use – subscribers want a
 * single [events] flow they can observe across cube swaps, even when
 * the swap is from a GAN cube to a MoYu cube.
 *
 * The orchestrator calls [bindActiveDriver] each time it completes a
 * connect-handshake; the facade swaps its forwarding source and
 * re-publishes that driver's events. Calls to [send] go to whichever
 * driver is bound at the moment of invocation; calls during the brief
 * unbound window (immediately post-disconnect) are dropped silently.
 *
 * **Why not implement [SmartCubeDriver.connect] meaningfully here.**
 * Connect-time wiring (transport construction, encryptor selection,
 * generation detection) is the orchestrator's job and depends on
 * vendor-specific behaviour the facade is deliberately blind to.
 * Calling [connect] on the facade is therefore a no-op – the
 * orchestrator never does so, and it would be confusing if a stray
 * caller could bypass the vendor-detection logic.
 */
class CubeDriverFacade(
    private val scope: CoroutineScope,
) : SmartCubeDriver {

    private val log = Logger.withTag("CubeDriverFacade")

    private val _events = MutableSharedFlow<SmartCubeEvent>(
        replay = 0,
        // Same buffer size as the underlying drivers; a paused subscriber
        // (user navigated away momentarily) doesn't drop moves.
        extraBufferCapacity = 64,
    )
    override val events: SharedFlow<SmartCubeEvent> = _events.asSharedFlow()

    private var activeDriver: SmartCubeDriver? = null
    private var forwardJob: Job? = null

    /**
     * Set the currently-active driver. Cancels any previous forwarder
     * and starts a new one collecting from [driver.events] into our
     * own SharedFlow.
     *
     * Idempotent: calling with the same driver does nothing.
     */
    fun bindActiveDriver(driver: SmartCubeDriver) {
        if (activeDriver === driver && forwardJob?.isActive == true) return
        log.d { "Binding active driver: ${driver::class.simpleName}" }
        forwardJob?.cancel()
        activeDriver = driver
        forwardJob = driver.events
            .onEach { _events.tryEmit(it) }
            .launchIn(scope)
    }

    /**
     * Clear the active driver. Called by the orchestrator on
     * disconnect. Subsequent [send] calls become no-ops.
     */
    fun clearActiveDriver() {
        log.d { "Clearing active driver" }
        forwardJob?.cancel()
        forwardJob = null
        activeDriver = null
    }

    /**
     * No-op. The facade does not manage connect lifecycles – those go
     * through the orchestrator with vendor-specific construction. The
     * method exists only because [SmartCubeDriver] requires it.
     */
    override suspend fun connect(transport: CubeTransport, encryptor: CubeEncryptor) {
        // intentionally empty
    }

    /** Route the command to the currently-bound driver. Drops if none. */
    override suspend fun send(command: SmartCubeCommand) {
        val d = activeDriver ?: run {
            log.d { "send($command) ignored – no active driver" }
            return
        }
        d.send(command)
    }

    /**
     * Disconnect whichever driver is currently bound. Used by
     * [com.zucham.qbsmarter.app.AppLifecycle] to tear down BLE state
     * on backgrounded auto-disconnect. The orchestrator's own
     * `disconnect()` performs richer cleanup (battery cache, active-
     * MAC clearing) and is what most call sites should reach for
     * directly; this method exists for the [SmartCubeDriver]
     * interface contract.
     */
    override suspend fun disconnect() {
        activeDriver?.disconnect()
        clearActiveDriver()
    }
}
