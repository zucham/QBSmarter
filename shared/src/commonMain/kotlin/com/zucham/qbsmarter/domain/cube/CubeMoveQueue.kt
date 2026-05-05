package com.zucham.qbsmarter.domain.cube

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import com.zakgof.korender.math.FloatMath.PIdiv2
import com.zakgof.korender.math.Vec3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cubic ease-in/ease-out used for face-turn animations and orbit snaps.
 * Defined here so the move queue and the orbiter can share the same curve.
 */
val CubicEaseInOut = Easing { t ->
    if (t < 0.5f) 4f * t * t * t
    else 1f - (-2f * t + 2f).let { it * it * it } / 2f
}

/** A queued move – one quarter turn. */
data class Move(val face: CubeFace, val clockwise: Boolean)

/**
 * Items the queue consumer pulls from the channel.
 *
 * [QueueItem.Turn] is a normal animated face turn; [QueueItem.Reset]
 * is an authoritative state replacement used by hardware Facelets
 * resync (see `RubiksCube.resync`) and by `RubiksCube.resetState`.
 *
 * Unifying both into one channel guarantees serial processing: every
 * Turn *before* a Reset has either already been processed (and
 * committed) or has been drained out by `enqueueReset`. So the
 * consumer sees Reset only after all stale Turns are gone – no race
 * where a Turn commits on top of the authoritative target.
 */
private sealed class QueueItem {
    data class Turn(val move: Move) : QueueItem()
    data class Reset(val target: CubeState) : QueueItem()
}

/**
 * Active rotation animation. Holds [animatedPieces], the [animatable]
 * driving the 0..1 progress, [perPieceAxisAngle] mapping each animated
 * piece to its (axis, target-angle), and crucially [preMoveState] +
 * [preMoveCenterOrientations] – the cube's logical state and per-face
 * center rotations at the moment the animation started.
 *
 * For coalesced opposite-face pairs, [animatedPieces] contains the pieces
 * for *both* faces, and [perPieceAxisAngle] gives each piece its own
 * (axis, targetAngle) so the two layers can rotate in opposite directions
 * simultaneously. The single shared [animatable] drives both, so they
 * remain perfectly in lockstep regardless of frame timing.
 *
 * **Why [preMoveState] is here**: the renderer's `pieceTransform` reads
 * both `_state` and `activeAnimation` per frame. Korender's render thread
 * is NOT a Compose snapshot reader – it reads `MutableState.value`
 * outside any snapshot, so two adjacent reads can fall on opposite
 * sides of an atomic write done via `Snapshot.withMutableSnapshot`. If
 * the renderer reads `activeAnimation` first (gets the old non-null
 * animation) then reads `_state` (gets the new committed state), it
 * would render `rotate(90°) * rest_post-move` = a visible 180° turn.
 * Storing the pre-move state here lets `pieceTransform` use IT for the
 * rest position whenever an animation is active, decoupling the rest
 * computation from `_state` entirely. The transition from "anim active
 * (read preMoveState)" to "anim null (read _state)" remains the only
 * point of switchover, and at that point both `_state` and the new
 * `null` are post-move – consistent regardless of order observed.
 *
 * **Why [preMoveCenterOrientations] mirrors that**: same race applies to
 * center rotations. Centers rotate visually with face turns and the
 * commit step bumps the orientation array – without a snapshot, the
 * renderer could see the new orientation AND the still-active
 * animation overlay together (visible double rotation of the center).
 */
data class ActiveAnimation(
    val animatedPieces: Set<CubePiece>,
    val animatable: Animatable<Float, *>,
    val perPieceAxisAngle: Map<CubePiece, AxisAngle>,
    val preMoveState: CubeState,
    val preMoveCenterOrientations: IntArray,
) {
    /** Convenience for the renderer when a piece is animated. */
    fun forPiece(piece: CubePiece): AxisAngle? = perPieceAxisAngle[piece]

    // IntArray needs a custom equals/hashCode (it would otherwise compare
    // by reference). Generated equals would also fail for the same reason
    // – override both so equality reflects content.
    override fun equals(other: Any?): Boolean =
        other is ActiveAnimation &&
            animatedPieces == other.animatedPieces &&
            animatable === other.animatable &&
            perPieceAxisAngle == other.perPieceAxisAngle &&
            preMoveState == other.preMoveState &&
            preMoveCenterOrientations.contentEquals(other.preMoveCenterOrientations)

    override fun hashCode(): Int {
        var r = animatedPieces.hashCode()
        r = 31 * r + animatable.hashCode()
        r = 31 * r + perPieceAxisAngle.hashCode()
        r = 31 * r + preMoveState.hashCode()
        r = 31 * r + preMoveCenterOrientations.contentHashCode()
        return r
    }
}

