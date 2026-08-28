package com.zucham.qbsmarter.data.ble

import co.touchlab.kermit.Logger
import com.zucham.qbsmarter.data.db.DevicesRepository
import com.zucham.qbsmarter.domain.driver.CubeDriverFacade
import com.zucham.qbsmarter.domain.driver.CubeVendor
import com.zucham.qbsmarter.domain.driver.SmartCubeCommand
import com.zucham.qbsmarter.domain.driver.SmartCubeEvent
import com.zucham.qbsmarter.domain.driver.gan.GanCubeDriver
import com.zucham.qbsmarter.domain.driver.gan.GanEncryptor
import com.zucham.qbsmarter.domain.driver.gan.GanGeneration
import com.zucham.qbsmarter.domain.driver.gan.ganSaltFromMac
import com.zucham.qbsmarter.domain.driver.moyu.MoyuConstants
import com.zucham.qbsmarter.domain.driver.moyu.MoyuCubeDriver
import com.zucham.qbsmarter.domain.driver.moyu.MoyuEncryptor
import com.zucham.qbsmarter.domain.driver.moyu.moyuSaltFromMac
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Long-lived owner of the connect-and-handshake flow. Lives as a Koin
 * singleton so per-screen VM lifecycles don't tear down a connection in
 * progress: a user who taps Connect on the Devices screen and immediately
 * navigates back to Solve still gets INFO + FACELETS + BATTERY delivered.
 *
 * Two concerns combined here on purpose:
 *   1. Initiating a connection = `connect(device)` does the BLE handshake,
 *      waits for service discovery + CCCD descriptor write, then issues the
 *      three startup commands.
 *   2. Persisting & surfacing INFO/BATTERY events. Hardware events update
 *      the cubes table; Battery events go into a transient StateFlow.
 *
 * Both have to live longer than any VM, so they share the same scope
 * (the app-wide singleton scope from Koin).
 *
 * **Vendor branching.** Two cube vendors are supported today, each with
 * its own driver implementation: [GanCubeDriver] (covering GAN's three
 * protocol generations Gen2/3/4 internally) and [MoyuCubeDriver]
 * (covering the MoYu WeiLong V10 AI). The orchestrator picks one at
 * connect time by matching the cube's advertised BLE service UUIDs via
 * [CubeVendor.detect]. The choice is recorded both in the [cubes] DB
 * row (via [DevicesRepository.updateVendor]) and, more importantly, on
 * the [CubeDriverFacade] which re-publishes the active driver's events
 * on a single stable [SmartCubeEvent] flow that the rest of the app
 * subscribes to without knowing which vendor is in use.
 */
