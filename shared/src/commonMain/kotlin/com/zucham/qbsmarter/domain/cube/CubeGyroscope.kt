package com.zucham.qbsmarter.domain.cube

import com.zakgof.korender.math.Quaternion
import com.zakgof.korender.math.Transform
import com.zakgof.korender.math.Vec3
import kotlin.concurrent.Volatile
import kotlin.math.exp

/**
 * Gyroscope-driven orientation of the rendered cube.
 *
 * Owns everything about "where the physical cube is pointing" so that
 * [RubiksCube] stays about cube *state* and [CubeOrbiter] stays about the
 * user's drag. The three concerns compose at exactly one place –
 * [RubiksCube.pieceTransform] – and nowhere else.
 *
 * ## Pipeline
 *
 * A gyro sample travels through four stages before it reaches a pixel:
 *
 * ```
 *   raw (cube axes)  --remap-->  sample  --basis-->  target  --slerp-->  displayed
 *        BLE thread                                              render thread
 * ```
 *
 *  1. **Remap.** The cube reports its quaternion in its own axis
 *     convention, which is not the renderer's. See [toRendererFrame].
 *  2. **Basis.** [target] is `basis * sample`. The basis starts at
 *     identity, so tracking is *absolute*: enabling the gyro shows the
 *     orientation the cube actually reports. [recenter] captures the
 *     current sample's inverse as the new basis, which re-homes the cube
 *     to the default pose without stopping tracking.
 *  3. **Smoothing.** Gyro notifications arrive in bursts at an uneven
 *     rate well below the display refresh rate. Rendering [target]
 *     directly reads as a visible stutter – the cube teleports between
 *     poses. [advance] instead eases [displayed] toward [target] a
 *     fraction of the remaining arc per frame.
 *
 * ## Threading
 *
 * Two threads touch this class and they touch disjoint fields:
 *
 *  * The BLE/parser thread calls [onSample], and the UI thread calls
 *    [setEnabled] / [recenter] / [reset]. Everything they write is
 *    `@Volatile` – a plain write plus a memory barrier, no locking on the
 *    event hot path.
 *  * The render thread exclusively owns [displayed] and [cachedTransform]
 *    via [advance] and [orientation]. Nothing else reads or writes them.
 *
 * Deliberately *not* Compose `MutableState`: gyro samples arrive tens of
 * times per second and the only consumer is the Korender render loop,
 * which polls rather than subscribes. Routing them through the snapshot
 * system would invalidate Compose state on every packet and buy nothing.
 */
class CubeGyroscope {

    /**
     * Whether gyro samples drive the render. Volatile rather than
     * Compose state for the reason in the class kdoc – the UI observes
     * the ViewModel's flow, not this field.
     */
    @Volatile
    var enabled: Boolean = false
        private set

    /**
     * Most recent sample, already in renderer axes. Null until the first
     * sample arrives; [recenter] needs it to compute a basis, so it is
     * kept fresh even while [enabled] is false.
     */
    @Volatile
    private var latestSample: Quaternion? = null

    /**
     * Pre-rotation applied to every sample. Identity means "show the
     * cube's absolute reported orientation"; [recenter] sets it to the
     * inverse of the live sample so the current physical pose maps to the
     * default on-screen pose.
     */
    @Volatile
    private var basis: Quaternion = Quaternion.IDENTITY

    /** Where [displayed] is heading. Identity whenever the gyro is off. */
    @Volatile
    private var target: Quaternion = Quaternion.IDENTITY

    /** Render-thread-owned interpolated value. See class kdoc. */
    private var displayed: Quaternion = Quaternion.IDENTITY

    /**
     * [displayed] as a Transform, rebuilt only when [displayed] actually
     * moves. [RubiksCube.pieceTransform] runs once per cubie – 26 times
     * per frame – and would otherwise redo the same quaternion-to-matrix
     * conversion for every one of them.
     */
    private var cachedTransform: Transform = Transform.IDENTITY

    /**
     * Backing flag for [isIdle]. Render-thread-owned like [displayed].
     */
    private var idle: Boolean = true

    /**
     * True while [displayed] sits at identity with nothing to interpolate
     * toward – i.e. the gyro contributes nothing to the render. Lets the
     * caller skip a matrix multiply per cubie in the overwhelmingly
     * common case where the feature is switched off.
     */
    val isIdle: Boolean
        get() = idle

    /** Current gyro rotation to compose into the scene. */
    val orientation: Transform
        get() = cachedTransform

    // -- Input ------------------------------------------------------------

    /**
     * Feed one [com.zucham.qbsmarter.domain.driver.SmartCubeEvent.Gyro]
     * quaternion, straight off the wire in cube axes.
     *
     * Called unconditionally, even while disabled: keeping [latestSample]
     * warm means [recenter] and a subsequent [setEnabled] have a real
     * pose to work from instead of waiting for the next packet.
     */
    fun onSample(raw: Quaternion) {
        val mapped = raw.toRendererFrame()
        latestSample = mapped
        if (enabled) target = basis * mapped
    }