/** Axis-angle pair for a single rotating piece. */
data class AxisAngle(val axis: Vec3, val angle: Float)

/**
 * Animation duration policy. Empty queue → smooth full-speed. One waiting →
 * quick. Two or more waiting → snap immediately, no animation.
 */
private const val DURATION_FULL_MS = 180
private const val DURATION_QUICK_MS = 80
private const val SNAP_THRESHOLD = 2

/**
 * How long to wait for an opposite-face partner before firing a solo move.
 * GAN cubes report each face independently with a few-ms gap between them
 * even for "simultaneous" turns.
 */
private const val SLICE_PAIR_WINDOW_MS = 60L

/**
 * Producer-consumer queue for face turns. Owns a single coroutine that
 * drains the channel; the producer side ([enqueue]) is non-suspending and
 * thread-safe.
 *
 * The queue calls [commit] to advance the logical state, and uses
 * [cubeState] to determine which cubies are visually on each face when
 * setting up the animation overlay. "Which pieces are on the D face" is
 * a function of the state's `cp/ep` arrays, not of any separately-stored
 * piece transforms – so move sequences cannot desync.
 */
class CubeMoveQueue(
    private val pieces: List<CubePiece>,
    private val cubeState: () -> CubeState,
    /**
     * Snapshot of the per-face center rotations at call time. Used by
     * [executeSolo] / [executePair] to capture pre-move centers for the
     * animation overlay. See [ActiveAnimation.preMoveCenterOrientations].
     */
    private val centerOrientations: () -> IntArray,
    private val onActiveAnimation: (ActiveAnimation?) -> Unit,
    private val commit: (move: Move) -> Unit,
    private val applyResetTarget: (CubeState) -> Unit,
) {
    private val channel = Channel<QueueItem>(Channel.UNLIMITED)
    private var consumerJob: Job? = null

    fun start(scope: CoroutineScope) {
        if (consumerJob?.isActive == true) return
        consumerJob = scope.launch { consume() }
    }

    fun stop() {
        consumerJob?.cancel()
        consumerJob = null
        // Drain anything left so a future start() doesn't replay stale items.
        while (channel.tryReceive().isSuccess) Unit
    }

    fun enqueue(move: Move) {
        channel.trySend(QueueItem.Turn(move))
    }

    /**
     * Replace the cube's logical state with [target]. Any pending
     * [Move]s in the channel are dropped before the reset signal is
     * enqueued. If a move is mid-animation when this is called, the
     * animation runs to completion (and that move's commit lands), then
     * the consumer pulls the Reset signal and overwrites `_state` with
     * [target] – so the in-flight move's commit is harmless: its
     * effect is overwritten before the next frame finishes the visual
     * transition.
     *
     * We do NOT cancel the in-flight animation. Cancellation here would
     * either drop the move (causing VM logical and cube logical to
     * diverge if the cancellation isn't a real reset) or commit it
     * (which we'd then have to suppress). Letting the animation finish
     * and overwriting afterward is simpler and produces only a brief
     * one-move visual blip – acceptable for a recovery path.
     */
    fun enqueueReset(target: CubeState) {
        // Drop everything pending so the Reset is the next thing the
        // consumer sees after its current move (if any) finishes.
        while (channel.tryReceive().isSuccess) Unit
        channel.trySend(QueueItem.Reset(target))
    }

    private suspend fun consume() {
        // Locally-held items between iterations. Why not put them back
        // in the channel? The kotlinx Channel is FIFO with no
        // front-insertion API, so re-enqueueing means trySend() to the
        // tail. If a producer (cube.enqueueMove) inserts a new move
        // into the channel between our drain and our re-send, the
        // re-sent items end up BEHIND that new move – i.e. we've
        // reordered the move stream. logicalState (in SolveVM) is
        // updated synchronously in event arrival order; reordering
        // here desyncs the visual from the logical and from the
        // physical cube.
        //
        // The local deque sidesteps that entirely: the consumer
        // ALWAYS pulls from the local deque first, falling through to
        // the channel only when the deque is empty. Producer-side
        // inserts always go to the channel tail, so the order seen by
        // the consumer is: [stuff we already drained but didn't
        // animate yet] → [stuff that arrived during/after the drain].
        // Which is the correct event order.
        val pending = ArrayDeque<Move>()

        while (true) {
            val firstItem: QueueItem = if (pending.isNotEmpty()) {
                QueueItem.Turn(pending.removeFirst())
            } else {
                channel.receive()
            }

            // Reset short-circuits: drop any pre-Reset Move backlog and
            // apply the new state directly. Items that arrived in the
            // channel AFTER the Reset (between enqueueReset draining the
            // channel and us pulling the Reset off) are post-target and
            // must be preserved – the user has performed those moves on
            // the actual cube and they need to apply on top of the
            // resync target. Draining them here would silently swallow
            // real moves whenever a Facelets event raced with a Move
            // event.
            if (firstItem is QueueItem.Reset) {
                // Pre-Reset items in `pending` are stale: the Reset
                // target is the authoritative truth and includes (or
                // pre-dates) those moves either way. Same policy as
                // pre-Reset items in the channel.
                pending.clear()
                applyResetTarget(firstItem.target)
                onActiveAnimation(null)
                continue
            }

            val first = (firstItem as QueueItem.Turn).move

            // Drain any additional pending items from the channel into
            // the local deque so we can decide on an animation policy.
            // If a Reset is encountered, stash it for application after
            // the in-flight `first` move's animation completes; any
            // post-Reset Moves go into postReset and become the new
            // pending after the Reset is applied.
            var pendingReset: QueueItem.Reset? = null
            val postReset = ArrayDeque<Move>()
            while (true) {
                val next = channel.tryReceive().getOrNull() ?: break
                when (next) {
                    is QueueItem.Turn -> {
                        if (pendingReset != null) postReset.addLast(next.move)
                        else pending.addLast(next.move)
                    }
                    is QueueItem.Reset -> {
                        pendingReset = next
                        // Drop any pre-Reset Moves we drained – they're
                        // logically stale. Post-Reset items continue
                        // accumulating into [postReset].
                        pending.clear()
                    }
                }
            }

            val totalPending = 1 + pending.size
            val durationMs = when {
                pendingReset != null -> 0  // snap, the Reset will overwrite anyway
                totalPending > SNAP_THRESHOLD -> 0
                totalPending == 2 -> DURATION_QUICK_MS
                else -> DURATION_FULL_MS
            }

            val partner: Move? = when {
                durationMs <= 0 -> null
                pending.isNotEmpty() && pending.first().face == first.face.opposite() ->
                    pending.removeFirst()
                pending.isEmpty() -> waitForPartner(first.face.opposite(), pending)
                else -> null
            }

            // Note: no re-enqueue. `pending` survives the iteration and
            // is consumed first on the next loop pass.

            if (partner != null) {
                executePair(first, partner, durationMs)
            } else {
                executeSolo(first, durationMs)
            }

            // Apply the pending Reset directly after the in-flight
            // move has committed. We can't re-enqueue Reset because
            // the channel may already contain new Moves that arrived
            // *after* enqueueReset (and thus should land *after* the
            // target state, not before). Trying to put Reset back at
            // the head of the channel would either require front-end
            // queuing (which Channel doesn't support) or shuffling
            // every subsequent item, both of which are race-prone.
            // Applying directly here preserves ordering: in-flight
            // commit → target overwrite → any post-Reset moves.
            if (pendingReset != null) {
                applyResetTarget(pendingReset.target)
                onActiveAnimation(null)
                // postReset moves apply on top of the new target. They
                // become the new `pending` so the next iteration picks
                // them up before any newly-arrived channel items –
                // which is correct because postReset items are
                // logically before any items the producer has sent
                // since.
                pending.clear()
                pending.addAll(postReset)
            }
        }
    }

    /**
     * Briefly poll the channel for a partner move on [target] face.
     *
     * If a non-partner Move arrives, it's pushed onto the caller's
     * [pending] deque (rather than re-sent to the channel) so that:
     *   1. It's processed in the next consume iteration without a
     *      channel round-trip.
     *   2. We don't trigger the same channel re-enqueue race fixed in
     *      [consume] – a producer-side enqueue between our tryReceive
     *      and our re-trySend would land the new move ahead of the
     *      reclaimed one in the channel.
     *
     * Reset items still go back to the channel (the local deque only
     * holds Moves). This is technically still racy under simultaneous
     * external enqueueReset calls – but enqueueReset is rare (Facelets
     * resync) and the race window is microseconds; not fixing here.
     */
    private suspend fun waitForPartner(target: CubeFace, pending: ArrayDeque<Move>): Move? {
        var elapsed = 0L
        while (elapsed < SLICE_PAIR_WINDOW_MS) {
            val candidate = channel.tryReceive().getOrNull()
            if (candidate != null) {
                when (candidate) {
                    is QueueItem.Turn -> {
                        return if (candidate.move.face == target) candidate.move
                        else { pending.addLast(candidate.move); null }
                    }
                    is QueueItem.Reset -> {
                        // Re-enqueue to channel. The next consume
                        // iteration will pick it up after we've finished
                        // the in-flight move and any pending items.
                        channel.trySend(candidate)
                        return null
                    }
                }
            }
            delay(POLL_INTERVAL_MS)
            elapsed += POLL_INTERVAL_MS
        }
        return null
    }

    private suspend fun executeSolo(move: Move, durationMs: Int) {
        val moving = piecesOnFace(move.face)
        val axis = move.face.axis()
        val targetAngle = if (move.clockwise) -PIdiv2 else PIdiv2

        if (durationMs <= 0) {
            commit(move)
            return
        }

        val animatable = Animatable(0f)
        val perPiece = moving.associateWith { AxisAngle(axis, targetAngle) }
        // preMoveState + preMoveCenterOrientations capture the cube's
        // visual state at the moment this animation starts. The renderer
        // uses BOTH for rest-position computation whenever this
        // animation is active, so the renderer cannot see post-commit
        // state AND the still-active animation overlay at the same
        // time (which would render as a visible double turn – for
        // either the slot permutation or the center rotation).
        // See the ActiveAnimation kdoc for the full race analysis.
        val active = ActiveAnimation(
            animatedPieces = moving,
            animatable = animatable,
            perPieceAxisAngle = perPiece,
            preMoveState = cubeState(),
            preMoveCenterOrientations = centerOrientations().copyOf(),
        )
        onActiveAnimation(active)

        try {
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = durationMs, easing = CubicEaseInOut),
            )
        } catch (e: CancellationException) {
            // Mid-animation cancellation: bake the move into state anyway
            // so logical and visual stay aligned (visual derives from state).
            // The order doesn't matter visually because the renderer
            // reads preMoveState while anim is non-null and _state when
            // anim is null – the transition is a single observable
            // step (anim becomes null) at which point _state already
            // holds the post-move value.
            commit(move)
            onActiveAnimation(null)
            throw e
        }

        commit(move)
        onActiveAnimation(null)
    }

    /**
     * Execute two opposite-face turns as a single visual animation. The
     * renderer reads from `_state`, which only updates after both commits
     * land – so even if the animation is interrupted, the cubies' rest
     * positions stay consistent.
     */
    private suspend fun executePair(first: Move, second: Move, durationMs: Int) {
        val movingFirst = piecesOnFace(first.face)
        val movingSecond = piecesOnFace(second.face)
        val axisFirst = first.face.axis()
        val axisSecond = second.face.axis()
        val angleFirst = if (first.clockwise) -PIdiv2 else PIdiv2
        val angleSecond = if (second.clockwise) -PIdiv2 else PIdiv2

        if (durationMs <= 0) {
            commit(first)
            commit(second)
            return
        }

        val allMoving = movingFirst + movingSecond
        val perPiece = HashMap<CubePiece, AxisAngle>(allMoving.size)
        for (p in movingFirst) perPiece[p] = AxisAngle(axisFirst, angleFirst)
        for (p in movingSecond) perPiece[p] = AxisAngle(axisSecond, angleSecond)

        val animatable = Animatable(0f)
        // Both moves share one animation; preMoveState (and
        // preMoveCenterOrientations) is the cube's visual state before
        // EITHER move was committed. See [ActiveAnimation] kdoc.
        val active = ActiveAnimation(
            animatedPieces = allMoving,
            animatable = animatable,
            perPieceAxisAngle = perPiece,
            preMoveState = cubeState(),
            preMoveCenterOrientations = centerOrientations().copyOf(),
        )
        onActiveAnimation(active)

        try {
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = durationMs, easing = CubicEaseInOut),
            )
        } catch (e: CancellationException) {
            commit(first)
            commit(second)
            onActiveAnimation(null)
            throw e
        }

        commit(first)
        commit(second)
        onActiveAnimation(null)
    }

    /**
     * Determine which CubePiece instances are visually on [face] right now.
     *
     * Derives the set from the current [CubeState] (via [cubiesOnFaceMeshes])
     * rather than from any stored piece-position field. Because the cube
     * state is the single source of truth, this cannot lie: if the state
     * says cubie X is at slot Y, then mesh-for-X is on whatever face slot
     * Y is on.
     */
    private fun piecesOnFace(face: CubeFace): HashSet<CubePiece> {
        val meshes = cubiesOnFaceMeshes(cubeState(), face)
        return meshes.mapTo(HashSet(meshes.size)) { pieces[it] }
    }

    private companion object {
        /** Granularity of the partner-wait poll. 5ms = 12 polls per window. */
        const val POLL_INTERVAL_MS = 5L
    }
}