class ConnectionOrchestrator(
    private val ble: BleManager,
    private val ganDriver: GanCubeDriver,
    private val moyuDriver: MoyuCubeDriver,
    private val facade: CubeDriverFacade,
    private val devicesRepo: DevicesRepository,
    private val scope: CoroutineScope,
) {
    private val log = Logger.withTag("ConnectionOrchestrator")

    /**
     * MAC of the cube currently being connected to or connected.
     *
     * Exposed as a flow so
     * the Devices screen can light up the right row's "Connecting…" UI
     * during the handshake. Set inside [connect]'s coroutine right
     * before the new BLE connect kicks off (so the row's spinner
     * appears as soon as `connectionState` flips to `CONNECTING`).
     * Cleared in [disconnect].
     *
     * Subtlety: when [connect] is switching from one cube to another,
     * the new MAC is **not** set until after the previous connection
     * has been fully torn down and acknowledged. Setting it earlier
     * would let the long-lived Hardware/Battery event handlers
     * (which read `_activeMac.value`) attribute trailing events from
     * the old cube to the new MAC.
     *
     * The MAC is the only stable identifier we have at the moment
     * connect is initiated (we don't yet know which paired-cube row
     * matches via id; the UI cross-references by MAC).
     */
    private val _activeMac = MutableStateFlow<String?>(null)
    val activeMac: StateFlow<String?> = _activeMac.asStateFlow()

    /**
     * Live battery levels keyed by cube MAC. Exposed for the Devices
     * screen's per-row indicator and for any other consumer that wants
     * to show "X%". Cleared on disconnect.
     */
    private val _batteryByMac = MutableStateFlow<Map<String, Int>>(emptyMap())
    val batteryByMac: StateFlow<Map<String, Int>> = _batteryByMac.asStateFlow()

    private var connectJob: Job? = null

    /**
     * Wall-clock timestamp of the most recent Facelets resync request.
     * Used to debounce: a burst of MovesMissed events during a flaky BLE
     * window shouldn't generate dozens of GATT writes – one resync covers
     * all of them.
     */
    private var lastResyncRequestMs: Long = 0L

    /**
     * Set once a [SmartCubeEvent.Hardware] has actually been received for
     * the cube currently on the wire. Drives the retry in
     * [ensureHardwareInfo]; cleared whenever [_activeMac] changes.
     */
    private var hardwareReceived: Boolean = false

    /**
     * Set once gyro data has actually been seen from the cube currently
     * on the wire. Pure write-suppression — gyro streams continuously and
     * the database only needs telling once.
     */
    private var gyroObserved: Boolean = false

    init {
        // Persist Hardware events to the DB; cache Battery in memory.
        // Both listeners live forever so they don't miss an event because
        // the user happened to be on a different screen at that moment.
        //
        // We subscribe to [facade.events] rather than any concrete driver's
        // events directly. The facade is bound to whichever vendor's driver
        // is currently active (set in [connect]'s body via
        // [CubeDriverFacade.bindActiveDriver]), so this listener
        // automatically sees events from the right vendor without needing
        // to be re-bound on every cube swap.
        facade.events
            .onEach { event ->
                when (event) {
                    is SmartCubeEvent.Hardware -> _activeMac.value?.let { mac ->
                        // Stops the retry in [ensureHardwareInfo].
                        hardwareReceived = true
                        // The hardware name is logged because it's the key
                        // the Gen4 gyro allow-list matches on: if a gyro
                        // cube we don't know about shows up, this line is
                        // what identifies it.
                        log.d {
                            "INFO ($mac, ${event.vendor}): name='${event.name}' " +
                                "hw=${event.hwVersion} sw=${event.swVersion} " +
                                "gyro=${event.gyroSupported ?: "unknown"}"
                        }
                        devicesRepo.updateHardwareInfo(
                            mac = mac,
                            hwVersion = event.hwVersion,
                            swVersion = event.swVersion,
                            gyroSupported = event.gyroSupported,
                        )
                        // The vendor on the row is normally stamped right
                        // after service-UUID detection (see [connect]),
                        // well before this event arrives, so this
                        // additional write is usually a no-op overwrite
                        // with the same value. It exists for two edge
                        // cases:
                        //   • A future protocol change where service
                        //     detection is ambiguous and the Hardware
                        //     payload is the more reliable surface.
                        //   • A bundle imported with the wrong vendor
                        //     (`'gan'` default) – the first real
                        //     Hardware event will correct it.
                        devicesRepo.updateVendor(mac = mac, vendor = event.vendor)
                    }
                    // Receiving gyro data is proof the cube has a
                    // gyroscope, and it outranks anything the hardware
                    // handshake did or didn't manage to tell us. Gyro
                    // notifications are unsolicited on cubes with the
                    // sensor, so this lands within about a second of
                    // connecting.
                    //
                    // It's the safety net under every failure mode of
                    // declared capability: a GAN Gen4 hardware name
                    // missing from the allow-list, a Gen2 capability bit
                    // that reads 0 on a cube that plainly has the sensor,
                    // or a hardware handshake that never completes at
                    // all. Any of those used to leave the cube stuck at
                    // "gyro: unknown" with the Gyro button hidden.
                    is SmartCubeEvent.Gyro -> {
                        if (!gyroObserved) {
                            _activeMac.value?.let { mac ->
                                gyroObserved = true
                                log.d { "GYRO ($mac): sensor confirmed by observed gyro data" }
                                devicesRepo.markGyroSupported(mac)
                            }
                        }
                    }
                    is SmartCubeEvent.Battery -> _activeMac.value?.let { mac ->
                        log.d { "BATTERY ($mac): ${event.level}%" }
                        _batteryByMac.value = _batteryByMac.value + (mac to event.level)
                    }
                    is SmartCubeEvent.MovesMissed -> {
                        // The parser saw a serial-number jump bigger than
                        // the cube's on-board replay buffer (7 moves for
                        // GAN Gen2, 5 for MoYu V10, FIFO-managed for GAN
                        // Gen3/4), which means some moves were lost
                        // forever. The only way to recover the true
                        // state is a fresh Facelets snapshot. Debounced
                        // so a noisy BLE link doesn't generate a flood
                        // of GATT writes.
                        val now = event.deviceTimestamp
                        if (now - lastResyncRequestMs >= RESYNC_DEBOUNCE_MS) {
                            lastResyncRequestMs = now
                            log.w {
                                "MOVES MISSED (~${event.missedCount}): requesting facelets resync"
                            }
                            scope.launch {
                                runCatching { facade.send(SmartCubeCommand.RequestFacelets) }
                            }
                        }
                    }
                    else -> Unit
                }
            }
            .launchIn(scope)
    }

    /**
     * Connect to [device] and run the post-connect handshake. The user can
     * navigate away mid-flow without breaking it – we run on the orchestrator's
     * own scope.
     *
     * **Existing-connection handling.** If there's already an active GATT
     * (because the user is jumping from cube A to cube B, or because a
     * previous connect attempt didn't finish unwinding), this method
     * tears it down cleanly first and waits for the BLE stack to
     * acknowledge before initiating the new connect. Without that
     * await, [BleManager.connectToDevice] would refuse the new connect
     * (its defensive guard rejects calls when `bluetoothGatt != null`)
     * and the user's tap would silently fail.
     *
     * If connect is already in flight from a previous call to this
     * method, the previous job is cancelled.
     */
    fun connect(device: BleDevice, userId: String) {
        connectJob?.cancel()
        connectJob = scope.launch {
            // Persist before connecting so a flaky connect still leaves a row.
            devicesRepo.rememberCube(userId, device.address, device.name)

            // If a previous BLE connection is alive (state CONNECTED) or
            // being established (state CONNECTING – connectGatt has run
            // but the handshake hasn't completed), tear it down and wait
            // for the platform to acknowledge before initiating the new
            // connect. Both states leave [BleManager.bluetoothGatt]
            // non-null, and [BleManager.connectToDevice]'s defensive
            // guard refuses if the GATT is alive – so without this
            // teardown the new connect would silently fail. This is the
            // central enforcement point; every callable path that asks
            // to connect a new cube routes through here. See
            // [BleManager.disconnect] for the close-ordering details
            // and [BleManager.connectToDevice] for the defensive guard.
            //
            // Note: we deliberately do this BEFORE setting [_activeMac]
            // to the new device. While the previous cube is still on
            // the wire, any in-flight Hardware/Battery events from it
            // could otherwise be attributed to the new MAC by the
            // long-lived handlers that read `_activeMac.value`.
            val priorState = ble.connectionState.value
            if (priorState == ConnectionState.CONNECTED || priorState == ConnectionState.CONNECTING) {
                log.d { "connect: tearing down previous connection (state=$priorState) before reconnecting" }
                ble.disconnect()
                runCatching {
                    withTimeout(EXISTING_CONNECTION_TIMEOUT_MS) {
                        ble.connectionState.first { it == ConnectionState.DISCONNECTED }
                    }
                }
                // If the wait timed out, BleManager's own 1.5 s
                // force-close fallback has already fired. Either way
                // we proceed: connectToDevice's own guard will refuse
                // and surface ERROR if the GATT is somehow still alive.
            }

            // Set the active-MAC flow immediately before the new BLE
            // connect kicks off so the Devices screen can light up the
            // right "Connecting…" row the instant `connectToDevice`
            // flips state to CONNECTING. The flow drives a per-row
            // spinner.
            _activeMac.value = device.address
            // New cube on the wire: it has to establish its hardware
            // info and earn its gyro flag on its own evidence, not
            // inherit the previous cube's.
            hardwareReceived = false
            gyroObserved = false

            // Tear down both vendor drivers defensively. Only the
            // previously-active one will have any live state to clear,
            // but the others' `disconnect()` is a cheap no-op (sets
            // their internal transport/encryptor refs to null and
            // cancels an already-cancelled or never-started ingest
            // job). Doing both keeps the code branch-free at this
            // point – the vendor choice for THIS cube isn't known yet,
            // and the previous cube might have been the other vendor.
            ganDriver.disconnect()
            moyuDriver.disconnect()
            facade.clearActiveDriver()
            ble.connectToDevice(device)

            // CRITICAL ordering, five steps:
            //   1. Wait for service discovery – connectGatt() returns
            //      immediately and we can't write before discovery
            //      finishes.
            //   2. **Detect the cube's vendor** by matching the
            //      advertised service UUIDs against the known vendors
            //      via [CubeVendor.detect]. This decides which driver
            //      (GAN or MoYu) to dispatch to.
            //   3. Build the vendor-appropriate encryptor and transport.
            //      GAN and MoYu both use AES-128 CBC with a MAC-derived
            //      salt, but they have *different* root key/IV pairs;
            //      [GanEncryptor] and [MoyuEncryptor] are thin factories
            //      over the shared [AesCbcMacSaltEncryptor]. Transport
            //      UUIDs come from the matching constants ([GanGeneration]
            //      for GAN, [MoyuConstants] for MoYu).
            //   4. Connect the matching driver, bind it on the facade
            //      so subscribers (including this orchestrator's init
            //      block) see its events. For GAN, this also passes the
            //      detected [GanGeneration] so the GAN driver selects
            //      the right per-generation parser internally.
            //   5. Wait for the descriptor write to complete
            //      (notificationsReady) BEFORE issuing INFO/FACELETS/
            //      BATTERY. Without this gate the command writes race
            //      against the descriptor write and the cube either
            //      drops them or replies into a void.
            val advertisedServices = run {
                lateinit var snapshot: List<String>
                ble.discoveredServices.first { services ->
                    val uuids = services.map { it.uuid }
                    if (CubeVendor.detect(uuids) != null) {
                        snapshot = uuids
                        true
                    } else false
                }
                snapshot
            }
            val detectedVendor: CubeVendor = CubeVendor.detect(advertisedServices)
                ?: error("Unreachable – discoveredServices.first guarantees a match")
            log.d { "Detected vendor $detectedVendor for ${device.address}" }
            // Stamp the vendor onto the cube row immediately. The
            // [updateVendor] write is a no-op if the row was already
            // tagged with the same value (e.g. a reconnect to a
            // previously-paired cube of the same vendor).
            devicesRepo.updateVendor(mac = device.address, vendor = detectedVendor)

            when (detectedVendor) {
                CubeVendor.GAN -> {
                    val generation = GanGeneration.detect(advertisedServices)
                        ?: error("CubeVendor.detect returned GAN but no GanGeneration matched")
                    log.d { "GAN protocol generation $generation for ${device.address}" }
                    val encryptor = GanEncryptor(ganSaltFromMac(device.address))
                    val transport = BleCubeTransport(
                        ble = ble,
                        serviceUuid = generation.serviceUuid,
                        commandCharUuid = generation.commandCharUuid,
                        stateCharUuid = generation.stateCharUuid,
                    )
                    // Bind BEFORE connecting so the facade's forward job
                    // is collecting from this driver before the first
                    // event lands. (Drivers buffer 64 events so a brief
                    // bind-after-connect window wouldn't actually drop
                    // anything, but bind-first is clearer.)
                    facade.bindActiveDriver(ganDriver)
                    ganDriver.connect(transport, encryptor, generation)
                }
                CubeVendor.MOYU -> {
                    val encryptor = MoyuEncryptor(moyuSaltFromMac(device.address))
                    val transport = BleCubeTransport(
                        ble = ble,
                        serviceUuid = MoyuConstants.SERVICE_UUID,
                        commandCharUuid = MoyuConstants.WRITE_CHAR_UUID,
                        stateCharUuid = MoyuConstants.NOTIFY_CHAR_UUID,
                    )
                    facade.bindActiveDriver(moyuDriver)
                    moyuDriver.connect(transport, encryptor)
                }
            }

            val ready = withTimeoutOrNull(NOTIFICATIONS_READY_TIMEOUT_MS) {
                ble.notificationsReady.first { it }
            }
            if (ready == null) {
                log.w { "Notifications never became ready; sending commands anyway" }
            }

            // Spaced commands so back-to-back GATT writes don't overflow
            // the queue on flaky stacks. Each is best-effort – failures
            // don't tear down the connection.
            //
            // A settling delay before the first write, and it is not
            // ceremony. `notificationsReady` flips inside the CCCD
            // descriptor-write callback, and on several Android stacks a
            // characteristic write issued in the same breath as that
            // callback is silently dropped — no error, no reply, the
            // cube simply never hears it. Whichever command goes first
            // absorbs that risk.
            delay(FIRST_COMMAND_SETTLE_MS)
            runCatching { facade.send(SmartCubeCommand.RequestHardware) }
            delay(POST_CONNECT_GAP_MS)
            runCatching { facade.send(SmartCubeCommand.RequestFacelets) }
            delay(POST_CONNECT_GAP_MS)
            runCatching { facade.send(SmartCubeCommand.RequestBattery) }
            // MoYu V10 specifically: ensure gyro is in the known-on
            // state regardless of whatever the previous client session
            // (likely the official WCU app) left it in. The cube
            // remembers the setting across reconnects, so this is the
            // only point we can reliably re-assert it. The ack is a
            // 0xAC reply which the driver swallows.
            if (detectedVendor == CubeVendor.MOYU) {
                delay(POST_CONNECT_GAP_MS)
                runCatching { moyuDriver.enableGyro() }
            }

            ensureHardwareInfo()
        }
    }

    /**
     * Keep asking for hardware info until the cube actually answers.
     *
     * The hardware reply is the only source of the cube's declared
     * gyro capability, and it is uniquely fragile: unlike facelets and
     * battery — which the app re-requests naturally over the life of a
     * session — it is asked for exactly once, and it is asked for
     * *first*, right after the CCCD descriptor write, which is precisely
     * where Android stacks are most likely to drop a characteristic
     * write. Lose that single write and the cube reports "hardware:
     * blank, gyro: unknown" for as long as it stays paired, because
     * nothing ever asks again. Moves, facelets and battery all keep
     * working, which makes it look like a UI bug rather than a lost
     * packet.
     *
     * So: re-send on a fixed interval until a [SmartCubeEvent.Hardware]
     * arrives (the event handler sets [hardwareReceived]), giving up
     * after [HARDWARE_RETRY_ATTEMPTS]. Cheap — a couple of 20-byte
     * writes on an idle link — and it converges on the first retry in
     * the common case.
     *
     * Giving up is not a failure state: capability detection has a
     * second, independent path (see the `Gyro` branch of the event
     * handler), so a cube that never answers this handshake can still
     * light up the Gyro button by simply sending gyro data.
     */
    private suspend fun ensureHardwareInfo() {
        repeat(HARDWARE_RETRY_ATTEMPTS) { attempt ->
            delay(HARDWARE_RETRY_INTERVAL_MS)
            if (hardwareReceived || _activeMac.value == null) return
            log.w { "No hardware info yet; re-requesting (attempt ${attempt + 2})" }
            runCatching { facade.send(SmartCubeCommand.RequestHardware) }
        }
        if (!hardwareReceived) {
            log.w {
                "Cube never answered RequestHardware; hardware info and declared " +
                    "gyro capability stay unknown for this session"
            }
        }
    }

    /**
     * Tear down the current connection. Safe to call at any point during
     * the lifecycle of [connect]:
     *
     *   • If the connect job is mid-handshake (waiting on service
     *     discovery, on `notificationsReady`, or in the spaced
     *     RequestHardware/Facelets/Battery writes), cancelling it
     *     interrupts the suspend points cleanly. We then **await the
     *     BLE stack acknowledging the disconnect** before clearing
     *     `_activeMac`. Without that wait, a subsequent user action
     *     (Pair new, switch profile, scan again) would race the in-
     *     flight teardown – exactly the family of bugs the close-
     *     ordering fix in [BleManager.disconnect] was meant to avoid.
     *   • If no connection is in flight, the BLE state is already
     *     DISCONNECTED and the await resolves immediately.
     *   • If the BLE stack hangs (out-of-range cube, wedged radio),
     *     the wait times out after [DISCONNECT_AWAIT_TIMEOUT_MS] and
     *     we proceed with the local cleanup anyway. BleManager's own
     *     1.5 s force-close fallback handles the radio side.
     *
     * Battery cleanup happens regardless of whether the await succeeded:
     * a stale "X%" indicator on a disconnected cube is more confusing
     * than no indicator. Same for `_activeMac`.
     */
    fun disconnect() {
        scope.launch {
            connectJob?.cancel()
            connectJob = null
            // Best-effort driver-level cleanup for both vendors. Only one
            // is realistically active at a time, but both `disconnect()`
            // implementations are idempotent and cheap when there's
            // nothing live (transport/encryptor refs are already null,
            // ingest job is already cancelled). Clearing the facade
            // ensures any subsequent `send` before the next connect
            // becomes a no-op rather than reaching a torn-down driver.
            runCatching { ganDriver.disconnect() }
            runCatching { moyuDriver.disconnect() }
            facade.clearActiveDriver()
            ble.disconnect()
            // Await the BLE stack acknowledging the disconnect. The
            // outer 2 s timeout matches the upstream waits in
            // DevicesViewModel.forget / startScan; under normal
            // conditions BleManager's internal 1.5 s fallback resolves
            // first, well before this.
            runCatching {
                withTimeout(DISCONNECT_AWAIT_TIMEOUT_MS) {
                    ble.connectionState.first { it == ConnectionState.DISCONNECTED }
                }
            }
            _activeMac.value?.let { _batteryByMac.value = _batteryByMac.value - it }
            _activeMac.value = null
            hardwareReceived = false
            gyroObserved = false
        }
    }

    private companion object {
        // BLE service / characteristic UUIDs come from the auto-detected [GanGeneration]
        // in [connect]. See [GanGeneration] for the per-generation values.

        const val POST_CONNECT_GAP_MS = 120L
        const val NOTIFICATIONS_READY_TIMEOUT_MS = 3000L

        /**
         * Pause between `notificationsReady` and the first command
         * write. See the comment at the call site: a write issued in the
         * same breath as the CCCD descriptor callback is silently
         * dropped on some Android stacks.
         */
        const val FIRST_COMMAND_SETTLE_MS = 150L

        /**
         * How long to wait for a hardware reply before asking again.
         * Comfortably longer than the ~150 ms a cube normally takes, so
         * a healthy handshake never triggers a retry at all.
         */
        const val HARDWARE_RETRY_INTERVAL_MS = 700L

        /**
         * How many times to re-ask for hardware info. Three retries over
         * ~2 s: enough to ride out a dropped write or a slow cube,
         * short enough that a cube which genuinely doesn't implement the
         * command stops being pestered quickly.
         */
        const val HARDWARE_RETRY_ATTEMPTS = 3

        /**
         * Upper bound on how long [connect] waits for an existing GATT
         * link to fully tear down before initiating the new connect.
         * Slightly longer than [BleManager]'s own internal disconnect
         * timeout (1500 ms) so under normal conditions BleManager's
         * fallback resolves the state-flow first; this is the outer
         * safety net. If the wait times out, [connect] proceeds –
         * BleManager.connectToDevice's own defensive guard refuses if
         * the GATT is somehow still alive and surfaces ERROR.
         */
        const val EXISTING_CONNECTION_TIMEOUT_MS = 2000L

        /**
         * Upper bound on how long [disconnect] waits for the BLE stack
         * to acknowledge teardown after we've cancelled the connect
         * job and called `ble.disconnect()`. Same 2 s outer bound as
         * the existing-connection wait above, for the same reason:
         * BleManager's 1.5 s internal fallback resolves the state
         * flow first under normal conditions; this is the safety net
         * for when the radio is wedged or the cube is out of range.
         * Past this we proceed with local cleanup anyway – a wedged
         * radio is no longer recoverable from this layer, and the
         * caller (user-initiated cancel) needs the orchestrator state
         * to converge.
         */
        const val DISCONNECT_AWAIT_TIMEOUT_MS = 2000L

        /**
         * Minimum interval between auto-Facelets resync requests. A noisy
         * BLE window can produce a chain of MovesMissed events; without
         * debouncing we'd issue a GATT write for each, queueing up a flood
         * of duplicate Facelets responses. 1500 ms is generous: the cube
         * usually replies to a Facelets request within ~150ms, so any
         * MovesMissed received afterward represents new data warranting
         * another resync.
         */
        const val RESYNC_DEBOUNCE_MS = 1500L
    }
}