    /**
     * Turn gyro tracking on or off.
     *
     * Switching **on** picks up the latest sample immediately if one has
     * already arrived, so the cube starts moving on the current frame
     * rather than at the next packet.
     *
     * Switching **off** clears the tracking state and points [target]
     * back at identity. [advance] then eases the cube back to its
     * drag-only orientation over the usual smoothing window instead of
     * snapping – the reset is a state reset, not a visual jolt.
     */
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (value) {
            latestSample?.let { target = basis * it }
        } else {
            reset()
        }
    }

    /**
     * Re-home the cube: make the pose it is in right now read as the
     * default orientation, while tracking continues from there.
     *
     * This is the gyro half of the Solve screen's "Reset orientation"
     * button – the orbiter half animates the manual drag offset back to
     * identity at the same time.
     *
     * No-op before the first sample: with nothing to measure against
     * there is no meaningful basis to capture, and leaving the existing
     * one alone is better than resetting it to something arbitrary.
     */
    fun recenter() {
        val sample = latestSample ?: return
        basis = sample.conjugate()
        if (enabled) target = basis * sample
    }

    /**
     * Drop all tracking state.
     *
     * [displayed] is deliberately left alone: pointing [target] at
     * identity and letting [advance] ease the cube home is what turns
     * "reset the state" into a motion the user can follow rather than a
     * frame-boundary snap.
     *
     * Called when the gyro is switched off and when the cube disconnects
     * – a frozen pose from a cube that is no longer on the wire is worse
     * than no pose at all.
     */
    fun reset() {
        basis = Quaternion.IDENTITY
        latestSample = null
        target = Quaternion.IDENTITY
    }

    // -- Render loop ------------------------------------------------------

    /**
     * Advance the smoothing by one frame. Call exactly once per rendered
     * frame, from the render thread, before reading [orientation].
     *
     * The interpolation factor is derived from [dtSeconds] rather than
     * being a fixed per-frame fraction, so the cube settles at the same
     * wall-clock rate on a 60 Hz phone and a 120 Hz one. [SMOOTHING_TAU]
     * is the time constant: after `tau` seconds roughly 63% of the gap to
     * [target] has been closed.
     *
     * @param dtSeconds time since the previous frame, in seconds.
     */
    fun advance(dtSeconds: Float) {
        val goal = target
        if (idle && goal === Quaternion.IDENTITY) return

        // Clamp before using dt: a frame served after a stall (GC pause,
        // app resume, first frame after surface creation) reports a huge
        // delta that would otherwise collapse the smoothing into a jump.
        val dt = dtSeconds.coerceIn(0f, MAX_FRAME_SECONDS)
        val t = 1f - exp(-dt / SMOOTHING_TAU)

        val next = if (rotationCloseness(displayed, goal) >= SETTLED_CLOSENESS) {
            // Close enough that further interpolation is sub-pixel. Land
            // exactly on the goal so `idle` can latch and the cached
            // transform stops being rebuilt.
            goal
        } else {
            slerp(displayed, goal, t)
        }

        displayed = next
        // "Contributes nothing" is a question about the *rendered* pose,
        // not about whether the gyro is switched on: a cube being held
        // at its home orientation is idle too, and skipping the compose
        // for it costs nothing to check.
        idle = rotationCloseness(next, Quaternion.IDENTITY) >= SETTLED_CLOSENESS
        cachedTransform = if (idle) Transform.IDENTITY else next.toTransform()
    }

    private companion object {
        /**
         * Smoothing time constant, in seconds.
         *
         * 60 ms puts the per-frame interpolation factor at ~0.24 on a
         * 60 Hz display, matching the 0.25 the official GAN reference
         * client uses – close enough to follow a fast cube rotation
         * without lagging behind it, slow enough to absorb the jitter
         * between packets. Unlike a fixed fraction it holds that feel on
         * 90 Hz and 120 Hz panels too.
         */
        const val SMOOTHING_TAU = 0.06f

        /**
         * Upper bound on the frame delta fed into the smoothing (50 ms,
         * i.e. 20 fps). Past this the app has stalled rather than merely
         * rendered slowly, and honouring the real delta would teleport
         * the cube.
         */
        const val MAX_FRAME_SECONDS = 0.05f

        /**
         * `|dot|` above which two rotations count as the same pose and
         * the smoothing latches onto its goal exactly.
         *
         * The latch is a jump, so the threshold has to be tight enough
         * that the jump is invisible: 0.9999999 is 0.05°, which moves a
         * cube edge by well under a pixel at any plausible render size.
         * (The obvious-looking 0.99999 is 0.51° – about 1.6 px on a
         * 350 px cube, which reads as a small twitch at the end of every
         * settle.)
         *
         * Tight thresholds are only useful if the interpolation can
         * actually reach them in single precision; this one is verified
         * reachable, with margin, by float32 slerp.
         */
        const val SETTLED_CLOSENESS = 0.9999999f
    }
}

/**
 * Map a quaternion from GAN cube axes to renderer axes.
 *
 * The cube's sensor frame and the renderer's world frame disagree about
 * which way is up: `(x, y, z) -> (x, z, -y)`, a -90° rotation about X.
 * This is the same mapping the official GAN reference client applies
 * before handing the quaternion to its 3D scene, and it is the reason a
 * naively-applied gyro quaternion makes the cube tumble around the wrong
 * axis.
 *
 * Normalised on the way out: the wire format quantises each component to
 * 15 bits plus a sign, so the reassembled quaternion is only
 * approximately unit-length and the error compounds through composition.
 */
internal fun Quaternion.toRendererFrame(): Quaternion =
    Quaternion(w, Vec3(r.x, r.z, -r.y)).normalize()
