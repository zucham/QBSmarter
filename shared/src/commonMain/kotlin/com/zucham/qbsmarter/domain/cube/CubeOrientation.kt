package com.zucham.qbsmarter.domain.cube

import com.zakgof.korender.math.Quaternion
import com.zakgof.korender.math.Transform
import com.zakgof.korender.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Build a quaternion from a rotation matrix expressed as its 3 column
 * vectors (the rotated basis).
 */
fun matrixToQuaternion(rx: Vec3, ry: Vec3, rz: Vec3): Quaternion {
    val m00 = rx.x; val m01 = ry.x; val m02 = rz.x
    val m10 = rx.y; val m11 = ry.y; val m12 = rz.y
    val m20 = rx.z; val m21 = ry.z; val m22 = rz.z

    val trace = m00 + m11 + m22
    return if (trace > 0f) {
        val s = sqrt(trace + 1f) * 2f
        Quaternion(0.25f * s, Vec3((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s))
    } else if (m00 > m11 && m00 > m22) {
        val s = sqrt(1f + m00 - m11 - m22) * 2f
        Quaternion((m21 - m12) / s, Vec3(0.25f * s, (m01 + m10) / s, (m02 + m20) / s))
    } else if (m11 > m22) {
        val s = sqrt(1f + m11 - m00 - m22) * 2f
        Quaternion((m02 - m20) / s, Vec3((m01 + m10) / s, 0.25f * s, (m12 + m21) / s))
    } else {
        val s = sqrt(1f + m22 - m00 - m11) * 2f
        Quaternion((m10 - m01) / s, Vec3((m02 + m20) / s, (m12 + m21) / s, 0.25f * s))
    }
}

/** Extract a quaternion from a Transform via its action on basis vectors. */
fun quaternionFromTransform(t: Transform): Quaternion {
    val rx = t * Vec3(1f, 0f, 0f)
    val ry = t * Vec3(0f, 1f, 0f)
    val rz = t * Vec3(0f, 0f, 1f)
    return matrixToQuaternion(rx, ry, rz)
}

/** Convert a quaternion back to a Transform.rotate. */
fun Quaternion.toTransform(): Transform {
    val x = r.x; val y = r.y; val z = r.z
    val len = sqrt(x * x + y * y + z * z + w * w)
    if (len < 1e-6f) return Transform.IDENTITY
    val nx = x / len; val ny = y / len; val nz = z / len; val nw = w / len
    val angle = 2f * acos(nw.coerceIn(-1f, 1f))
    val s = sqrt(1f - nw * nw)
    val axis = if (s < 1e-6f) Vec3(1f, 0f, 0f) else Vec3(nx / s, ny / s, nz / s)
    return Transform.rotate(axis, angle)
}

fun dot(a: Quaternion, b: Quaternion): Float =
    a.w * b.w + a.r.x * b.r.x + a.r.y * b.r.y + a.r.z * b.r.z

/** Spherical-linear interpolation. */
fun slerp(a: Quaternion, b: Quaternion, t: Float): Quaternion {
    var bw = b.w
    var bx = b.r.x; var by = b.r.y; var bz = b.r.z
    var d = dot(a, b)

    // Take the shorter path (a quaternion and its negation represent the same rotation).
    if (d < 0f) {
        bw = -bw; bx = -bx; by = -by; bz = -bz
        d = -d
    }

    val aw = a.w; val ax = a.r.x; val ay = a.r.y; val az = a.r.z

    if (d > 0.9995f) {
        // Linear fallback for very close quaternions; avoids 0/0 in the
        // sinTheta0 path.
        val rw = aw + (bw - aw) * t
        val rx = ax + (bx - ax) * t
        val ry = ay + (by - ay) * t
        val rz = az + (bz - az) * t
        val len = sqrt(rw * rw + rx * rx + ry * ry + rz * rz)
        return Quaternion(rw / len, Vec3(rx / len, ry / len, rz / len))
    }

    val theta0 = acos(d.coerceIn(-1f, 1f))
    val theta = theta0 * t
    val sinTheta = sin(theta)
    val sinTheta0 = sin(theta0)
    val s0 = cos(theta) - d * sinTheta / sinTheta0
    val s1 = sinTheta / sinTheta0

    return Quaternion(
        aw * s0 + bw * s1,
        Vec3(
            ax * s0 + bx * s1,
            ay * s0 + by * s1,
            az * s0 + bz * s1,
        ),
    )
}

/**
 * The 24 rotations of a cube, as quaternions. Built once and reused for
 * "snap to nearest cube orientation" – the central trick that fights
 * floating-point drift in the per-piece transforms after many face turns.
 */
val CUBE_ORIENTATIONS: List<Quaternion> by lazy {
    val result = mutableListOf<Quaternion>()
    val halfPi = (PI / 2.0).toFloat()
    val pi = PI.toFloat()

    val faceUpRotations = listOf(
        Quaternion.IDENTITY,                          // +Y up
        Quaternion.fromAxisAngle(Vec3.X, pi),         // -Y up
        Quaternion.fromAxisAngle(Vec3.X, halfPi),     // +Z up
        Quaternion.fromAxisAngle(Vec3.X, -halfPi),    // -Z up
        Quaternion.fromAxisAngle(Vec3.Z, halfPi),     // -X up
        Quaternion.fromAxisAngle(Vec3.Z, -halfPi),    // +X up
    )

    for (faceRot in faceUpRotations) {
        for (i in 0 until 4) {
            val spin = Quaternion.fromAxisAngle(Vec3.Y, i * halfPi)
            result.add(spin * faceRot)
        }
    }
    result
}

/** Find the cube orientation closest to the given quaternion. */
fun nearestCubeOrientation(current: Quaternion): Quaternion {
    var best = CUBE_ORIENTATIONS[0]
    var bestDot = -1f
    for (q in CUBE_ORIENTATIONS) {
        // abs() because q and -q represent the same physical rotation.
        val d = abs(dot(current, q))
        if (d > bestDot) {
            bestDot = d
            best = q
        }
    }
    return best
}

/** Snap a Transform's rotation to the nearest cube orientation. */
fun Transform.snappedToCubeOrientation(): Transform =
    nearestCubeOrientation(quaternionFromTransform(this)).toTransform()

/**
 * True when [t] represents (approximately) the identity rotation. Used by
 * the Solve screen to decide whether to surface the "Reset orientation"
 * button – there's nothing to reset when the cube is already aligned.
 *
 * Compares against the identity quaternion via |dot(q, IDENTITY)| ≈ 1.
 * `abs` because q and -q both encode the identity rotation; the dot
 * product flips sign when the antipodal representation is used.
 *
 * The default tolerance (0.9995) allows ~1.8° of slop before the button
 * appears – enough that micro-jitter from the auto-snap landing animation
 * doesn't flicker the button visibility.
 */
fun isApproximatelyIdentity(t: Transform, tolerance: Float = 0.9995f): Boolean {
    val q = quaternionFromTransform(t)
    return abs(dot(q, Quaternion.IDENTITY)) >= tolerance
}
