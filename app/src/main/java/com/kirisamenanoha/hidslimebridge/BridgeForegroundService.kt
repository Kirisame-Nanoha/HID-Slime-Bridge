package com.kirisamenanoha.hidslimebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.ConcurrentHashMap

class BridgeForegroundService : Service(), HidReaderListener {
    private lateinit var usbManager: UsbManager
    private lateinit var prefs: SharedPreferences

    private val readers = mutableListOf<HidReader>()
    private var sender: SlimeVRSender? = null

    private var wakeLock: PowerManager.WakeLock? = null

    private val trackerInfoSent = mutableSetOf<Int>()
    private val lastUiBroadcastAt = ConcurrentHashMap<Int, Long>()
    private val sensorMountRotations = ConcurrentHashMap<Int, SensorMountRotation>()

    override fun onCreate() {
        super.onCreate()

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val ip = intent.getStringExtra(EXTRA_IP).orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, 6969)

                startForeground(NOTIFICATION_ID, buildNotification("Bridge running"))
                acquireWakeLock()
                startBridge(ip, port)
            }

            ACTION_STOP -> {
                stopBridge("manual stop")
                stopSelf()
            }

            ACTION_UPDATE_SENSOR_ORIENTATION -> {
                val sensorId = intent.getIntExtra(EXTRA_SENSOR_ID, -1)
                val degrees = intent.getIntExtra(EXTRA_SENSOR_ORIENTATION_DEGREES, 0)

                if (sensorId in 0..255) {
                    val rotation = SensorMountRotation.fromDegrees(degrees)
                    sensorMountRotations[sensorId] = rotation

                    prefs.edit()
                        .putInt(sensorMountRotationKey(sensorId), rotation.degrees)
                        .apply()

                    broadcastStatus(
                        message = "Tracker #$sensorId orientation: ${rotation.label}",
                        level = "OK",
                    )
                }
            }

            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("Bridge idle"))
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopBridge("service destroyed")
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startBridge(ip: String, port: Int) {
        stopBridge("restart")

        if (ip.isBlank()) {
            broadcastError("送信先IPアドレスが空です。")
            stopSelf()
            return
        }

        val devices = findCandidateUsbDevices()
            .filter { usbManager.hasPermission(it) }
            .take(MAX_USB_DEVICE_COUNT)

        if (devices.isEmpty()) {
            broadcastError("許可済みHIDデバイスがありません。")
            stopSelf()
            return
        }

        val newSender = try {
            SlimeVRSender(ip = ip, port = port)
        } catch (t: Throwable) {
            broadcastError("UDP送信初期化失敗: ${t.message}")
            stopSelf()
            return
        }

        sender = newSender
        trackerInfoSent.clear()
        lastUiBroadcastAt.clear()

        var startedCount = 0

        devices.forEachIndexed { index, device ->
            val selection = selectHidInputEndpoint(device)

            if (selection == null) {
                broadcastStatus("HID入力エンドポイントなし: ${device.deviceName}", "WARNING")
                return@forEachIndexed
            }

            val connection = usbManager.openDevice(device)

            if (connection == null) {
                broadcastStatus("USBデバイスを開けませんでした: ${device.deviceName}", "WARNING")
                return@forEachIndexed
            }

            try {
                val reader = HidReader(
                    device = device,
                    connection = connection,
                    usbInterface = selection.usbInterface,
                    inputEndpoint = selection.inputEndpoint,
                    sender = newSender,
                    sensorIdBase = index,
                    sensorIdResolver = { uidHex, fallback ->
                        resolveSensorIdForUid(uidHex, fallback)
                    },
                    mountRotationProvider = { sensorId ->
                        sensorMountRotationFor(sensorId)
                    },
                    listener = this,
                )

                readers.add(reader)
                reader.start()
                startedCount += 1
            } catch (t: Throwable) {
                try {
                    connection.close()
                } catch (_: Throwable) {
                }

                broadcastError("開始失敗: ${device.deviceName} / ${t.message}")
            }
        }

        if (startedCount <= 0) {
            stopBridge("no devices started")
            broadcastError("開始できたUSB HIDデバイスがありません。")
            stopSelf()
            return
        }

        broadcastStatus(
            message = "送信中: USB $startedCount 台 → $ip:$port",
            level = "OK",
        )
    }

    private fun stopBridge(reason: String) {
        val oldReaders = readers.toList()
        val oldSender = sender

        readers.clear()
        sender = null
        trackerInfoSent.clear()
        lastUiBroadcastAt.clear()

        oldReaders.forEach { reader ->
            try {
                reader.close()
            } catch (_: Throwable) {
            }
        }

        try {
            oldSender?.close()
        } catch (_: Throwable) {
        }

        broadcastStatus("停止: $reason", "IDLE")
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HidSlimeBridge:BridgeWakeLock",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Throwable) {
        }

        wakeLock = null
    }

    private fun findCandidateUsbDevices(): List<UsbDevice> {
        val devices = usbManager.deviceList.values.toList()

        return devices
            .filter { selectHidInputEndpoint(it) != null }
            .sortedWith(
                compareBy<UsbDevice> {
                    if (isKnownSlimeHidDevice(it)) 0 else 1
                }.thenBy {
                    it.deviceName
                },
            )
    }

    private fun selectHidInputEndpoint(device: UsbDevice): EndpointSelection? {
        var fallback: EndpointSelection? = null

        for (interfaceIndex in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(interfaceIndex)
            val isHid = usbInterface.interfaceClass == UsbConstants.USB_CLASS_HID

            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                val isIn = endpoint.direction == UsbConstants.USB_DIR_IN

                if (!isIn) continue

                val selection = EndpointSelection(usbInterface, endpoint)

                if (isHid && endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                    return selection
                }

                if (fallback == null && isHid) {
                    fallback = selection
                }
            }
        }

        return fallback
    }

    private fun isKnownSlimeHidDevice(device: UsbDevice): Boolean {
        if (device.vendorId != SLIME_HID_VID) return false

        return device.productId == SLIME_HID_RECEIVER_PID ||
                device.productId == SLIME_HID_TRACKER_PID
    }

    @Synchronized
    private fun resolveSensorIdForUid(uidHex: String, fallbackSensorId: Int): Int {
        val key = "$KEY_UID_SENSOR_PREFIX$uidHex"
        val existing = prefs.getInt(key, -1)

        if (existing in 0..255) {
            return existing
        }

        val usedIds = prefs.all
            .filterKeys { it.startsWith(KEY_UID_SENSOR_PREFIX) }
            .values
            .mapNotNull { it as? Int }
            .filter { it in 0..255 }
            .toMutableSet()

        val assigned = if (fallbackSensorId in 0..255 && !usedIds.contains(fallbackSensorId)) {
            fallbackSensorId
        } else {
            (0..255).firstOrNull { !usedIds.contains(it) } ?: fallbackSensorId.coerceIn(0, 255)
        }

        prefs.edit()
            .putInt(key, assigned)
            .apply()

        return assigned
    }

    private fun sensorMountRotationFor(sensorId: Int): SensorMountRotation {
        val safeSensorId = sensorId.coerceIn(0, 255)
        val cached = sensorMountRotations[safeSensorId]

        if (cached != null) {
            return cached
        }

        val degrees = prefs.getInt(sensorMountRotationKey(safeSensorId), SensorMountRotation.DEG_0.degrees)
        val rotation = SensorMountRotation.fromDegrees(degrees)
        sensorMountRotations[safeSensorId] = rotation

        return rotation
    }

    private fun sensorMountRotationKey(sensorId: Int): String {
        return "$KEY_SENSOR_MOUNT_ROTATION_PREFIX${sensorId.coerceIn(0, 255)}"
    }

    override fun onReaderStarted(deviceName: String) {
        val intent = Intent(ACTION_BRIDGE_EVENT).setPackage(packageName)
        intent.putExtra(EXTRA_EVENT_TYPE, EVENT_READER_STARTED)
        intent.putExtra(EXTRA_DEVICE_NAME, deviceName)
        sendBroadcast(intent)
    }

    override fun onReaderStopped(reason: String) {
        val intent = Intent(ACTION_BRIDGE_EVENT).setPackage(packageName)
        intent.putExtra(EXTRA_EVENT_TYPE, EVENT_READER_STOPPED)
        intent.putExtra(EXTRA_MESSAGE, reason)
        sendBroadcast(intent)
    }

    override fun onTrackerInfo(sensorId: Int, deviceId: Int, imuType: Int, batteryPercent: Int) {
        synchronized(trackerInfoSent) {
            if (trackerInfoSent.contains(sensorId)) {
                return
            }

            trackerInfoSent.add(sensorId)
        }

        val intent = Intent(ACTION_BRIDGE_EVENT).setPackage(packageName)
        intent.putExtra(EXTRA_EVENT_TYPE, EVENT_TRACKER_INFO)
        intent.putExtra(EXTRA_SENSOR_ID, sensorId)
        intent.putExtra(EXTRA_DEVICE_ID, deviceId)
        intent.putExtra(EXTRA_IMU_TYPE, imuType)
        intent.putExtra(EXTRA_BATTERY, batteryPercent)
        sendBroadcast(intent)
    }

    override fun onQuaternion(
        sensorId: Int,
        rawQuaternion: Quaternion,
        sentQuaternion: Quaternion,
        acceleration: Acceleration,
        packetsRead: Long,
    ) {
        val now = System.currentTimeMillis()
        val last = lastUiBroadcastAt[sensorId] ?: 0L

        if (now - last < UI_UPDATE_INTERVAL_MS) {
            return
        }

        lastUiBroadcastAt[sensorId] = now

        val intent = Intent(ACTION_BRIDGE_EVENT).setPackage(packageName)
        intent.putExtra(EXTRA_EVENT_TYPE, EVENT_QUATERNION)
        intent.putExtra(EXTRA_SENSOR_ID, sensorId)
        intent.putExtra(EXTRA_QW, sentQuaternion.w)
        intent.putExtra(EXTRA_QX, sentQuaternion.x)
        intent.putExtra(EXTRA_QY, sentQuaternion.y)
        intent.putExtra(EXTRA_QZ, sentQuaternion.z)
        intent.putExtra(EXTRA_AX, acceleration.x)
        intent.putExtra(EXTRA_AY, acceleration.y)
        intent.putExtra(EXTRA_AZ, acceleration.z)
        intent.putExtra(EXTRA_PACKETS, packetsRead)
        sendBroadcast(intent)
    }

    override fun onStatus(sensorId: Int, status: Int, packetsReceivedCounter: Int, packetsLostCounter: Int) {
        val intent = Intent(ACTION_BRIDGE_EVENT).setPackage(packageName)
        intent.putExtra(EXTRA_EVENT_TYPE, EVENT_HID_STATUS)
        intent.putExtra(EXTRA_SENSOR_ID, sensorId)
        intent.putExtra(EXTRA_STATUS, status)
        intent.putExtra(EXTRA_RX, packetsReceivedCounter)
        intent.putExtra(EXTRA_LOST, packetsLostCounter)
        sendBroadcast(intent)
    }

    override fun onReaderError(message: String, throwable: Throwable?) {
        broadcastError(message)
    }

    private fun broadcastStatus(message: String, level: String) {
        val intent = Intent(ACTION_BRIDGE_EVENT).setPackage(packageName)
        intent.putExtra(EXTRA_EVENT_TYPE, EVENT_STATUS)
        intent.putExtra(EXTRA_MESSAGE, message)
        intent.putExtra(EXTRA_LEVEL, level)
        sendBroadcast(intent)

        updateNotification(message)
    }

    private fun broadcastError(message: String) {
        val intent = Intent(ACTION_BRIDGE_EVENT).setPackage(packageName)
        intent.putExtra(EXTRA_EVENT_TYPE, EVENT_ERROR)
        intent.putExtra(EXTRA_MESSAGE, message)
        sendBroadcast(intent)

        updateNotification(message)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "HID Slime Bridge",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "USB HID tracker bridge foreground service"
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle("HID Slime Bridge")
        builder.setContentText(text)
        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
        builder.setContentIntent(pendingIntent)
        builder.setOngoing(true)

        return builder.build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private data class EndpointSelection(
        val usbInterface: UsbInterface,
        val inputEndpoint: UsbEndpoint,
    )

    companion object {
        const val ACTION_START = "com.kirisamenanoha.hidslimebridge.START"
        const val ACTION_STOP = "com.kirisamenanoha.hidslimebridge.STOP"
        const val ACTION_UPDATE_SENSOR_ORIENTATION = "com.kirisamenanoha.hidslimebridge.UPDATE_SENSOR_ORIENTATION"
        const val ACTION_BRIDGE_EVENT = "com.kirisamenanoha.hidslimebridge.BRIDGE_EVENT"

        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"
        const val EXTRA_SENSOR_ORIENTATION_DEGREES = "sensor_orientation_degrees"

        const val EXTRA_EVENT_TYPE = "event_type"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_DEVICE_NAME = "device_name"

        const val EXTRA_SENSOR_ID = "sensor_id"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_IMU_TYPE = "imu_type"
        const val EXTRA_BATTERY = "battery"

        const val EXTRA_QW = "qw"
        const val EXTRA_QX = "qx"
        const val EXTRA_QY = "qy"
        const val EXTRA_QZ = "qz"

        const val EXTRA_AX = "ax"
        const val EXTRA_AY = "ay"
        const val EXTRA_AZ = "az"

        const val EXTRA_PACKETS = "packets"
        const val EXTRA_STATUS = "status"
        const val EXTRA_RX = "rx"
        const val EXTRA_LOST = "lost"

        const val EVENT_STATUS = "status"
        const val EVENT_ERROR = "error"
        const val EVENT_READER_STARTED = "reader_started"
        const val EVENT_READER_STOPPED = "reader_stopped"
        const val EVENT_TRACKER_INFO = "tracker_info"
        const val EVENT_QUATERNION = "quaternion"
        const val EVENT_HID_STATUS = "hid_status"

        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "hid_slime_bridge"

        private const val KEY_UID_SENSOR_PREFIX = "uid_sensor_"
        private const val KEY_SENSOR_MOUNT_ROTATION_PREFIX = "sensor_mount_rotation_"

        private const val SLIME_HID_VID = 0x1209
        private const val SLIME_HID_RECEIVER_PID = 0x7690
        private const val SLIME_HID_TRACKER_PID = 0x7692

        private const val MAX_USB_DEVICE_COUNT = 32
        private const val UI_UPDATE_INTERVAL_MS = 100L
    }
}