package com.kirisamenanoha.hidslimebridge

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class Quaternion(
    val w: Float,
    val x: Float,
    val y: Float,
    val z: Float,
) {
    fun normalized(): Quaternion {
        val n = sqrt((w * w + x * x + y * y + z * z).toDouble()).toFloat()

        if (n <= 0f || n.isNaN() || n.isInfinite()) {
            return Quaternion(1f, 0f, 0f, 0f)
        }

        return Quaternion(
            w = w / n,
            x = x / n,
            y = y / n,
            z = z / n,
        )
    }
}

data class Acceleration(
    val x: Float,
    val y: Float,
    val z: Float,
)

object SlimePacketBuilder {
    fun buildRotationPacket(
        q: Quaternion,
        packetCounter: Long,
        sensorId: Int,
    ): ByteArray {
        val normalized = q.normalized()

        val buffer = ByteBuffer.allocate(4 + 8 + 1 + 1 + 16 + 1)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // Packet type 17: RotationData
        buffer.putInt(17)
        buffer.putLong(packetCounter)
        buffer.put(sensorId.coerceIn(0, 255).toByte())

        // dataType = 1
        buffer.put(1.toByte())

        /*
         * ここでは軸変換しない。
         * HID reportから読んだ qx, qy, qz, qw を
         * UDP RotationDataへ x, y, z, w の順で載せる。
         */
        buffer.putFloat(normalized.x)
        buffer.putFloat(normalized.y)
        buffer.putFloat(normalized.z)
        buffer.putFloat(normalized.w)

        // accuracyInfo
        buffer.put(0.toByte())

        return buffer.array()
    }

    fun buildAccelerationPacket(
        acceleration: Acceleration,
        packetCounter: Long,
        sensorId: Int,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(4 + 8 + 12 + 1)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // Packet type 4: Accel
        buffer.putInt(4)
        buffer.putLong(packetCounter)

        /*
         * SlimeVR UDP AccelPacket:
         * float x
         * float y
         * float z
         * uint8 sensorId
         *
         * ここでも軸変換や符号反転はしない。
         * HidReaderで読んだ acceleration.x/y/z をそのまま送る。
         */
        buffer.putFloat(acceleration.x)
        buffer.putFloat(acceleration.y)
        buffer.putFloat(acceleration.z)
        buffer.put(sensorId.coerceIn(0, 255).toByte())

        return buffer.array()
    }

    fun buildHandshake(
        mac: String,
        firmwareVersion: String,
        packetCounter: Long,
    ): ByteArray {
        val firmwareString = "MoSlime/AndroidHID - Puck Version:$firmwareVersion"
        val macBytes = parseMac(mac)
        val fwInt = firmwareVersion.replace(".", "").toIntOrNull() ?: 100
        val fwBytes = firmwareString.toByteArray(Charsets.UTF_8)

        val buffer = ByteBuffer.allocate(
            4 + 8 + 4 + 4 + 4 + 12 + 4 + 1 + fwBytes.size + 6 + 1,
        )

        buffer.order(ByteOrder.BIG_ENDIAN)

        // Packet type 3: Handshake
        buffer.putInt(3)
        buffer.putLong(packetCounter)

        // Board / IMU / MCU values
        buffer.putInt(10)
        buffer.putInt(8)
        buffer.putInt(7)

        // Reserved values
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.putInt(0)

        // Firmware version int
        buffer.putInt(fwInt)

        // Firmware string
        buffer.put(fwBytes.size.toByte())
        buffer.put(fwBytes)

        // MAC
        buffer.put(macBytes)

        // Mode
        buffer.put(255.toByte())

        return buffer.array()
    }

    fun buildSensorInfo(
        packetCounter: Long,
        sensorId: Int,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(4 + 8 + 1 + 1 + 1)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // Packet type 15: SensorInfo
        buffer.putInt(15)
        buffer.putLong(packetCounter)
        buffer.put(sensorId.coerceIn(0, 255).toByte())

        // Sensor status
        buffer.put(0.toByte())

        // Sensor type
        buffer.put(8.toByte())

        return buffer.array()
    }

    private fun parseMac(mac: String): ByteArray {
        val clean = mac
            .replace(":", "")
            .replace("-", "")
            .trim()

        require(clean.length == 12) {
            "MAC address must be 6 bytes, e.g. 3C:38:F4:B4:95:01"
        }

        return ByteArray(6) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}

class SlimeVRSender(
    ip: String,
    private val port: Int,
    private val mac: String = "3C:38:F4:B4:95:01",
    private val firmwareVersion: String = "1.0.0",
) : AutoCloseable {
    private val socket = DatagramSocket()
    private val address = InetAddress.getByName(ip)

    private var packetCounter = 0L
    private var handshakeSent = false

    private val registeredSensorIds = linkedSetOf<Int>()

    @Synchronized
    fun ensureSensor(sensorId: Int) {
        val safeSensorId = sensorId.coerceIn(0, 255)

        if (!handshakeSent) {
            packetCounter += 1
            sendRaw(
                SlimePacketBuilder.buildHandshake(
                    mac = mac,
                    firmwareVersion = firmwareVersion,
                    packetCounter = packetCounter,
                ),
            )
            handshakeSent = true
        }

        if (!registeredSensorIds.contains(safeSensorId)) {
            packetCounter += 1
            sendRaw(
                SlimePacketBuilder.buildSensorInfo(
                    packetCounter = packetCounter,
                    sensorId = safeSensorId,
                ),
            )
            registeredSensorIds.add(safeSensorId)
        }
    }

    @Synchronized
    fun sendQuaternion(
        sensorId: Int,
        quaternion: Quaternion,
    ) {
        val safeSensorId = sensorId.coerceIn(0, 255)

        ensureSensor(safeSensorId)

        packetCounter += 1

        sendRaw(
            SlimePacketBuilder.buildRotationPacket(
                q = quaternion.normalized(),
                packetCounter = packetCounter,
                sensorId = safeSensorId,
            ),
        )
    }

    @Synchronized
    fun sendAcceleration(
        sensorId: Int,
        acceleration: Acceleration,
    ) {
        val safeSensorId = sensorId.coerceIn(0, 255)

        ensureSensor(safeSensorId)

        packetCounter += 1

        sendRaw(
            SlimePacketBuilder.buildAccelerationPacket(
                acceleration = acceleration,
                packetCounter = packetCounter,
                sensorId = safeSensorId,
            ),
        )
    }

    @Synchronized
    fun registeredSensors(): List<Int> {
        return registeredSensorIds.toList()
    }

    private fun sendRaw(bytes: ByteArray) {
        val packet = DatagramPacket(
            bytes,
            bytes.size,
            address,
            port,
        )

        socket.send(packet)
    }

    override fun close() {
        socket.close()
    }
}