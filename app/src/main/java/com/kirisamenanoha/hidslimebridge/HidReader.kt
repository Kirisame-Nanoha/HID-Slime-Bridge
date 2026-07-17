package com.kirisamenanoha.hidslimebridge

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import kotlin.math.max

interface HidReaderListener {
    fun onReaderStarted(deviceName: String)
    fun onReaderStopped(reason: String)
    fun onTrackerInfo(sensorId: Int, deviceId: Int, imuType: Int, batteryPercent: Int)

    fun onQuaternion(
        sensorId: Int,
        rawQuaternion: Quaternion,
        sentQuaternion: Quaternion,
        acceleration: Acceleration,
        packetsRead: Long,
    )

    fun onStatus(sensorId: Int, status: Int, packetsReceivedCounter: Int, packetsLostCounter: Int)
    fun onReaderError(message: String, throwable: Throwable? = null)
}

class HidReader(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inputEndpoint: UsbEndpoint,
    private val sender: SlimeVRSender,
    private val sensorIdBase: Int,
    private val sensorIdResolver: (uidHex: String, fallbackSensorId: Int) -> Int,
    private val mountRotationProvider: (sensorId: Int) -> SensorMountRotation,
    private val listener: HidReaderListener,
) : AutoCloseable {
    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var packetsRead = 0L

    private var trackerUidHex: String? = null
    private var resolvedSensorId: Int? = null

    fun start() {
        if (running) return

        running = true
        thread = Thread(
            { readLoop() },
            "HidSlimeReader-${device.deviceName}",
        ).also {
            it.start()
        }
    }

    private fun readLoop() {
        try {
            val claimed = connection.claimInterface(usbInterface, true)

            if (!claimed) {
                listener.onReaderError("USB interfaceをclaimできませんでした: ${device.deviceName}")
                stopInternal("claim failed")
                return
            }

            listener.onReaderStarted(device.deviceName)

            val readSize = max(64, inputEndpoint.maxPacketSize)
            val buffer = ByteArray(readSize)

            while (running) {
                val length = connection.bulkTransfer(
                    inputEndpoint,
                    buffer,
                    buffer.size,
                    500,
                )

                if (!running) break

                if (length > 0) {
                    parseReport(buffer, length)
                }
            }

            listener.onReaderStopped("stopped: ${device.deviceName}")
        } catch (t: Throwable) {
            if (running) {
                listener.onReaderError(
                    "HID読み取り中にエラーが発生しました: ${device.deviceName} / ${t.message}",
                    t,
                )
            }
        } finally {
            closeQuietly()
        }
    }

    private fun parseReport(report: ByteArray, length: Int) {
        if (length < HID_PACKET_SIZE) return

        var offset = 0

        while (offset + HID_PACKET_SIZE <= length) {
            parsePacket(report, offset)
            offset += HID_PACKET_SIZE
        }
    }

    private fun parsePacket(report: ByteArray, offset: Int) {
        val packetType = u8(report, offset + 0)
        val firmwareDeviceId = u8(report, offset + 1)

        when (packetType) {
            PACKET_TYPE_RECEIVER_REGISTER -> {
                val uid = readUidHex(report, offset + 2, 6)

                if (uid.isNotBlank() && uid != "000000000000") {
                    trackerUidHex = uid
                    resolvedSensorId = sensorIdResolver(uid, sensorIdBase)

                    val sensorId = resolvedSensorId()
                    sender.ensureSensor(sensorId)

                    listener.onTrackerInfo(
                        sensorId = sensorId,
                        deviceId = firmwareDeviceId,
                        imuType = TORAMARU_IMU_TYPE,
                        batteryPercent = 0,
                    )
                }
            }

            PACKET_TYPE_DEVICE_INFO -> {
                val sensorId = resolvedSensorId()
                val batteryPercent = u8(report, offset + 2)
                val imuType = u8(report, offset + 8)

                sender.ensureSensor(sensorId)

                listener.onTrackerInfo(
                    sensorId = sensorId,
                    deviceId = firmwareDeviceId,
                    imuType = imuType,
                    batteryPercent = batteryPercent,
                )
            }

            PACKET_TYPE_QUAT_ACCEL -> {
                val sensorId = resolvedSensorId()

                val qx = q15ToFloat(i16le(report, offset + 2))
                val qy = q15ToFloat(i16le(report, offset + 4))
                val qz = q15ToFloat(i16le(report, offset + 6))
                val qw = q15ToFloat(i16le(report, offset + 8))

                /*
                 * ファームウェアのHID reportに入っている値をそのまま使う。
                 *
                 * ファーム側:
                 *   report[34..35] = -q[1] -> qx
                 *   report[36..37] = -q[2] -> qy
                 *   report[38..39] =  q[3] -> qz
                 *   report[40..41] =  q[0] -> qw
                 *
                 * Android側ではこれ以上、Swap/Flip/座標変換しない。
                 */
                val rawQuaternion = Quaternion(
                    w = qw,
                    x = qx,
                    y = qy,
                    z = qz,
                ).normalized()

                val rawAcceleration = Acceleration(
                    x = i16le(report, offset + 10).toFloat() / 128f,
                    y = i16le(report, offset + 12).toFloat() / 128f,
                    z = i16le(report, offset + 14).toFloat() / 128f,
                )

                val mountRotation = mountRotationProvider(sensorId)
                val sentQuaternion = AxisTransform.applyMountRotation(rawQuaternion, mountRotation)
                val sentAcceleration = AxisTransform.applyMountRotation(rawAcceleration, mountRotation)

                /*
                 * UDPへ2種類送る:
                 *   packet type 17: RotationData
                 *   packet type 4 : Accel
                 *
                 * trackerごとのDEG_0/90/180/270設定に応じて
                 * quaternionとaccelerationの両方を補正して送る。
                 */
                sender.sendQuaternion(sensorId, sentQuaternion)
                sender.sendAcceleration(sensorId, sentAcceleration)

                packetsRead += 1

                listener.onQuaternion(
                    sensorId = sensorId,
                    rawQuaternion = rawQuaternion,
                    sentQuaternion = sentQuaternion,
                    acceleration = sentAcceleration,
                    packetsRead = packetsRead,
                )
            }

            PACKET_TYPE_STATUS -> {
                val sensorId = resolvedSensorId()

                val status = u8(report, offset + 2)
                val received = u8(report, offset + 4)
                val lost = u8(report, offset + 5)

                listener.onStatus(
                    sensorId = sensorId,
                    status = status,
                    packetsReceivedCounter = received,
                    packetsLostCounter = lost,
                )
            }

            else -> {
                Log.d(
                    TAG,
                    "Unknown HID packet type=$packetType offset=$offset device=${device.deviceName}",
                )
            }
        }
    }

    private fun resolvedSensorId(): Int {
        val cached = resolvedSensorId

        if (cached != null) {
            return cached.coerceIn(0, 255)
        }

        val uid = trackerUidHex

        if (!uid.isNullOrBlank()) {
            val resolved = sensorIdResolver(uid, sensorIdBase)
            resolvedSensorId = resolved
            return resolved.coerceIn(0, 255)
        }

        return sensorIdBase.coerceIn(0, 255)
    }

    fun stop() {
        if (!running) return
        stopInternal("manual stop")
    }

    private fun stopInternal(reason: String) {
        running = false

        try {
            thread?.interrupt()
        } catch (_: Throwable) {
        }

        listener.onReaderStopped(reason)
    }

    private fun closeQuietly() {
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Throwable) {
        }

        try {
            connection.close()
        } catch (_: Throwable) {
        }
    }

    override fun close() {
        stop()
        closeQuietly()
    }

    companion object {
        private const val TAG = "HidReader"

        private const val HID_PACKET_SIZE = 16

        private const val PACKET_TYPE_DEVICE_INFO = 0
        private const val PACKET_TYPE_QUAT_ACCEL = 1
        private const val PACKET_TYPE_STATUS = 3
        private const val PACKET_TYPE_RECEIVER_REGISTER = 255

        private const val TORAMARU_IMU_TYPE = 13

        private fun u8(bytes: ByteArray, index: Int): Int {
            return bytes[index].toInt() and 0xFF
        }

        private fun i16le(bytes: ByteArray, index: Int): Short {
            val low = bytes[index].toInt() and 0xFF
            val high = bytes[index + 1].toInt() and 0xFF
            return ((high shl 8) or low).toShort()
        }

        private fun q15ToFloat(value: Short): Float {
            val i = value.toInt()

            return if (i < 0) {
                i / 32768f
            } else {
                i / 32767f
            }.coerceIn(-1f, 1f)
        }

        private fun readUidHex(bytes: ByteArray, start: Int, length: Int): String {
            val builder = StringBuilder(length * 2)

            for (i in 0 until length) {
                val value = bytes[start + i].toInt() and 0xFF
                builder.append(value.toString(16).padStart(2, '0').uppercase())
            }

            return builder.toString()
        }
    }
}