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

    data class Hardware(
        val deviceTimestamp: Long,
        val name: String,
        val hwVersion: String,
        val swVersion: String,
        val gyroSupported: Boolean,
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
