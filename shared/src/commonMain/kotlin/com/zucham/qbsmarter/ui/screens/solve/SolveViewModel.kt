package com.zucham.qbsmarter.ui.screens.solve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.zucham.qbsmarter.data.ble.BleManager
import com.zucham.qbsmarter.data.ble.ConnectionOrchestrator
import com.zucham.qbsmarter.data.ble.ConnectionState
import com.zucham.qbsmarter.data.cache.AppCache
import com.zucham.qbsmarter.data.db.PairedCube
import com.zucham.qbsmarter.data.db.SettingsRepository
import com.zucham.qbsmarter.data.db.SolveRow
import com.zucham.qbsmarter.data.db.SolvesRepository
import com.zucham.qbsmarter.data.profile.ActiveProfile
import com.zucham.qbsmarter.domain.cube.CubeMove
import com.zucham.qbsmarter.domain.cube.CubeState
import com.zucham.qbsmarter.domain.cube.RubiksCube
import com.zucham.qbsmarter.domain.cube.applyMove
import com.zucham.qbsmarter.domain.cube.applyMoves
import com.zucham.qbsmarter.domain.driver.SmartCubeCommand
import com.zucham.qbsmarter.domain.driver.SmartCubeDriver
import com.zucham.qbsmarter.domain.driver.SmartCubeEvent
import com.zucham.qbsmarter.domain.timing.SolveTimer
import com.zucham.qbsmarter.ui.screens.solve.stats.SolveSession
import com.zucham.qbsmarter.ui.screens.solve.stats.StatRegistry
import com.zucham.qbsmarter.ui.theme.ThemeController
import com.zucham.qbsmarter.util.ScreenKeeper
import com.zucham.qbsmarter.util.currentTimeMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/**
 * Drives the Solve screen. Holds the cube model, scramble + scramble target,
 * timer, inspection countdown, and the phase state machine.
 *
 * **Profile-reactive design.** The userId is *not* captured at construction
 * time – that would make the VM stale across profile switches. Instead we
 * read [activeProfile.idSnapshot] at the moment of each write (insert solve,
 * etc.), and expose reactive flows that key off [activeProfile.id] for
 * reads. Switching profiles while this VM is alive cleanly swaps the
 * surfaced data.
 *
 * State tracking model: we maintain our own [logicalState] CubeState that
 * mirrors what's on the cube according to incoming [SmartCubeEvent.Move]
 * events. This is independent of [RubiksCube.state] because the cube's
 * state is updated asynchronously inside the visual move queue (animations)
 * and we need a synchronous, race-free ground truth to detect phase
 * transitions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SolveViewModel(
    val cube: RubiksCube,
    private val driver: SmartCubeDriver,
    private val solvesRepo: SolvesRepository,
    private val settingsRepo: SettingsRepository,
    private val screenKeeper: ScreenKeeper,
    ble: BleManager,
    orchestrator: ConnectionOrchestrator,
    private val activeProfile: ActiveProfile,
    private val cache: AppCache,
    private val scrambleGenerator: ScrambleGenerator = ScrambleGenerator(),
    val statRegistry: StatRegistry = StatRegistry(),
    val themeController: ThemeController,
) : ViewModel() {

    private val log = Logger.withTag("SolveVM")

    private val timer = SolveTimer()
    private val inspection = InspectionTimer(viewModelScope)

    private val _phase = MutableStateFlow(SolvePhase.IDLE)
    val phase: StateFlow<SolvePhase> = _phase.asStateFlow()

    private val _scramble = MutableStateFlow("")
    val scramble: StateFlow<String> = _scramble.asStateFlow()

    /** Parsed scramble moves, kept in sync with [_scramble]. */
    private var scrambleMoves: List<CubeMove> = emptyList()

    /**
     * Precomputed cube states for every prefix of the scramble. Index k holds
     * the state after applying the first k scramble moves to SOLVED. Used to
     * find the current progress in O(scramble.size) per move. Recomputed only
     * when the scramble itself changes.
     */
    private var scramblePrefixStates: List<CubeState> = listOf(CubeState.SOLVED)

    /** Independent logical state, advanced by every Move event. */
    private var logicalState: CubeState = CubeState.SOLVED

    private val _scrambleProgress = MutableStateFlow(0)
    val scrambleProgress: StateFlow<Int> = _scrambleProgress.asStateFlow()

    /** Quarter-turn moves the user has made since the last on-rails position. */
    private val _deviationMoves = MutableStateFlow<List<CubeMove>>(emptyList())

    /**
     * Public deviation flow with a grace window for half-turns. When the
     * next-up scramble token is `R2` (or any half-turn) and the user has
     * just done its first quarter (`R`), the raw deviation list briefly
     * shows `[R]` – it'll be cleared once the second `R` lands and brings
     * us back to a prefix state. Without a delay the user sees a confusing
     * flash of red correction text between the two halves of a single
     * physical motion.
     *
     * The screen consumes this flow directly without further delay; this
     * is the single source of the grace-window behaviour.
     */
    val deviationMoves: StateFlow<List<CubeMove>> =
        combine(_deviationMoves, _scrambleProgress) { devs, prog -> devs to prog }
            .transformLatest { (devs, prog) ->
                if (shouldDelayHalfTurnWarning(devs, prog)) {
                    delay(HALF_TURN_GRACE_MS)
                }
                emit(devs)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val elapsedMs: StateFlow<Long> = timer.elapsedMs
    val running: StateFlow<Boolean> = timer.running
    val inspectionMs: StateFlow<Long> = inspection.remainingMs
    val inspectionRunning: StateFlow<Boolean> = inspection.running

    private val _moveCount = MutableStateFlow(0)
    val moveCount: StateFlow<Int> = _moveCount.asStateFlow()

    /** Recent solves for the stat cards. Sourced from the cache. */
    val history: StateFlow<List<SolveRow>> = cache.recentSolves

    /** Total solves for the active profile. */
    val totalSolveCount: StateFlow<Long> = cache.solveCount

    /**
     * One-shot "new personal best" notification for the Solve screen.
     * Holds the effective duration (ms) of the just-finished
     * solve when it beat the previous best for the active profile;
     * cleared back to null by [dismissPbEvent] when the user dismisses
     * the celebration dialog.
     *
     * Why a `MutableStateFlow<Long?>` instead of a `Channel`/`SharedFlow`:
     * the screen observes via `collectAsState` and re-displays the dialog
     * automatically across screen recompositions (e.g. configuration
     * change) until the user explicitly dismisses. A Channel/SharedFlow
     * would deliver once and lose the "still showing" state on reconfig.
     */
    private val _newPbEvent = MutableStateFlow<Long?>(null)
    val newPbEvent: StateFlow<Long?> = _newPbEvent.asStateFlow()

    /**
     * Snapshot of the just-finished solve. Holds the row id and
     * current penalty/DNF flags so the post-solve action row on the
     * Solve screen can show "+2" / "DNF" / "OK" buttons that reflect
     * the live state of the database.
     *
     * Set by [finishSolve], cleared by [newScramble] and [abortToIdle]
     * (so the buttons disappear when the user moves on or loses the
     * connection).
     *
     * The captured `previousBest` is used by [recomputePbAfterPenalty]
     * to revoke a PB notification if a +2 / DNF makes the solve no
     * longer a record.
     */
    private val _lastSolveInfo = MutableStateFlow<LastSolveInfo?>(null)
    val lastSolveInfo: StateFlow<LastSolveInfo?> = _lastSolveInfo.asStateFlow()

    /**
     * Connection summary for the header indicator: BLE state plus the
     * row of the cube that is actually on the wire.
     *
     * The cube is resolved by MAC from
     * [ConnectionOrchestrator.activeMac], which is the only authoritative
     * answer to "which cube is this". It matters beyond the displayed
     * name: [CubeConnectionSummary.gyroSupported] gates the Gyro button,
     * and reading that off the wrong row would either hide the button on
     * a gyro cube or offer it on one without the sensor.
     *
     * The `paired.firstOrNull()` fallback covers the window between
     * CONNECTED and the orchestrator publishing a MAC, and mirrors the
     * previous behaviour (paired cubes are ordered last-seen first, and
     * connecting refreshes last_seen, so the head of the list is a
     * decent guess).
     */
    val connectionSummary: StateFlow<CubeConnectionSummary> =
        combine(
            ble.connectionState,
            cache.pairedCubes,
            orchestrator.activeMac,
        ) { state, paired, activeMac ->
            val active = activeMac?.let { mac -> paired.firstOrNull { it.mac == mac } }
            CubeConnectionSummary(state = state, cube = active ?: paired.firstOrNull())
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            CubeConnectionSummary(ConnectionState.DISCONNECTED, null),
        )

    /**
     * Whether the 3D cube follows the physical cube's gyroscope.
     *
     * Backed by the per-profile [SettingsRepository.Keys.GYRO_ENABLED]
     * setting, so it survives a restart and swaps with the profile. The
     * flow is the single source of truth the UI renders from; a collector
     * in [init] pushes each value down into the cube model.
     *
     * Seeded from the cube rather than from `false`: [RubiksCube] is an
     * app-wide singleton but this ViewModel is recreated every time the
     * user navigates back to the Solve screen. Starting at `false` would
     * make that collector's first emission switch the gyro *off* before
     * the persisted `true` arrived a moment later – a needless
     * off/on cycle that would throw away the user's re-centering.
     */
    private val _gyroEnabled = MutableStateFlow(cube.gyroscope.enabled)
    val gyroEnabled: StateFlow<Boolean> = _gyroEnabled.asStateFlow()

    /**
     * Whether any turn made on the finished-solve screen starts the next
     * solve, as opposed to the U U' gesture. See
     * [SettingsRepository.Keys.ANY_MOVE_STARTS_NEW_SOLVE].
     *
     * A StateFlow rather than a cache lookup because both consumers need
     * it in a different shape: [handleMove] reads `.value` synchronously
     * on the move hot path, and the Solve screen collects it to show the
     * matching post-solve tip.
     */
    val anyMoveStartsNewSolve: StateFlow<Boolean> =
        activeProfile.id.flatMapLatest { uid ->
            if (uid == null) flowOf(true)
            else settingsRepo.observeBool(
                uid,
                SettingsRepository.Keys.ANY_MOVE_STARTS_NEW_SOLVE,
                default = true,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        newScramble()  // give the user something to look at on first paint

        driver.events.onEach(::onCubeEvent).launchIn(viewModelScope)

        // Gyro preference: load the persisted value (and reload it on a
        // profile switch), then mirror whatever the flow holds into the
        // cube model. Two hops rather than one so the DB is the source of
        // truth for *persistence* while the flow stays the source of
        // truth for *the current session* – a toggle takes effect
        // immediately even for the (theoretical) no-active-profile case
        // where the write can't be persisted.
        gyroEnabledSetting()
            .onEach { _gyroEnabled.value = it }
            .launchIn(viewModelScope)
        // Live tracking is the preference AND a cube on the wire, not
        // the preference alone. A gyroscope left "on" with nothing
        // feeding it isn't harmless: [CubeOrbiter]'s drag-end auto-snap
        // is suppressed for as long as the gyro is enabled (the composed
        // pose isn't axis-aligned, so snapping the drag half achieves
        // nothing), which would leave the cube un-snappable for the
        // whole time it is disconnected. Gating on the connection also
        // means a reconnect resumes tracking on its own, with no second
        // copy of the preference to keep in sync.
        combine(
            _gyroEnabled,
            ble.connectionState,
        ) { enabled, state -> enabled && state == ConnectionState.CONNECTED }
            .distinctUntilChanged()
            .onEach { cube.gyroscope.setEnabled(it) }
            .launchIn(viewModelScope)

        // Keep the screen on while the user is actively solving.
        // "Actively solving" = phase != IDLE – covers SCRAMBLING (user is
        // turning the cube), READY/INSPECTION (waiting for the first
        // move), RUNNING (the timer is ticking), and SOLVED (the result
        // is on screen and the user is reading it). The setting toggle
        // is the master gate: when off, we never inhibit screen-off.
        combine(
            _phase,
            keepScreenOnSetting(),
        ) { phase, enabled -> enabled && phase != SolvePhase.IDLE }
            .onEach { screenKeeper.setKeepScreenOn(it) }
            .launchIn(viewModelScope)

        // When the BLE link drops mid-solve (disconnect, error,
        // permission revoked), bail back to IDLE. We can't reliably read
        // moves anymore, the timer's input has gone stale, and any
        // assumptions the state machine made about an in-flight scramble
        // or RUNNING solve are invalidated. Going to IDLE is the safest
        // "stop everything" – the user can press New Scramble to start
        // again once the cube is back.
        //
        // The transition guard is critical: we only abort when we *had*
        // been connected and just lost it. The initial app state is
        // DISCONNECTED (no cube paired yet), so without this guard we'd
        // immediately abort the freshly-generated first scramble back to
        // IDLE on every cold start.
        //
        // On the false → true transition (just connected) we always
        // start a fresh scramble. Reason: stale state from a previous
        // session can leave the timer/phase machine in a weird limbo
        // (e.g. "–" status forever) when reconnecting after an abort.
        // A clean newScramble() resets timer, inspection, move count,
        // scramble progress, and phase in one shot.
        var wasConnected = false
        ble.connectionState
            .onEach { state ->
                if (state == ConnectionState.CONNECTED) {
                    if (!wasConnected) {
                        wasConnected = true
                        newScramble()
                    }
                } else if (wasConnected && state in CONNECTION_LOSS_STATES) {
                    wasConnected = false
                    // Wipe the cube view along with the solve state. No
                    // more samples or moves are coming, so everything on
                    // screen is a snapshot of a cube we can no longer
                    // see: a pose frozen at whatever angle the link died
                    // at, and a permutation the user is free to change
                    // behind our back. Both read as "broken" rather than
                    // "disconnected". The gyro *preference* survives –
                    // it's a setting, and reconnecting resumes tracking.
                    cube.resetView()
                    logicalState = CubeState.SOLVED
                    abortToIdle()
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Stop everything in flight and return to IDLE. Called on BLE
     * disconnect/error and could be reused later for an explicit "abort"
     * UI control. Does NOT clear the scramble – the user might want to
     * see what they were working on after reconnect – but does cancel
     * the timer, inspection, and progress tracking so a stray late move
     * event can't push us into an inconsistent state.
     */
    private fun abortToIdle() {
        // Don't bother if we're already idle – avoids a needless
        // screen-keep flip when the connection drops while we're
        // already on the idle screen.
        if (_phase.value == SolvePhase.IDLE) return
        timer.reset()
        inspection.cancel()
        _moveCount.value = 0
        _scrambleProgress.value = 0
        _deviationMoves.value = emptyList()
        lastSolvedGestureMove = null
        _newPbEvent.value = null
        _lastSolveInfo.value = null
        _phase.value = SolvePhase.IDLE
    }

    /**
     * Reactive `display.keepScreenOn` setting for the active profile.
     * Defaults to true. Re-subscribes when the profile changes.
     */
    private fun keepScreenOnSetting() =
        activeProfile.id.flatMapLatest { uid ->
            if (uid == null) flowOf(true)
            else settingsRepo.observeBool(uid, SettingsRepository.Keys.KEEP_SCREEN_ON, default = true)
        }

    /**
     * Reactive `solving.gyroEnabled` setting for the active profile.
     * Defaults to false. Re-subscribes when the profile changes.
     */
    private fun gyroEnabledSetting() =
        activeProfile.id.flatMapLatest { uid ->
            if (uid == null) flowOf(false)
            else settingsRepo.observeBool(uid, SettingsRepository.Keys.GYRO_ENABLED, default = false)
        }

    // -- User actions ------------------------------------------------------

    /**
     * Reset the solve state machine and produce a fresh scramble.
     *
     * No length parameter: [ScrambleGenerator] now samples a length per
     * call from a tight range (~19–23) to mimic TNoodle's variable
     * output, so a caller-provided length would be ignored. If a future
     * "competition mode" wants a fixed length, plumb that through the
     * generator directly (it still accepts an explicit length).
     */
    fun newScramble() {
        timer.reset()
        inspection.cancel()
        _moveCount.value = 0
        cube.resetState()
        logicalState = CubeState.SOLVED
        // Implicitly dismiss any pending PB celebration: the user has
        // moved on to the next solve, the moment has passed.
        _newPbEvent.value = null
        // Hide the post-solve penalty buttons – they belong to the
        // solve we just finished, not the new one we're starting.
        _lastSolveInfo.value = null

        val s = scrambleGenerator.generate()
        _scramble.value = s
        scrambleMoves = CubeMove.parseAll(s)
        scramblePrefixStates = buildList(scrambleMoves.size + 1) {
            var cur = CubeState.SOLVED
            add(cur)
            for (m in scrambleMoves) {
                cur = applyMoves(cur, listOf(m))
                add(cur)
            }
        }
        _scrambleProgress.value = 0
        _deviationMoves.value = emptyList()
        lastSolvedGestureMove = null
        _phase.value = SolvePhase.SCRAMBLING
    }

    /**
     * "Reset state" button. Logical state goes back to SOLVED. If a
     * scramble is loaded we re-enter [SolvePhase.SCRAMBLING] so the
     * scramble line keeps reacting to cube turns.
     */
    fun resetState() {
        viewModelScope.launch { runCatching { driver.send(SmartCubeCommand.RequestReset) } }

        cube.resetState()
        logicalState = CubeState.SOLVED
        timer.reset()
        inspection.cancel()
        _moveCount.value = 0
        _scrambleProgress.value = 0
        _deviationMoves.value = emptyList()
        lastSolvedGestureMove = null
        _phase.value = SolvePhase.IDLE

        newScramble()
    }

    /**
     * Force the visualisation to match the current logical state.
     *
     * Called when the Solve screen becomes visible (it's a no-op when
     * already in sync). Rationale: while the user is on a different
     * screen, the move queue is stopped (CubeView is detached and
     * disposes the cube's coroutine scope) so any moves received over
     * BLE during that window pile up in the channel without animating.
     * The logical state – maintained synchronously in this VM – stays
     * correct, but the visual ends up replaying a stale backlog the
     * moment the user navigates back. Forcing a resync to
     * [logicalState] on screen entry skips that replay entirely.
     *
     * Uses [RubiksCube.catchUpVisualTo] (not [RubiksCube.resync]) so
     * that center orientations are preserved when the visual was
     * already in sync. resync zeroes centers (correct for Facelets
     * resync where we have no center info); catchUpVisualTo only
     * routes through the queue if state actually differs.
     */
    fun resyncVisualToLogical() {
        cube.catchUpVisualTo(logicalState)
    }

    /**
     * "Reset orientation" button. Re-homes both layers of the cube's
     * orientation – the manual drag offset and the gyro baseline. See
     * [RubiksCube.animateOrientationToIdentity].
     */
    fun resetOrientation() {
        cube.animateOrientationToIdentity()
    }

    /**
     * "Gyro" button. Flips the preference, applies it to the cube through
     * the [_gyroEnabled] collector wired in [init], and persists it for
     * the active profile.
     *
     * The write echoes back through [gyroEnabledSetting]; the resulting
     * emission is identical to what [_gyroEnabled] already holds, and
     * StateFlow drops duplicates, so there is no feedback loop.
     */
    fun toggleGyro() {
        val next = !_gyroEnabled.value
        _gyroEnabled.value = next
        if (!next) {
            // Apply the switch-off here rather than waiting for the
            // [_gyroEnabled] collector: the snap below and the gyro's
            // own ease-back-to-identity should start on the same frame,
            // and a flow hop would stagger them by a dispatch. The
            // collector still runs and setEnabled is idempotent.
            cube.gyroscope.setEnabled(false)
            // Square the cube up. The orbiter's drag-end auto-snap is
            // suppressed for as long as the gyro is on, so the drag
            // offset can be at any angle by the time the user switches
            // off; this is the moment it becomes meaningful (and
            // possible) to land it on an axis again.
            cube.snapOrientationToAxes()
        }
        val uid = activeProfile.idSnapshot()
        if (uid == null) {
            log.w { "toggleGyro: no active profile, not persisting" }
            return
        }
        settingsRepo.setBool(uid, SettingsRepository.Keys.GYRO_ENABLED, next)
    }

    // -- Driver events -----------------------------------------------------

    private fun onCubeEvent(event: SmartCubeEvent) {
        log.d { "received $event in phase ${_phase.value}" }
        when (event) {
            is SmartCubeEvent.Move -> handleMove(event)
            is SmartCubeEvent.Facelets -> handleFacelets(event)
            // Fed in unconditionally, not just while the toggle is on:
            // the gyroscope keeps the latest sample so that enabling the
            // feature (or hitting Reset orientation) has a real pose to
            // work from on the spot rather than waiting for the next
            // packet. It ignores samples for rendering while disabled.
            is SmartCubeEvent.Gyro -> cube.gyroscope.onSample(event.quat)
            else -> Unit
        }
    }

    /**
     * Reconcile against a hardware state snapshot.
     *
     * **Facelets events are not all solicited.** Gen3 and Gen4 cubes emit
     * one periodically, on their own, as the carrier for their
     * missed-move recovery protocol – the parser compares the snapshot's
     * serial against the last move it saw and backfills any gap. Gen2
     * only ever answers an explicit request, which is why this path used
     * to look harmless.
     *
     * It was not harmless. The previous implementation cleared
     * [_deviationMoves] and re-derived [_scrambleProgress] on *every*
     * Facelets event, so on a Gen3/Gen4 cube every periodic heartbeat
     * wiped the correction hint and – because a deviated state matches
     * no scramble prefix – reset the progress marker to zero. The user
     * saw their red correction move appear and then, a beat later, the
     * scramble jump back to the start, which made scramble mistakes
     * impossible to walk back.
     *
     * So the snapshot is treated as what it actually is – a claim about
     * the cube's state – and acted on only when that claim differs from
     * what move tracking already believes:
     *
     *  * **Snapshot agrees** (the overwhelmingly common case): a
     *    heartbeat confirming we're in sync. Do nothing at all. In
     *    particular don't call [RubiksCube.resync], which would enqueue
     *    a state reset purely to zero the centre orientations and make
     *    the centres visibly snap on every heartbeat.
     *  * **Snapshot differs**: we genuinely lost moves and the hardware
     *    is the ground truth. Resync, then re-place ourselves on the
     *    scramble – but only clear the deviation list if the new state
     *    actually lands on a prefix. If it doesn't, the user is still
     *    off-rails and the correction moves we've tracked remain the
     *    best guidance we have; discarding them would strand them.
     */
    private fun handleFacelets(event: SmartCubeEvent.Facelets) {
        if (event.state == logicalState) return

        log.d { "facelets snapshot differs from tracked state; resyncing" }
        logicalState = event.state
        cube.resync(event.state)

        val matched = matchedPrefixIndex(_scrambleProgress.value)
        if (matched != null) {
            _scrambleProgress.value = matched
            _deviationMoves.value = emptyList()
        }
        checkPhaseAfterStateChange()
    }

    private fun handleMove(move: SmartCubeEvent.Move) {
        // "Any move starts the next solve" is resolved *before* the move
        // is applied, not inside the SOLVED branch below. newScramble()
        // resets the cube back to solved, so a move applied first would
        // simply be erased – and the app would then believe the cube is
        // solved while the one in the user's hands is a quarter turn
        // off, which desyncs scramble progress and, worse, stops the
        // timer from ever seeing the next solve finish.
        //
        // Generating the scramble first puts the phase in SCRAMBLING, so
        // the turn falls through into the normal scramble handling and
        // lands on the new scramble as its first move: progress 1 if it
        // happens to match, otherwise a correction move to undo. Either
        // way our model and the physical cube agree.
        if (_phase.value == SolvePhase.SOLVED && anyMoveStartsNewSolve.value) {
            lastSolvedGestureMove = null
            newScramble()
        }

        cube.enqueueMove(move.face, move.cw)
        logicalState = applyMove(logicalState, move.face, move.cw)
        val cubeMove = CubeMove(move.face, if (move.cw) 1 else 3)

        when (_phase.value) {
            SolvePhase.SCRAMBLING -> {
                updateScrambleProgressOnMove(cubeMove)
                checkPhaseAfterStateChange()
            }
            SolvePhase.READY, SolvePhase.INSPECTION -> {
                inspection.cancel()
                _phase.value = SolvePhase.RUNNING
                timer.startTicker(viewModelScope)
                timer.observeMove(move.cubeTimestamp, move.deviceTimestamp)
                _moveCount.value += 1
                checkPhaseAfterStateChange()
            }
            SolvePhase.RUNNING -> {
                timer.observeMove(move.cubeTimestamp, move.deviceTimestamp)
                _moveCount.value += 1
                checkPhaseAfterStateChange()
            }
            SolvePhase.SOLVED -> {
                handleNextSolveGesture(move)
            }
            else -> Unit
        }
    }

    private fun updateScrambleProgressOnMove(justDone: CubeMove) {
        if (scrambleMoves.isEmpty()) return

        val cur = _scrambleProgress.value
        val matched = matchedPrefixIndex(cur)
        if (matched != null) {
            _scrambleProgress.value = matched
            _deviationMoves.value = emptyList()
            return
        }

        val devs = _deviationMoves.value
        val newDevs = if (devs.isNotEmpty() && justDone == devs.last().inverse()) {
            devs.dropLast(1)
        } else {
            devs + justDone
        }
        _deviationMoves.value = newDevs
    }

    /**
     * Index of the scramble prefix whose state equals [logicalState], or
     * null when the cube isn't sitting on any prefix (the user has
     * deviated).
     *
     * [hint] is where we last were; the first three checks cover
     * "advanced one", "unchanged" and "undid one", which is every case
     * a single quarter turn can produce. The reverse scan is the
     * fallback for a jump – a resync, or several moves arriving at once.
     */
    private fun matchedPrefixIndex(hint: Int): Int? {
        if (hint + 1 < scramblePrefixStates.size &&
            scramblePrefixStates[hint + 1] == logicalState
        ) return hint + 1
        if (scramblePrefixStates[hint] == logicalState) return hint
        if (hint >= 1 && scramblePrefixStates[hint - 1] == logicalState) return hint - 1
        for (k in scramblePrefixStates.indices.reversed()) {
            if (scramblePrefixStates[k] == logicalState) return k
        }
        return null
    }

    private fun checkPhaseAfterStateChange() {
        when (_phase.value) {
            SolvePhase.SCRAMBLING -> {
                if (_scrambleProgress.value == scrambleMoves.size) {
                    onScrambleComplete()
                }
            }
            SolvePhase.RUNNING -> {
                if (logicalState.isSolved()) finishSolve()
            }
            else -> Unit
        }
    }

    private fun onScrambleComplete() {
        val uid = activeProfile.idSnapshot()
        val inspectionEnabled = if (uid != null) {
            cache.boolSetting(SettingsRepository.Keys.INSPECTION_ENABLED, default = true)
        } else true
        if (inspectionEnabled) {
            _phase.value = SolvePhase.INSPECTION
            inspection.start(onTimeout = ::onInspectionTimedOut)
        } else {
            _phase.value = SolvePhase.READY
        }
    }

    private fun onInspectionTimedOut() {
        if (_phase.value != SolvePhase.INSPECTION) return
        _phase.value = SolvePhase.RUNNING
        timer.startTicker(viewModelScope)
    }

    // -- "Next solve" gesture (post-SOLVED) -------------------------------

    private var lastSolvedGestureMove: SmartCubeEvent.Move? = null

    /**
     * The U U' gesture: a face turn and its reversal in quick
     * succession start the next solve. Only reached when
     * [anyMoveStartsNewSolve] is off – with it on, [handleMove] has
     * already left the SOLVED phase before this branch is considered.
     */
    private fun handleNextSolveGesture(move: SmartCubeEvent.Move) {
        val previous = lastSolvedGestureMove
        if (previous != null &&
            previous.face == move.face &&
            previous.cw != move.cw &&
            move.deviceTimestamp - previous.deviceTimestamp < NEXT_SOLVE_GESTURE_WINDOW_MS
        ) {
            lastSolvedGestureMove = null
            newScramble()
        } else {
            lastSolvedGestureMove = move
        }
    }

    private fun finishSolve() {
        val durationMs = timer.finish()
        _phase.value = SolvePhase.SOLVED

        // moveCount is captured into a local before the insert so we
        // also pass exactly the same value into LastSolveInfo /
        // anywhere else downstream might need it; it'd be a subtle bug
        // for the persisted row and the in-memory snapshot to disagree
        // because a late event arrived between the two reads.
        val moveCount = _moveCount.value.toLong()
        val tps = if (durationMs > 0) moveCount * 1000.0 / durationMs else null

        val uid = activeProfile.idSnapshot() ?: run {
            log.w { "finishSolve: no active profile, dropping" }
            return
        }

        // Capture the previous best BEFORE inserting – once the new row
        // is in the DB, cache.bestDurationMs will already reflect it and
        // we'd compare the new solve against itself.
        //
        // The fall-through to the repository is not just belt and
        // braces: the cached value is null whenever the user has caching
        // switched off, and reading it alone meant personal bests simply
        // stopped being detected for those users. It is an indexed MIN
        // seek, so it is cheap enough to sit on this path — measured at
        // hundredths of a millisecond against a hundred thousand solves.
        val previousBest = cache.bestDurationMs.value ?: solvesRepo.bestDuration(uid)

        // The Ao5 is no longer computed here. It is derived inside the
        // insert transaction from the rows the database holds, which is
        // the only way it can be right when caching is off, when a
        // penalty is applied later, or when the window straddles solves
        // older than the in-memory one. See `SolvesRepository.insert`.
        val insertedId = solvesRepo.insert(
            userId = uid,
            solvedAt = currentTimeMillis(),
            durationMs = durationMs,
            scramble = _scramble.value,
            fluency = tps,
            moveCount = moveCount,
        )

        // Surface the just-finished solve so the post-solve
        // action row can mark it +2 / DNF / clear-penalty. Initial
        // state has no penalty and is not DNF.
        _lastSolveInfo.value = LastSolveInfo(
            id = insertedId,
            durationMs = durationMs,
            penaltyMs = 0L,
            isDnf = false,
            previousBest = previousBest,
        )

        // PB only fires when the effective time (durationMs +
        // penaltyMs, excluding DNFs) strictly beats the previous best.
        // Effective time at this moment equals durationMs (no penalty
        // applied yet), and the solve isn't DNF. The penalty buttons
        // below recompute this whenever flags change.
        if (previousBest != null && durationMs < previousBest) {
            _newPbEvent.value = durationMs
        }
    }

    /**
     * Toggle DNF on the just-finished solve. Behaviour:
     *   - if currently DNF: clear the flag (and any penalty), restoring
     *     the raw time;
     *   - else: set DNF and clear any +2 (mutual exclusion – only one
     *     of {DNF, +2} can be active at a time).
     *
     * After every toggle, [recomputePbAfterPenalty] re-evaluates whether
     * the solve should be a PB given its current effective time. DNFs
     * cannot be PBs.
     */
    fun markLastSolveDnf() {
        val info = _lastSolveInfo.value ?: return
        if (info.isDnf) {
            // Toggle off: clear DNF and any penalty in one DB write.
            solvesRepo.updatePenalty(info.id, isDnf = false, penaltyMs = 0L)
            _lastSolveInfo.value = info.copy(isDnf = false, penaltyMs = 0L)
        } else {
            // Toggle on: set DNF, clear any +2 (mutual exclusion).
            solvesRepo.updatePenalty(info.id, isDnf = true, penaltyMs = 0L)
            _lastSolveInfo.value = info.copy(isDnf = true, penaltyMs = 0L)
        }
        recomputePbAfterPenalty()
    }

    /**
     * Toggle +2 on the just-finished solve. Behaviour:
     *   - if currently +2: clear the penalty, restoring the raw time;
     *   - else: set +2 and clear any DNF (mutual exclusion).
     */
    fun markLastSolvePlus2() {
        val info = _lastSolveInfo.value ?: return
        if (info.penaltyMs == PLUS2_PENALTY_MS && !info.isDnf) {
            // Toggle off: drop the penalty.
            solvesRepo.updatePenalty(info.id, isDnf = false, penaltyMs = 0L)
            _lastSolveInfo.value = info.copy(isDnf = false, penaltyMs = 0L)
        } else {
            // Toggle on: set +2, clear any DNF (mutual exclusion).
            solvesRepo.updatePenalty(info.id, isDnf = false, penaltyMs = PLUS2_PENALTY_MS)
            _lastSolveInfo.value = info.copy(isDnf = false, penaltyMs = PLUS2_PENALTY_MS)
        }
        recomputePbAfterPenalty()
    }

    /**
     * Re-evaluate whether the just-finished solve should still be a PB
     * after a penalty change. Logic:
     *  - DNF → never a PB.
     *  - Otherwise compare effective time against the captured
     *    `previousBest`.
     *  - If now a PB and we don't already have an event raised, raise it.
     *  - If no longer a PB but we had raised an event, clear it.
     */
    private fun recomputePbAfterPenalty() {
        val info = _lastSolveInfo.value ?: return
        val previousBest = info.previousBest
        val effective = info.durationMs + info.penaltyMs
        val shouldBePb = !info.isDnf && previousBest != null && effective < previousBest
        when {
            shouldBePb && _newPbEvent.value == null -> _newPbEvent.value = effective
            !shouldBePb && _newPbEvent.value != null -> _newPbEvent.value = null
            // If the value is already correct (e.g. PB still valid with
            // updated time) we leave it – re-emitting wouldn't change
            // observers' state.
            shouldBePb && _newPbEvent.value != effective -> _newPbEvent.value = effective
        }
    }

    /**
     * Dismiss the PB celebration dialog. Called from the screen on the
     * "Hooray!" button tap and the dialog's outside-tap. Idempotent:
     * dismissing a non-existent event is a no-op.
     */
    fun dismissPbEvent() {
        _newPbEvent.value = null
    }

    fun currentSession(): SolveSession = SolveSession(
        running = running.value,
        durationMs = elapsedMs.value,
        moveCount = moveCount.value,
        totalSolves = totalSolveCount.value,
        // Persisted all-time best (effective time, DNFs excluded). Read
        // straight from the cache's StateFlow, which is fed by an
        // indexed MIN(...) SQL query on the active profile and updates
        // every time recentSolves re-emits. BestStat consumes this so
        // the "fastest solve" tile reflects the DB record, never the
        // running timer.
        bestDurationMs = cache.bestDurationMs.value,
    )

    override fun onCleared() {
        super.onCleared()
        screenKeeper.setKeepScreenOn(false)
    }

    private fun shouldDelayHalfTurnWarning(devs: List<CubeMove>, prog: Int): Boolean {
        if (devs.size != 1) return false
        if (prog >= scrambleMoves.size) return false
        val nextToken = scrambleMoves[prog]
        return nextToken.times == 2 && nextToken.face == devs[0].face
    }

    private companion object {
        const val NEXT_SOLVE_GESTURE_WINDOW_MS = 1500L
        const val HALF_TURN_GRACE_MS = 700L

        /**
         * Connection states that count as "we just lost the cube" – they
         * trigger an abort to IDLE. CONNECTING is not in here because it's a
         * normal mid-pair transition; SCANNING isn't either – neither
         * implies the cube went away.
         */
        val CONNECTION_LOSS_STATES = setOf(
            ConnectionState.DISCONNECTED,
            ConnectionState.ERROR,
            ConnectionState.PERMISSION_DENIED,
            ConnectionState.BLUETOOTH_DISABLED,
        )

        /** Standard cube-timer +2 penalty (2 seconds, in ms). */
        const val PLUS2_PENALTY_MS = 2000L
    }
}

/**
 * Snapshot of the just-finished solve, surfaced to the Solve screen so
 * the post-solve action row can show / hide and update DNF / +2 / OK
 * buttons. Cleared on `newScramble()` and `abortToIdle()` – the buttons
 * disappear when the user moves on or the connection drops.
 *
 * `previousBest` is captured at insert time so that toggling +2 / DNF
 * after the fact can revoke or restore the PB notification correctly.
 */
data class LastSolveInfo(
    val id: Long,
    val durationMs: Long,
    val penaltyMs: Long,
    val isDnf: Boolean,
    val previousBest: Long?,
) {
    val effectiveMs: Long get() = durationMs + penaltyMs
}

/**
 * Connection summary surfaced to the Solve screen. When [state] is CONNECTED
 * and a [cube] is paired, the screen shows a green dot + cube name.
 */
data class CubeConnectionSummary(
    val state: ConnectionState,
    val cube: PairedCube?,
) {
    val isConnected: Boolean get() = state == ConnectionState.CONNECTED
    val displayName: String? get() = cube?.name
    /** True only if we know the cube has gyro support. False if we know it doesn't. */
    val gyroSupported: Boolean? get() = cube?.gyroSupported
}
