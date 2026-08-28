package com.zucham.qbsmarter.domain.cube

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import co.touchlab.kermit.Logger
import com.zakgof.korender.math.Transform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Visual + logical 3×3 cube model.
 *
 * **Single source of truth: [CubeState].** Everything visual derives from
 * the logical state through [transformForMesh]. There is no separately-
 * stored per-piece transform field; the renderer reads `_state` and
 * computes each cubie's transform on demand. Moves cannot desync because
 * there is nothing to desync – only one variable holds the truth.
 *
 * **Center orientations** are an exception: they're tracked in
 * [_centerOrientations] (one quarter-turn count per face) rather than
 * inside [CubeState]. Reason: the GAN cube doesn't report center
 * rotation, and folding it into [CubeState] would break the
 * scramble-progress equality check (which compares whole CubeStates
 * against precomputed prefix states whose centers are always zero).
 * Keeping centers in their own array is purely visual – physical reality
 * for the cube doesn't include center orientation either, just rotation
 * symmetry of each individual face.
 *
 * **Mid-animation rendering.** `pieceTransform` composes a partial
 * rotation on top of the rest position. While an animation is in flight
 * the rest position comes from [ActiveAnimation.preMoveState] (a snapshot
 * captured at the moment the animation started); when the animation
 * finishes, the next read falls through to the live `_state` (which by
 * then holds the post-move value), giving a seamless visual transition.
 * See the [ActiveAnimation] kdoc for the renderer-thread race analysis
 * that motivates the snapshot.
 */
class RubiksCube {

    private val log = Logger.withTag("RubiksCube")

    val pieces: List<CubePiece> = CUBE_PARTS.mapIndexed { _, _ ->
        CubePiece()
    }

    /**
     * Logical Kociemba state. The single source of truth for both the
     * logical solver and the renderer (corner/edge slots and twists).
     * Updates only inside [commitMove], [resetState], and [resync].
     */
    private val _state: MutableState<CubeState> = mutableStateOf(CubeState.SOLVED)
    val state: CubeState get() = _state.value

    /**
     * Per-face center rotation in 0..3 quarter-turn units, indexed by
     * [CubeFace.ordinal]. A face turn of [face] increments
     * `_centerOrientations[face.ordinal]` by 1 (CW) or 3 (CCW), modulo
     * [CENTER_ORIENTATIONS_COUNT]. CCW = 3 increments because mod arithmetic
     * with non-negative numbers is simpler than dealing with negative mods.
     *
     * Snapshot semantics for animations: the animation overlay rotates the
     * center piece relative to its pre-move rest. The pre-move rest is
     * captured in [ActiveAnimation.preMoveCenterOrientations] so that the
     * renderer can compose [animation overlay] × [pre-move rest] and the
     * commit step bumps `_centerOrientations` to the post-move value.
     */
    private val _centerOrientations: MutableState<IntArray> =
        mutableStateOf(IntArray(CubeFace.entries.size))
    val centerOrientations: IntArray get() = _centerOrientations.value

    /**
     * The physical cube's own orientation, as reported by its gyroscope.
     * Declared before [orbiter] because the orbiter's auto-snap gate
     * queries it.
     */
    val gyroscope: CubeGyroscope = CubeGyroscope()

    /**
     * The user's manual orbit, owned by this cube so the VM and the renderer
     * agree on a single source of truth.
     *
     * Auto-snap is suppressed while the gyro is driving the view – see
     * [CubeOrbiter]'s kdoc for why.
     */
    val orbiter: CubeOrbiter = CubeOrbiter(autoSnapAllowed = { !gyroscope.enabled })

    /** Active animation, exposed so the renderer can compose it on top. */
    val activeAnimation: MutableState<ActiveAnimation?> = mutableStateOf(null)

    private val baseTransform = Transform.scale(7f)

    private val moveQueue = CubeMoveQueue(
        pieces = pieces,
        cubeState = { _state.value },
        centerOrientations = { _centerOrientations.value },
        onActiveAnimation = { activeAnimation.value = it },
        commit = ::commitMove,
        applyResetTarget = ::applyResetTarget,
    )

