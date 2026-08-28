package com.zucham.qbsmarter.domain.driver

import com.zakgof.korender.math.Quaternion
import com.zakgof.korender.math.Vec3
import com.zucham.qbsmarter.domain.cube.CubeFace
import com.zucham.qbsmarter.domain.cube.CubeState
/**
 * Cube-generation-agnostic events. The GAN driver, future MoYu / QiYi
 * drivers, and any test driver all funnel into this one type so the rest
 * of the app sees a uniform stream regardless of cube model.
 */
sealed interface SmartCubeEvent {

    /** A single quarter turn detected on the cube. */
    data class Move(
        val face: CubeFace,
        val cw: Boolean,
        /** Cube's own clock – monotonic but skewed/drifting from device time. */
        val cubeTimestamp: Long,
        /** Wall-clock when we received the event. */
        val deviceTimestamp: Long,
    ) : SmartCubeEvent

    /** Hardware-reported full state. Used to drive RubiksCube.resync(). */
    data class Facelets(
        val state: CubeState,
        val deviceTimestamp: Long,
    ) : SmartCubeEvent

    /**
     * Hardware/firmware identification.
     *
     * May be emitted more than once per connection. GAN Gen4 spreads
     * this across four separate notifications and raises the event as
     * each one lands, so a later copy is strictly more complete than an
     * earlier one; the orchestrator also re-requests until the cube
     * answers at all. Consumers must treat it as an upsert, not a
     * one-shot.
     *
     * @property gyroSupported true / false once the cube has told us,
     *   **null while we still don't know**. Null is a real state: GAN
     *   Gen4 carries no capability bit and infers support from the
     *   hardware name, which may not have arrived yet. Persisting a
     *   premature `false` would hide the gyro controls for good, so the
     *   distinction has to survive all the way to the database.
     */
    data class Hardware(
        val deviceTimestamp: Long,
        val name: String,
        val hwVersion: String,
        val swVersion: String,
        val gyroSupported: Boolean?,
        /**
         * Which manufacturer-protocol family this hardware reply came
         * from. Set by the driver that produced the event (GAN parsers
         * always emit [CubeVendor.GAN]; the MoYu driver always emits
         * [CubeVendor.MOYU]). Persisted on the matching paired-cube row
         * by [com.zucham.qbsmarter.data.ble.ConnectionOrchestrator] so
         * the Devices screen can label the cube correctly across
         * reconnects, even before the next Hardware event arrives.
         */
        val vendor: CubeVendor,
    ) : SmartCubeEvent

    data class Battery(
        val deviceTimestamp: Long,
        val level: Int,
    ) : SmartCubeEvent

    data class Gyro(
        val quat: Quaternion,
        val angularVel: Vec3,
        val deviceTimestamp: Long,
    ) : SmartCubeEvent

    data class MovesMissed(
        val missedCount: Int,
        val deviceTimestamp: Long,
    ) : SmartCubeEvent

    data object Disconnect : SmartCubeEvent
}
