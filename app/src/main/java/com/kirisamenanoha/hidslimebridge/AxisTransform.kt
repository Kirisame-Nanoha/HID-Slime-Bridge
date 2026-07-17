package com.kirisamenanoha.hidslimebridge

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Legacy swap/flip options. Kept so older code that still references AxisOptions compiles.
 * The current app uses SensorMountRotation instead.
 */
data class AxisOptions(
    val swapXY: Boolean = false,
    val swapYZ: Boolean = false,
    val swapXZ: Boolean = false,
    val flipX: Boolean = false,
    val flipY: Boolean = false,
    val flipZ: Boolean = false,
    val flipW: Boolean = false,
) {
    fun toFlags(): Int {
        var flags = 0

        if (swapXY) flags = flags or FLAG_SWAP_XY
        if (swapYZ) flags = flags or FLAG_SWAP_YZ
        if (swapXZ) flags = flags or FLAG_SWAP_XZ
        if (flipX) flags = flags or FLAG_FLIP_X
        if (flipY) flags = flags or FLAG_FLIP_Y
        if (flipZ) flags = flags or FLAG_FLIP_Z
        if (flipW) flags = flags or FLAG_FLIP_W

        return flags
    }

    companion object {
        const val FLAG_SWAP_XY = 1 shl 0
        const val FLAG_SWAP_YZ = 1 shl 1
        const val FLAG_SWAP_XZ = 1 shl 2
        const val FLAG_FLIP_X = 1 shl 3
        const val FLAG_FLIP_Y = 1 shl 4
        const val FLAG_FLIP_Z = 1 shl 5
        const val FLAG_FLIP_W = 1 shl 6

        fun fromFlags(flags: Int): AxisOptions {
            return AxisOptions(
                swapXY = flags and FLAG_SWAP_XY != 0,
                swapYZ = flags and FLAG_SWAP_YZ != 0,
                swapXZ = flags and FLAG_SWAP_XZ != 0,
                flipX = flags and FLAG_FLIP_X != 0,
                flipY = flags and FLAG_FLIP_Y != 0,
                flipZ = flags and FLAG_FLIP_Z != 0,
                flipW = flags and FLAG_FLIP_W != 0,
            )
        }
    }
}

enum class SensorMountRotation(
    val degrees: Int,
    val label: String,
) {
    DEG_270(270, "270° / DEG_270"),
    DEG_180(180, "180° / DEG_180"),
    DEG_90(90, "90° / DEG_90"),
    DEG_0(0, "0° / DEG_0");

    fun displayText(): String {
        return label
    }

    companion object {
        fun fromDegrees(value: Int): SensorMountRotation {
            val normalized = ((value % 360) + 360) % 360

            return values().firstOrNull { it.degrees == normalized } ?: DEG_0
        }

        fun fromPosition(position: Int): SensorMountRotation {
            return values().getOrElse(position) { DEG_0 }
        }

        fun positionOf(rotation: SensorMountRotation): Int {
            return values().indexOf(rotation).coerceAtLeast(0)
        }

        fun labels(): List<String> {
            return values().map { it.label }
        }
    }
}

object AxisTransform {
    /**
     * Legacy swap/flip transform. Not used by the current per-tracker mount-rotation flow.
     */
    fun apply(input: Quaternion, options: AxisOptions): Quaternion {
        var w = input.w
        var x = input.x
        var y = input.y
        var z = input.z

        if (options.swapXY) {
            val temp = x
            x = y
            y = temp
        }

        if (options.swapYZ) {
            val temp = y
            y = z
            z = temp
        }

        if (options.swapXZ) {
            val temp = x
            x = z
            z = temp
        }

        if (options.flipX) x = -x
        if (options.flipY) y = -y
        if (options.flipZ) z = -z
        if (options.flipW) w = -w

        return Quaternion(w, x, y, z).normalized()
    }

    fun applyMountRotation(
        input: Quaternion,
        rotation: SensorMountRotation,
    ): Quaternion {
        val correction = mountCorrectionQuaternion(rotation)

        /*
         * SensorMountRotation is the physical IMU-board orientation shown in the SlimeVR image.
         * To convert that board orientation back to the canonical DEG_0 tracker frame,
         * apply the inverse rotation around the sensor board normal/local Z axis.
         */
        return multiply(input.normalized(), correction).normalized()
    }

    fun applyMountRotation(
        input: Acceleration,
        rotation: SensorMountRotation,
    ): Acceleration {
        val correction = mountCorrectionQuaternion(rotation)
        val rotated = rotateVector(correction, input.x, input.y, input.z)

        return Acceleration(
            x = rotated[0],
            y = rotated[1],
            z = rotated[2],
        )
    }

    private fun mountCorrectionQuaternion(rotation: SensorMountRotation): Quaternion {
        val correctionDegrees = -rotation.degrees.toDouble()
        val halfRadians = correctionDegrees * PI / 180.0 / 2.0

        return Quaternion(
            w = cos(halfRadians).toFloat(),
            x = 0f,
            y = 0f,
            z = sin(halfRadians).toFloat(),
        ).normalized()
    }

    private fun multiply(a: Quaternion, b: Quaternion): Quaternion {
        return Quaternion(
            w = a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
            x = a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
            y = a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
            z = a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
        )
    }

    private fun conjugate(q: Quaternion): Quaternion {
        return Quaternion(
            w = q.w,
            x = -q.x,
            y = -q.y,
            z = -q.z,
        )
    }

    private fun rotateVector(q: Quaternion, x: Float, y: Float, z: Float): FloatArray {
        val v = Quaternion(0f, x, y, z)
        val rotated = multiply(multiply(q.normalized(), v), conjugate(q.normalized()))

        return floatArrayOf(rotated.x, rotated.y, rotated.z)
    }
}