    private var ownedScope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        ownedScope = scope
        orbiter.bindScope(scope)
        moveQueue.start(scope)
    }
    fun stop() {
        moveQueue.stop()
        orbiter.unbindScope()
        ownedScope = null
    }

    /** Producer side. Safe to call at any rate from any thread. */
    fun enqueueMove(face: CubeFace, clockwise: Boolean) {
        moveQueue.enqueue(Move(face, clockwise))
    }

    /**
     * Compose the live transform for the mesh at [meshIndex].
     *
     * Critical detail: the rest position uses [ActiveAnimation.preMoveState]
     * (and [ActiveAnimation.preMoveCenterOrientations]) when an animation
     * is in flight, NOT [_state] / [_centerOrientations]. Korender's render
     * thread reads `MutableState.value` outside any Compose snapshot, so
     * adjacent reads of `activeAnimation` and `_state` can fall on
     * opposite sides of an atomic write – the renderer would then
     * compute `rotate(90°) * rest_post-move` (visible 180° turn). By
     * deriving rest from `anim.preMoveState` whenever anim is non-null,
     * the only switch-over point is `anim` going from non-null to null,
     * at which moment `_state` is already at the post-move value.
     * Whichever order the renderer observes the two writes, the result
     * is consistent.
     */
    fun pieceTransform(meshIndex: Int): Transform {
        val piece = pieces[meshIndex]
        val anim = activeAnimation.value
        val axisAngle = anim?.forPiece(piece)

        // Rest position: while an animation is active we use the snapshot
        // of state taken when the animation started – see kdoc above.
        // No animation? Read the live state directly.
        val rest = if (anim != null) {
            transformForMesh(anim.preMoveState, meshIndex, anim.preMoveCenterOrientations)
        } else {
            transformForMesh(_state.value, meshIndex, _centerOrientations.value)
        }

        val local = if (axisAngle != null) {
            // Mid-animation: rotate the rest position by the partial
            // angle. The animation finishes exactly when commit updates
            // _state to the post-move value, so rest jumps to its new
            // value at the same instant the overlay rotation is removed
            // – no visible jump.
            val live = axisAngle.angle * anim!!.animatable.value
            Transform.rotate(axisAngle.axis, live) * rest * baseTransform
        } else {
            rest * baseTransform
        }

        // Gyro and orbit are layered, not alternatives. The gyro places
        // the cube the way the user is physically holding it; the drag
        // is a viewing offset the user applies on top of that. Ordering
        // matters: `a * b` applies b first, so the drag wraps the gyro
        // pose the same way it wraps a stationary cube, and dragging
        // feels identical whether or not the gyro is running.
        //
        // The isIdle fast path skips a 4x4 multiply per cubie (26 per
        // frame) whenever the gyro contributes nothing, which is the
        // case for every user who never turns the feature on.
        val outer = if (gyroscope.isIdle) orbiter.rotation else orbiter.rotation * gyroscope.orientation
        return outer * local
    }

    /**
     * Per-frame tick, driven by the renderer (see
     * [com.zucham.qbsmarter.ui.screens.solve.CubeView]).
     *
     * Only the gyro smoothing needs it today. It lives here rather than
     * having the view reach into [gyroscope] directly so the render loop
     * has a single, stable entry point into the cube model as more
     * frame-driven behaviour appears.
     *
     * @param dtSeconds time since the previous rendered frame, in seconds.
     */
    fun advanceFrame(dtSeconds: Float) {
        gyroscope.advance(dtSeconds)
    }

    /** Kociemba facelet string for the current logical state. */
    fun facelets(): String = state.toKociembaFacelets()

    /**
     * "Reset orientation" button: bring the cube all the way back to the
     * default pose (white-up, green-front).
     *
     * That means both layers of [pieceTransform]'s `outer` rotation, not
     * just the drag: the orbiter slerps back to identity, and the
     * gyroscope re-homes so the pose the cube is physically in right now
     * becomes the new zero. Resetting only the drag would leave the cube
     * visibly off-centre whenever the gyro is running, which reads as the
     * button not working.
     *
     * Both halves animate over comparable windows (the orbiter's tween
     * and the gyro's smoothing), so the cube eases home as one motion.
     *
     * The gyro half runs unconditionally – it needs no coroutine scope,
     * and it is harmless when the gyro is off.
     */
    fun animateOrientationToIdentity(): Boolean {
        gyroscope.recenter()
        val scope = ownedScope ?: return false
        scope.launch { orbiter.animateToIdentity() }
        return true
    }

    /**
     * Reset both visual and logical state to solved.
     *
     * Routes through the queue so any in-flight or pending moves are
     * dropped before the SOLVED state lands. If we set `_state` directly
     * here, a queued move would commit on top of SOLVED, leaving the
     * cube one move past solved – visible as 8 misplaced pieces.
     */
    fun resetState() {
        moveQueue.enqueueReset(CubeState.SOLVED)
    }

    /**
     * Reconcile the logical state with a hardware-reported snapshot.
     *
     * Routed through the queue (see [resetState]) so pending moves
     * don't commit on top of [target]. This is the recovery path for
     * BLE drops that exceeded the cube's 7-move replay buffer: the
     * Facelets snapshot is the ground truth, and any queued moves
     * (which may include backfilled-but-stale moves from before the
     * snapshot was requested) must be discarded.
     *
     * Center orientations are reset to all-zero on resync. The cube
     * doesn't report them, so we don't have a better answer; the
     * one-quarter-turn-off center is a smaller visual glitch than a
     * misaligned permutation.
     */
    fun resync(target: CubeState) {
        if (_state.value == target && centerOrientationsAllZero()) return
        log.w { "resync: hardware state differs from local; routing through queue" }
        moveQueue.enqueueReset(target)
    }

    /**
     * Visual-only catch-up to a known-correct logical state.
     *
     * Differs from [resync] in two ways:
     *   1. **Centers are preserved.** If `_state` already matches
     *      `target` (the common case – visual was already in sync), the
     *      currently-tracked center orientations are correct and
     *      shouldn't be wiped to zero.
     *   2. **No-op on match.** Callers can invoke this freely on screen
     *      entry; if visual = logical there's nothing to do.
     *
     * If `_state` doesn't match `target`, we route through the queue's
     * reset (which DOES zero centers) – that's the safe choice, since
     * if the visual was lagging we have no way to know what the
     * "correct" center orientation is for [target].
     *
     * Use case: SolveScreen entry. The move queue is stopped while the
     * user is on a different screen, so any moves received over BLE
     * during that window pile up in the channel. logicalState (in the
     * VM) stays current synchronously. When the user returns, this
     * method snaps the visual to the same state without replaying the
     * stale backlog as visible animations.
     */
    fun catchUpVisualTo(target: CubeState) {
        if (_state.value == target) return  // already in sync, leave centers alone
        log.d { "catchUpVisualTo: visual lagged logical; routing through queue" }
        moveQueue.enqueueReset(target)
    }

    private fun centerOrientationsAllZero(): Boolean =
        _centerOrientations.value.all { it == 0 }

    /**
     * Bake a finished face turn into the logical state. Single point of
     * truth for state mutation outside of [resync] / [resetState].
     *
     * The visual is automatically correct on the next frame because
     * [pieceTransform] derives from `_state`.
     */
    private fun commitMove(move: Move) {
        _state.value = applyMove(_state.value, move.face, move.clockwise)
        // Bump the center rotation for the face being turned. CW = +1,
        // CCW = +3 (== -1 mod 4). Read-modify-write a copy because
        // MutableState equality skips redundant emissions; if we
        // mutated the array in place, observers wouldn't see the
        // change.
        val ord = move.face.ordinal
        val delta = if (move.clockwise) 1 else 3
        val updated = _centerOrientations.value.copyOf()
        updated[ord] = (updated[ord] + delta) % CENTER_ORIENTATIONS_COUNT
        _centerOrientations.value = updated
    }

    /**
     * Apply a state replacement from the move queue. Resets center
     * orientations because the resync target carries no center data.
     */
    private fun applyResetTarget(target: CubeState) {
        _state.value = target
        _centerOrientations.value = IntArray(CubeFace.entries.size)
    }
}
