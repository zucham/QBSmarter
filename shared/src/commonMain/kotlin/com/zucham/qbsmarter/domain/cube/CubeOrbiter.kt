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
 */
class CubeOrbiter {

    private val _rotation: MutableState<Transform> = mutableStateOf(Transform.IDENTITY)
    val rotation: Transform get() = _rotation.value

    private var startEvent: TouchEvent? = null
    private var startRotation: Transform = Transform.IDENTITY
    private var animating = false

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
     * window. Canceled if the orbiter's scope is unbound.
     */
    private fun scheduleAutoSnap() {
        pendingSnapJob?.cancel()
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
        try {
            val anim = Animatable(0f)
            anim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = SNAP_DURATION_MS, easing = CubicEaseInOut),
            ) {
                _rotation.value = slerp(startQ, targetQ, value).toTransform()
            }
            _rotation.value = targetQ.toTransform()
        } finally {
            animating = false
        }
    }

    companion object {
        /** Delay between drag-end and auto-snap. */
        const val SNAP_DELAY_MS = 500L
        const val SNAP_DURATION_MS = 280
    }
}
