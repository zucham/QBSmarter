package com.zucham.qbsmarter.domain.cube

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.zakgof.korender.TouchEvent
import com.zakgof.korender.math.Quaternion
import com.zakgof.korender.math.Transform
import com.zakgof.korender.math.Vec3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * User-driven orbit of the rendered cube. Touch events come in from
 * Korender's `OnTouch` callback. DOWN captures the start state; subsequent
 * MOVE events accumulate yaw + pitch onto the start rotation; UP closes
 * the gesture and schedules an auto-snap.
 *
 * Two animation entry points expose the orbiter to the rest of the app:
 *   • [animateToIdentity] – the "Reset orientation" button. Slerp-animates
 *     back to default orientation (white up, green front).
 *   • [snapToNearest] – fires automatically [SNAP_DELAY_MS] after the user
 *     finishes dragging. Picks one of the 24 cube-symmetric orientations,
 *     so the cube always rests on a clean axis-aligned pose.
 *
 * Observable rotation: [rotation] is a Compose [MutableState] so the
 * renderer recomposes on every frame the slerp updates it. This is the
 * single source of truth for the cube's user-driven orientation –
 * [com.zucham.qbsmarter.domain.cube.RubiksCube] simply forwards it into
 * `pieceTransform`'s `outer` rotation, and the cube view never writes back.
 *
 * Lifecycle: [bindScope] is called when the cube starts (typically from
 * `RubiksCube.start(scope)`); [unbindScope] cancels any pending snap job.
 *
 * @param autoSnapAllowed gate on the drag-end auto-snap, queried at the
 *   moment the gesture ends. Injected as a predicate rather than a
 *   mutable flag so there is no second copy of the condition to keep in
 *   sync – the orbiter asks, it doesn't get told. [RubiksCube] wires this
 *   to "the gyroscope is off": with gyro tracking live, the rendered pose
 *   is the drag offset *composed with* the cube's real orientation, so it
 *   isn't axis-aligned regardless of what the drag component snaps to.
 *   Snapping then just yanks the cube for no visible payoff.
 */
class CubeOrbiter(
    private val autoSnapAllowed: () -> Boolean = { true },
) {

    private val _rotation: MutableState<Transform> = mutableStateOf(Transform.IDENTITY)
    val rotation: Transform get() = _rotation.value

    private var startEvent: TouchEvent? = null
    private var startRotation: Transform = Transform.IDENTITY
    private var animating = false

    /**
     * Bumped by [resetImmediately]. An animation in flight captures the
     * value it started under and stops writing [_rotation] the moment it
     * no longer matches - otherwise a slerp that was already running
     * would keep painting over the rotation the reset just forced home,
     * for the remainder of its tween.
     */
    private var resetGeneration = 0

    private var scope: CoroutineScope? = null
    private var pendingSnapJob: Job? = null

    private val sensitivity = 0.005f
    private val worldY = Vec3(0f, 1f, 0f)
    private val worldX = Vec3(1f, 0f, 0f)

    fun bindScope(scope: CoroutineScope) {
        this.scope = scope
    }

    fun unbindScope() {
        pendingSnapJob?.cancel()
        pendingSnapJob = null
        this.scope = null
    }

    fun touch(touchEvent: TouchEvent) {
        if (animating) return
        when (touchEvent.type) {
            TouchEvent.Type.DOWN -> {
                pendingSnapJob?.cancel()  // user is touching again, drop the snap
                startEvent = touchEvent
                startRotation = rotation
            }
            TouchEvent.Type.MOVE -> {
                val s = startEvent ?: return
                val dx = (touchEvent.x - s.x) * sensitivity
                val dy = (touchEvent.y - s.y) * sensitivity
                val yawRot = Transform.rotate(worldY, dx)
                val pitchRot = Transform.rotate(worldX, dy)
                _rotation.value = pitchRot * yawRot * startRotation
            }
            TouchEvent.Type.UP -> {
                startEvent = null
                scheduleAutoSnap()
            }
        }
    }

    /**
     * Wait [SNAP_DELAY_MS], then snap to the nearest of the 24 cube
     * orientations. Canceled if the user starts a new touch in that
     * window. Canceled if the orbiter's scope is unbound. Skipped
     * entirely when [autoSnapAllowed] says no – see the class kdoc.
     */
    private fun scheduleAutoSnap() {
        pendingSnapJob?.cancel()
        if (!autoSnapAllowed()) return
        val s = scope ?: return
        pendingSnapJob = s.launch {
            delay(SNAP_DELAY_MS)
            snapToNearest()
        }
    }

    /**
     * Smoothly snap to the closest of the 24 axis-aligned cube orientations.
     * Public so external callers (e.g. tests) can trigger it manually too.
     */
    suspend fun snapToNearest() {
        if (animating) return
        val current = quaternionFromTransform(rotation)
        val target = nearestCubeOrientation(current)
        animateTo(current, target)
    }

    /**
     * Drop the drag offset immediately, with no animation.
     *
     * The animated paths ([animateToIdentity], [snapToNearest]) all need
     * a bound scope, which only exists while the Solve screen is on
     * display. This one doesn't, so it is the reset that still works
     * when the cube view isn't composed - the disconnect path uses it as
     * its fallback so a cube that dropped while the user was on another
     * screen isn't found tilted when they come back.
     *
     * Cancels any pending auto-snap and any in-flight animation's effect
     * by clearing [animating]: whatever was easing toward a target is
     * moot once the rotation is forced home.
     */
    fun resetImmediately() {
        pendingSnapJob?.cancel()
        pendingSnapJob = null
        startEvent = null
        startRotation = Transform.IDENTITY
        animating = false
        resetGeneration++
        _rotation.value = Transform.IDENTITY
    }

    /**
     * Animate the orbiter back to the default orientation (white-up,
     * green-front). The "Reset orientation" button calls this.
     */
    suspend fun animateToIdentity() {
        if (animating) return
        val current = quaternionFromTransform(rotation)
        animateTo(current, Quaternion.IDENTITY)
    }

    private suspend fun animateTo(startQ: Quaternion, targetQ: Quaternion) {
        animating = true
        // Captured, not read live: if a reset lands mid-tween this
        // animation must go quiet rather than fight it. See
        // [resetGeneration].
        val generation = resetGeneration
        try {
            val anim = Animatable(0f)
            anim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = SNAP_DURATION_MS, easing = CubicEaseInOut),
            ) {
                if (generation != resetGeneration) return@animateTo
                _rotation.value = slerp(startQ, targetQ, value).toTransform()
            }
            if (generation == resetGeneration) _rotation.value = targetQ.toTransform()
        } finally {
            if (generation == resetGeneration) animating = false
        }
    }

    companion object {
        /** Delay between drag-end and auto-snap. */
        const val SNAP_DELAY_MS = 500L
        const val SNAP_DURATION_MS = 280
    }
}
