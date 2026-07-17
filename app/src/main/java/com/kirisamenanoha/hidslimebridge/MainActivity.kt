package com.kirisamenanoha.hidslimebridge

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var prefs: SharedPreferences

    private lateinit var ipEditText: EditText
    private lateinit var portEditText: EditText
    private lateinit var statusTextView: TextView
    private lateinit var deviceTextView: TextView
    private lateinit var trackerContainer: LinearLayout

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var scanButton: Button

    private var bridgeUiState: BridgeUiState = BridgeUiState.STOPPED

    private val mainHandler = Handler(Looper.getMainLooper())
    private val trackerViews = mutableMapOf<Int, TrackerCard>()
    private val trackerOrientationSpinners = mutableMapOf<Int, Spinner>()

    private var pendingStart = false
    private var pendingPermissionDevices = mutableListOf<UsbDevice>()
    private var startCandidateDevices = emptyList<UsbDevice>()
    private var deniedUsbPermissionCount = 0
    private val activeDeviceNames = mutableSetOf<String>()

    private val appReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.parcelableExtraCompat(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                    if (!pendingStart) {
                        refreshDeviceSummary()
                        return
                    }

                    if (device != null && granted) {
                        setStatus("USB権限許可: ${device.deviceName}", StatusLevel.OK)
                    } else {
                        deniedUsbPermissionCount += 1
                        setStatus("USB権限が拒否されたデバイスをスキップします。", StatusLevel.WARNING)
                    }

                    requestNextPermissionOrStart()
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.parcelableExtraCompat(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    setStatus("USBデバイス接続: ${device?.deviceName ?: "unknown"}", StatusLevel.OK)
                    refreshDeviceSummary()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.parcelableExtraCompat(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    val detachedName = device?.deviceName

                    if (detachedName != null && activeDeviceNames.contains(detachedName)) {
                        activeDeviceNames.remove(detachedName)
                        setStatus("使用中USBデバイス切断: $detachedName", StatusLevel.WARNING)
                    } else {
                        setStatus("USBデバイス切断: ${detachedName ?: "unknown"}", StatusLevel.WARNING)
                    }

                    refreshDeviceSummary()
                }

                BridgeForegroundService.ACTION_BRIDGE_EVENT -> {
                    handleBridgeEvent(intent)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        window.statusBarColor = COLOR_BACKGROUND
        window.navigationBarColor = COLOR_BACKGROUND

        requestNotificationPermissionIfNeeded()

        buildUi()
        registerReceivers()
        refreshDeviceSummary()
        updateBridgeButtons(BridgeUiState.STOPPED)

        intent?.parcelableExtraCompat(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)?.let { device ->
            setStatus("起動時USBデバイス検出: ${device.deviceName}", StatusLevel.OK)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(appReceiver)
        } catch (_: Throwable) {
        }

        super.onDestroy()
    }

    private fun buildUi() {
        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(38), dp(18), dp(28))
        }

        rootScroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        root.addView(TextView(this).apply {
            text = "HID Slime Bridge"
            textSize = 28f
            setTextColor(COLOR_TEXT_MAIN)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.02f
        }, matchWrap())

        root.addView(TextView(this).apply {
            text = "USB HID Tracker → SlimeVR Server"
            textSize = 13f
            setTextColor(COLOR_ACCENT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }, matchWrap())

        root.addView(TextView(this).apply {
            text = "使い方\nHIDトラッカーを接続し順番にUSBのアクセス許可を行ってください。\nSlimeVRServerを起動します。送信先PCのIPアドレスを指定しSTARTを押してください。\nブリッジが開始されます。\nトラッカー装着する角度に応じてセンサーの角度をプルダウンより変更してみてください。"
            textSize = 13f
            setTextColor(COLOR_TEXT_MUTED)
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setPadding(0, dp(6), 0, dp(18))
        }, matchWrap())

        val connectionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(COLOR_SURFACE, dp(20), COLOR_STROKE, 1)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        connectionCard.addView(sectionHeader("Connection", "送信先設定"))

        connectionCard.addView(inputLabel("PC IP Address"))

        ipEditText = EditText(this).apply {
            singleLineSet()
            hint = "192.168.1.10"
            setText(prefs.getString(KEY_IP, "192.168.1.10"))
        }

        connectionCard.addView(ipEditText, matchWrap())
        connectionCard.addView(spacer(10))
        connectionCard.addView(inputLabel("SlimeVR UDP Port"))

        portEditText = EditText(this).apply {
            singleLineSet()
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "6969"
            setText(prefs.getInt(KEY_PORT, 6969).toString())
        }

        connectionCard.addView(portEditText, matchWrap())
        connectionCard.addView(spacer(16))

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        startButton = actionButton("START", ButtonRole.START) {
            setStatus("STARTボタンが押されました。USBデバイスを確認しています。", StatusLevel.OK)
            startBridge()
        }

        stopButton = actionButton("STOPPED", ButtonRole.STOP) {
            stopBridgeService("手動停止")
        }

        scanButton = actionButton("SCAN", ButtonRole.SCAN) {
            pulseScanButton()
            setStatus("SCANボタンが押されました。USBデバイスを再検索します。", StatusLevel.OK)
            refreshDeviceSummary(showToast = true)
        }

        buttonRow.addView(startButton, weightedButton())
        buttonRow.addView(stopButton, weightedButton())
        buttonRow.addView(scanButton, weightedButton())

        connectionCard.addView(buttonRow)
        root.addView(connectionCard, cardLayoutParams())

        statusTextView = TextView(this).apply {
            text = "状態: 未開始"
            textSize = 13f
            setTextColor(COLOR_TEXT_MAIN)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedDrawable(COLOR_SURFACE_DARK, dp(16), COLOR_STROKE, 1)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        root.addView(statusTextView, cardLayoutParams())

        val usbCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(COLOR_SURFACE, dp(20), COLOR_STROKE, 1)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        usbCard.addView(sectionHeader("USB Devices", "検出状態"))

        deviceTextView = TextView(this).apply {
            textSize = 12f
            setTextColor(COLOR_TEXT_MUTED)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setLineSpacing(dp(3).toFloat(), 1.0f)
            setPadding(0, dp(10), 0, 0)
        }

        usbCard.addView(deviceTextView, matchWrap())
        root.addView(usbCard, cardLayoutParams())

        root.addView(sectionHeader("Trackers", "トラッカー状態"), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(8)
            bottomMargin = dp(10)
        })

        trackerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(trackerContainer, matchWrap())

        setContentView(rootScroll)
    }

    private fun startBridge() {
        val ip = ipEditText.text.toString().trim()
        val port = portEditText.text.toString().trim().toIntOrNull()

        if (ip.isEmpty()) {
            markBridgeStopped()
            setStatus("送信先IPアドレスを入力してください。", StatusLevel.ERROR)
            return
        }

        if (port == null || port <= 0 || port > 65535) {
            markBridgeStopped()
            setStatus("UDPポート番号が不正です。", StatusLevel.ERROR)
            return
        }

        prefs.edit()
            .putString(KEY_IP, ip)
            .putInt(KEY_PORT, port)
            .apply()

        val candidates = findCandidateUsbDevices()

        if (candidates.isEmpty()) {
            markBridgeStopped()
            setStatus("対象USB HIDデバイスが見つかりません。OTG接続・USBハブ・電源を確認してください。", StatusLevel.ERROR)
            refreshDeviceSummary()
            return
        }

        markBridgeStarting()

        startCandidateDevices = candidates
        deniedUsbPermissionCount = 0
        pendingPermissionDevices = candidates
            .filter { !usbManager.hasPermission(it) }
            .toMutableList()

        if (pendingPermissionDevices.isNotEmpty()) {
            pendingStart = true
            requestNextPermissionOrStart()
            return
        }

        startBridgeService()
    }

    private fun requestNextPermissionOrStart() {
        if (pendingPermissionDevices.isNotEmpty()) {
            val device = pendingPermissionDevices.removeAt(0)
            requestUsbPermission(device)
            setStatus("USB権限を要求中: ${device.deviceName} / 残り${pendingPermissionDevices.size}台", StatusLevel.WARNING)
            return
        }

        pendingStart = false

        val permittedDevices = startCandidateDevices.filter { usbManager.hasPermission(it) }

        if (permittedDevices.isEmpty()) {
            markBridgeStopped()
            setStatus("許可済みUSB HIDデバイスがありません。", StatusLevel.ERROR)
            refreshDeviceSummary()
            return
        }

        startBridgeService()
    }

    private fun startBridgeService() {
        trackerViews.clear()
        trackerOrientationSpinners.clear()
        trackerContainer.removeAllViews()
        activeDeviceNames.clear()

        val ip = ipEditText.text.toString().trim()
        val port = portEditText.text.toString().trim().toIntOrNull() ?: 6969

        val intent = Intent(this, BridgeForegroundService::class.java).apply {
            action = BridgeForegroundService.ACTION_START
            putExtra(BridgeForegroundService.EXTRA_IP, ip)
            putExtra(BridgeForegroundService.EXTRA_PORT, port)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        markBridgeStarting()
        setStatus("フォアグラウンドサービス開始要求: $ip:$port", StatusLevel.OK)
    }

    private fun stopBridgeService(reason: String) {
        val intent = Intent(this, BridgeForegroundService::class.java).apply {
            action = BridgeForegroundService.ACTION_STOP
        }

        startService(intent)

        activeDeviceNames.clear()
        markBridgeStopped()
        setStatus("停止: $reason", StatusLevel.IDLE)
        refreshDeviceSummary()
    }

    private fun handleBridgeEvent(intent: Intent) {
        when (intent.getStringExtra(BridgeForegroundService.EXTRA_EVENT_TYPE)) {
            BridgeForegroundService.EVENT_STATUS -> {
                val message = intent.getStringExtra(BridgeForegroundService.EXTRA_MESSAGE).orEmpty()
                val level = statusLevelFromString(intent.getStringExtra(BridgeForegroundService.EXTRA_LEVEL))
                setStatus(message, level)
                refreshDeviceSummary()
            }

            BridgeForegroundService.EVENT_ERROR -> {
                val message = intent.getStringExtra(BridgeForegroundService.EXTRA_MESSAGE).orEmpty()
                markBridgeStopped()
                setStatus(message, StatusLevel.ERROR)
            }

            BridgeForegroundService.EVENT_READER_STARTED -> {
                val deviceName = intent.getStringExtra(BridgeForegroundService.EXTRA_DEVICE_NAME).orEmpty()
                if (deviceName.isNotBlank()) {
                    activeDeviceNames.add(deviceName)
                }

                markBridgeRunning()

                setStatus("HID読み取り開始: $deviceName", StatusLevel.OK)
                refreshDeviceSummary()
            }

            BridgeForegroundService.EVENT_READER_STOPPED -> {
                val message = intent.getStringExtra(BridgeForegroundService.EXTRA_MESSAGE).orEmpty()

                markBridgeStopped()

                setStatus("HID読み取り停止: $message", StatusLevel.IDLE)
            }

            BridgeForegroundService.EVENT_TRACKER_INFO -> {
                val sensorId = intent.getIntExtra(BridgeForegroundService.EXTRA_SENSOR_ID, 0)
                val deviceId = intent.getIntExtra(BridgeForegroundService.EXTRA_DEVICE_ID, 0)
                val imuType = intent.getIntExtra(BridgeForegroundService.EXTRA_IMU_TYPE, 0)
                val battery = intent.getIntExtra(BridgeForegroundService.EXTRA_BATTERY, 0)
                onTrackerInfo(sensorId, deviceId, imuType, battery)
            }

            BridgeForegroundService.EVENT_QUATERNION -> {
                val sensorId = intent.getIntExtra(BridgeForegroundService.EXTRA_SENSOR_ID, 0)

                val quat = Quaternion(
                    w = intent.getFloatExtra(BridgeForegroundService.EXTRA_QW, 1f),
                    x = intent.getFloatExtra(BridgeForegroundService.EXTRA_QX, 0f),
                    y = intent.getFloatExtra(BridgeForegroundService.EXTRA_QY, 0f),
                    z = intent.getFloatExtra(BridgeForegroundService.EXTRA_QZ, 0f),
                )

                val accel = Acceleration(
                    x = intent.getFloatExtra(BridgeForegroundService.EXTRA_AX, 0f),
                    y = intent.getFloatExtra(BridgeForegroundService.EXTRA_AY, 0f),
                    z = intent.getFloatExtra(BridgeForegroundService.EXTRA_AZ, 0f),
                )

                val packets = intent.getLongExtra(BridgeForegroundService.EXTRA_PACKETS, 0L)

                onQuaternion(sensorId, quat, accel, packets)
            }

            BridgeForegroundService.EVENT_HID_STATUS -> {
                val sensorId = intent.getIntExtra(BridgeForegroundService.EXTRA_SENSOR_ID, 0)
                val status = intent.getIntExtra(BridgeForegroundService.EXTRA_STATUS, 0)
                val rx = intent.getIntExtra(BridgeForegroundService.EXTRA_RX, 0)
                val lost = intent.getIntExtra(BridgeForegroundService.EXTRA_LOST, 0)
                onHidStatus(sensorId, status, rx, lost)
            }
        }
    }

    private fun onTrackerInfo(sensorId: Int, deviceId: Int, imuType: Int, batteryPercent: Int) {
        mainHandler.post {
            val card = trackerCard(sensorId)

            card.title.text = "Tracker #$sensorId"
            card.badge.text = "ONLINE"
            card.badge.background = roundedDrawable(COLOR_OK_DARK, dp(12), COLOR_OK, 1)

            card.meta.text = "FW ID $deviceId  /  IMU $imuType  /  BAT $batteryPercent%  /  センサーの角度 ${sensorMountRotationFor(sensorId).displayText()}"

            if (!card.hasQuaternion) {
                card.quat.text = "Waiting quaternion..."
                card.accel.text = "Accel: --"
                card.packet.text = "Packets: --"
            }
        }
    }

    private fun onQuaternion(
        sensorId: Int,
        quaternion: Quaternion,
        acceleration: Acceleration,
        packetsRead: Long,
    ) {
        mainHandler.post {
            val card = trackerCard(sensorId)

            card.hasQuaternion = true
            card.title.text = "Tracker #$sensorId"
            card.badge.text = "LIVE"
            card.badge.background = roundedDrawable(COLOR_OK_DARK, dp(12), COLOR_OK, 1)
            card.meta.text = "Last ${nowTime()}  /  センサーの角度 ${sensorMountRotationFor(sensorId).displayText()}"

            card.quat.text =
                "Quat  W ${f(quaternion.w)}   X ${f(quaternion.x)}   Y ${f(quaternion.y)}   Z ${f(quaternion.z)}"

            card.accel.text =
                "Accel X ${f(acceleration.x)}   Y ${f(acceleration.y)}   Z ${f(acceleration.z)}"

            card.packet.text = "Packets $packetsRead  /  UDP送信中"
        }
    }

    private fun onHidStatus(sensorId: Int, status: Int, packetsReceivedCounter: Int, packetsLostCounter: Int) {
        mainHandler.post {
            val card = trackerCard(sensorId)
            card.status.text = "HID Status $status  /  RX $packetsReceivedCounter  /  Lost $packetsLostCounter"
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(BridgeForegroundService.ACTION_BRIDGE_EVENT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(appReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(appReceiver, filter)
        }
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
            .take(MAX_USB_DEVICE_COUNT)
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

    private fun refreshDeviceSummary(showToast: Boolean = false) {
        val devices = usbManager.deviceList.values.toList()
        val candidates = findCandidateUsbDevices().map { it.deviceName }.toSet()
        val lines = mutableListOf<String>()

        lines += "Detected USB Devices : ${devices.size}"
        lines += "Target HID Devices   : ${candidates.size}"
        lines += "Active HID Devices   : ${activeDeviceNames.size}"
        lines += ""

        devices.forEach { device ->
            val endpoint = selectHidInputEndpoint(device)
            val known = isKnownSlimeHidDevice(device)

            val marker = when {
                activeDeviceNames.contains(device.deviceName) -> "ACTIVE"
                candidates.contains(device.deviceName) -> "READY"
                else -> "SKIP"
            }

            val permission = if (usbManager.hasPermission(device)) {
                "PERMIT"
            } else {
                "NO-PERM"
            }

            lines += "[$marker][$permission] ${device.deviceName}"
            lines += "  VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)} ${if (endpoint != null) "HID-IN" else "NO-IN"} ${if (known) "SLIME" else ""}"
        }

        deviceTextView.text = lines.joinToString("\n")

        if (showToast) {
            Toast.makeText(this, "USBデバイスを再検索しました。", Toast.LENGTH_SHORT).show()
        }
    }

    private fun trackerCard(sensorId: Int): TrackerCard {
        return trackerViews.getOrPut(sensorId) {
            val cardRoot = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedDrawable(COLOR_SURFACE, dp(20), COLOR_STROKE, 1)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val title = TextView(this).apply {
                text = "Tracker #$sensorId"
                textSize = 18f
                setTextColor(COLOR_TEXT_MAIN)
                setTypeface(typeface, Typeface.BOLD)
            }

            val badge = TextView(this).apply {
                text = "INIT"
                textSize = 11f
                setTextColor(COLOR_OK)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = roundedDrawable(COLOR_SURFACE_DARK, dp(12), COLOR_STROKE, 1)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }

            headerRow.addView(
                title,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )

            headerRow.addView(
                badge,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            val meta = TextView(this).apply {
                text = "Waiting..."
                textSize = 12f
                setTextColor(COLOR_TEXT_MUTED)
                setPadding(0, dp(6), 0, dp(10))
            }

            val orientationRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedDrawable(COLOR_SURFACE_DARK, dp(14), COLOR_STROKE, 1)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }

            val orientationLabel = TextView(this).apply {
                text = "センサーの角度"
                textSize = 12f
                setTextColor(COLOR_TEXT_MUTED)
                setTypeface(typeface, Typeface.BOLD)
            }

            val orientationSpinner = Spinner(this).apply {
                val adapter = object : ArrayAdapter<String>(
                    this@MainActivity,
                    android.R.layout.simple_spinner_item,
                    SensorMountRotation.labels(),
                ) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val view = super.getView(position, convertView, parent) as TextView
                        view.text = SensorMountRotation.fromPosition(position).displayText()
                        view.textSize = 14f
                        view.setTextColor(COLOR_TEXT_MAIN)
                        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                        view.gravity = Gravity.CENTER_VERTICAL
                        view.setPadding(dp(12), dp(8), dp(12), dp(8))
                        return view
                    }

                    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val view = super.getDropDownView(position, convertView, parent) as TextView
                        view.text = SensorMountRotation.fromPosition(position).displayText()
                        view.textSize = 15f
                        view.setTextColor(Color.BLACK)
                        view.setPadding(dp(18), dp(12), dp(18), dp(12))
                        return view
                    }
                }.apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }

                this.adapter = adapter
                background = roundedDrawable(COLOR_SURFACE, dp(12), COLOR_ACCENT, 1)
                prompt = "センサーの角度"

                val savedRotation = sensorMountRotationFor(sensorId)
                setSelection(SensorMountRotation.positionOf(savedRotation), false)

                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        val rotation = SensorMountRotation.fromPosition(position)
                        saveSensorMountRotation(sensorId, rotation)
                        sendSensorMountRotationToService(sensorId, rotation)

                        if (trackerViews.containsKey(sensorId)) {
                            setStatus("Tracker #$sensorId センサーの角度: ${rotation.displayText()}", StatusLevel.OK)
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {
                    }
                }
            }

            trackerOrientationSpinners[sensorId] = orientationSpinner

            orientationRow.addView(
                orientationLabel,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )

            orientationRow.addView(
                orientationSpinner,
                LinearLayout.LayoutParams(
                    dp(130),
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )

            val quat = TextView(this).apply {
                text = "Quat: --"
                textSize = 13f
                setTextColor(COLOR_TEXT_MAIN)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                background = roundedDrawable(COLOR_SURFACE_DARK, dp(14), COLOR_STROKE, 1)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }

            val accel = TextView(this).apply {
                text = "Accel: --"
                textSize = 12f
                setTextColor(COLOR_TEXT_MUTED)
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                setPadding(0, dp(10), 0, 0)
            }

            val packet = TextView(this).apply {
                text = "Packets: --"
                textSize = 12f
                setTextColor(COLOR_ACCENT)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(0, dp(4), 0, 0)
            }

            val status = TextView(this).apply {
                text = "HID Status: --"
                textSize = 12f
                setTextColor(COLOR_TEXT_MUTED)
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                setPadding(0, dp(4), 0, 0)
            }

            cardRoot.addView(headerRow)
            cardRoot.addView(meta)
            cardRoot.addView(orientationRow)
            cardRoot.addView(spacer(8))
            cardRoot.addView(quat)
            cardRoot.addView(accel)
            cardRoot.addView(packet)
            cardRoot.addView(status)

            trackerContainer.addView(cardRoot, cardLayoutParams())

            TrackerCard(
                root = cardRoot,
                title = title,
                badge = badge,
                meta = meta,
                orientationSpinner = orientationSpinner,
                quat = quat,
                accel = accel,
                packet = packet,
                status = status,
            )
        }
    }

    private fun sensorMountRotationFor(sensorId: Int): SensorMountRotation {
        val degrees = prefs.getInt(sensorMountRotationKey(sensorId), SensorMountRotation.DEG_0.degrees)
        return SensorMountRotation.fromDegrees(degrees)
    }

    private fun saveSensorMountRotation(sensorId: Int, rotation: SensorMountRotation) {
        prefs.edit()
            .putInt(sensorMountRotationKey(sensorId), rotation.degrees)
            .apply()
    }

    private fun sendSensorMountRotationToService(sensorId: Int, rotation: SensorMountRotation) {
        val intent = Intent(this, BridgeForegroundService::class.java).apply {
            action = BridgeForegroundService.ACTION_UPDATE_SENSOR_ORIENTATION
            putExtra(BridgeForegroundService.EXTRA_SENSOR_ID, sensorId.coerceIn(0, 255))
            putExtra(BridgeForegroundService.EXTRA_SENSOR_ORIENTATION_DEGREES, rotation.degrees)
        }

        startService(intent)
    }

    private fun sensorMountRotationKey(sensorId: Int): String {
        return "$KEY_SENSOR_MOUNT_ROTATION_PREFIX${sensorId.coerceIn(0, 255)}"
    }

    private fun setStatus(message: String, level: StatusLevel) {
        if (!::statusTextView.isInitialized) return

        val accent = when (level) {
            StatusLevel.OK -> COLOR_OK
            StatusLevel.WARNING -> COLOR_WARNING
            StatusLevel.ERROR -> COLOR_ERROR
            StatusLevel.IDLE -> COLOR_ACCENT
        }

        val prefix = when (level) {
            StatusLevel.OK -> "ONLINE"
            StatusLevel.WARNING -> "WAIT"
            StatusLevel.ERROR -> "ERROR"
            StatusLevel.IDLE -> "IDLE"
        }

        statusTextView.text = "[$prefix] $message"
        statusTextView.setTextColor(COLOR_TEXT_MAIN)
        statusTextView.background = roundedDrawable(COLOR_SURFACE_DARK, dp(16), accent, 1)
    }

    private fun statusLevelFromString(value: String?): StatusLevel {
        return when (value) {
            "OK" -> StatusLevel.OK
            "WARNING" -> StatusLevel.WARNING
            "ERROR" -> StatusLevel.ERROR
            else -> StatusLevel.IDLE
        }
    }

    private fun sectionHeader(english: String, japanese: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        row.addView(TextView(this).apply {
            text = english
            textSize = 16f
            setTextColor(COLOR_TEXT_MAIN)
            setTypeface(typeface, Typeface.BOLD)
        })

        row.addView(TextView(this).apply {
            text = japanese
            textSize = 11f
            setTextColor(COLOR_ACCENT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(8))
        })

        return row
    }

    private fun inputLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(COLOR_TEXT_MUTED)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        }
    }

    private fun EditText.singleLineSet() {
        isSingleLine = true
        textSize = 16f
        setTextColor(COLOR_TEXT_MAIN)
        setHintTextColor(COLOR_TEXT_MUTED)
        background = roundedDrawable(COLOR_SURFACE_DARK, dp(14), COLOR_STROKE, 1)
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }

    private fun actionButton(textValue: String, role: ButtonRole, action: () -> Unit): Button {
        return Button(this).apply {
            text = textValue
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            minHeight = dp(50)
            minimumHeight = dp(50)
            isAllCaps = false
            stateListAnimator = null
            setPadding(0, dp(10), 0, dp(10))

            background = when (role) {
                ButtonRole.START -> buttonStateDrawable(COLOR_ACCENT, COLOR_ACCENT_PRESSED, COLOR_ACCENT)
                ButtonRole.STOP -> buttonStateDrawable(COLOR_ERROR, COLOR_ERROR_DARK, COLOR_ERROR)
                ButtonRole.SCAN -> buttonStateDrawable(COLOR_SURFACE_DARK, COLOR_BUTTON_PRESSED, COLOR_STROKE)
            }

            setTextColor(
                when (role) {
                    ButtonRole.START -> Color.WHITE
                    ButtonRole.STOP -> Color.WHITE
                    ButtonRole.SCAN -> COLOR_TEXT_MAIN
                },
            )

            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.alpha = 0.55f
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        view.alpha = 1.0f
                    }
                }

                false
            }

            setOnClickListener { action() }
        }
    }

    private fun markBridgeStarting() {
        bridgeUiState = BridgeUiState.STARTING
        updateBridgeButtons(bridgeUiState)
    }

    private fun markBridgeRunning() {
        bridgeUiState = BridgeUiState.RUNNING
        updateBridgeButtons(bridgeUiState)
    }

    private fun markBridgeStopped() {
        bridgeUiState = BridgeUiState.STOPPED
        updateBridgeButtons(bridgeUiState)
    }

    private fun pulseScanButton() {
        if (!::scanButton.isInitialized) return

        scanButton.background = buttonStateDrawable(
            normalColor = COLOR_WARNING,
            pressedColor = COLOR_WARNING_DARK,
            strokeColor = COLOR_WARNING,
        )
        scanButton.setTextColor(COLOR_BACKGROUND)
        scanButton.text = "SCANNING"

        mainHandler.postDelayed({
            updateBridgeButtons(bridgeUiState)
        }, 450L)
    }

    private fun updateBridgeButtons(state: BridgeUiState) {
        if (!::startButton.isInitialized || !::stopButton.isInitialized || !::scanButton.isInitialized) {
            return
        }

        when (state) {
            BridgeUiState.STARTING -> {
                startButton.background = buttonStateDrawable(
                    normalColor = COLOR_OK,
                    pressedColor = COLOR_OK_DARK,
                    strokeColor = COLOR_OK,
                )
                startButton.setTextColor(COLOR_BACKGROUND)
                startButton.text = "STARTING"

                stopButton.background = buttonStateDrawable(
                    normalColor = COLOR_SURFACE_DARK,
                    pressedColor = COLOR_ERROR_DARK,
                    strokeColor = COLOR_ERROR,
                )
                stopButton.setTextColor(COLOR_ERROR)
                stopButton.text = "STOP"

                scanButton.background = buttonStateDrawable(
                    normalColor = COLOR_SURFACE_DARK,
                    pressedColor = COLOR_BUTTON_PRESSED,
                    strokeColor = COLOR_STROKE,
                )
                scanButton.setTextColor(COLOR_TEXT_MAIN)
                scanButton.text = "SCAN"
            }

            BridgeUiState.RUNNING -> {
                startButton.background = buttonStateDrawable(
                    normalColor = COLOR_OK,
                    pressedColor = COLOR_OK_DARK,
                    strokeColor = COLOR_OK,
                )
                startButton.setTextColor(COLOR_BACKGROUND)
                startButton.text = "RUNNING"

                stopButton.background = buttonStateDrawable(
                    normalColor = COLOR_SURFACE_DARK,
                    pressedColor = COLOR_ERROR_DARK,
                    strokeColor = COLOR_ERROR,
                )
                stopButton.setTextColor(COLOR_ERROR)
                stopButton.text = "STOP"

                scanButton.background = buttonStateDrawable(
                    normalColor = COLOR_SURFACE_DARK,
                    pressedColor = COLOR_BUTTON_PRESSED,
                    strokeColor = COLOR_STROKE,
                )
                scanButton.setTextColor(COLOR_TEXT_MAIN)
                scanButton.text = "SCAN"
            }

            BridgeUiState.STOPPED -> {
                startButton.background = buttonStateDrawable(
                    normalColor = COLOR_ACCENT,
                    pressedColor = COLOR_ACCENT_PRESSED,
                    strokeColor = COLOR_ACCENT,
                )
                startButton.setTextColor(Color.WHITE)
                startButton.text = "START"

                stopButton.background = buttonStateDrawable(
                    normalColor = COLOR_ERROR,
                    pressedColor = COLOR_ERROR_DARK,
                    strokeColor = COLOR_ERROR,
                )
                stopButton.setTextColor(Color.WHITE)
                stopButton.text = "STOPPED"

                scanButton.background = buttonStateDrawable(
                    normalColor = COLOR_SURFACE_DARK,
                    pressedColor = COLOR_BUTTON_PRESSED,
                    strokeColor = COLOR_STROKE,
                )
                scanButton.setTextColor(COLOR_TEXT_MAIN)
                scanButton.text = "SCAN"
            }
        }
    }

    private fun buttonStateDrawable(
        normalColor: Int,
        pressedColor: Int,
        strokeColor: Int,
    ): StateListDrawable {
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundedDrawable(pressedColor, dp(14), COLOR_TEXT_MAIN, 2),
            )

            addState(
                intArrayOf(android.R.attr.state_focused),
                roundedDrawable(pressedColor, dp(14), COLOR_TEXT_MAIN, 2),
            )

            addState(
                intArrayOf(),
                roundedDrawable(normalColor, dp(14), strokeColor, 1),
            )
        }
    }

    private fun roundedDrawable(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(dp(strokeWidth), strokeColor)
        }
    }

    private fun spacer(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(heightDp),
            )
        }
    }

    private fun matchWrap(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun cardLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(14)
        }
    }

    private fun weightedButton(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(8)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }

        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun f(value: Float): String {
        return String.format(Locale.US, "%.4f", value)
    }

    private fun nowTime(): String {
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    }

    private data class EndpointSelection(
        val usbInterface: UsbInterface,
        val inputEndpoint: UsbEndpoint,
    )

    private data class TrackerCard(
        val root: LinearLayout,
        val title: TextView,
        val badge: TextView,
        val meta: TextView,
        val orientationSpinner: Spinner,
        val quat: TextView,
        val accel: TextView,
        val packet: TextView,
        val status: TextView,
        var hasQuaternion: Boolean = false,
    )

    private enum class StatusLevel {
        OK,
        WARNING,
        ERROR,
        IDLE,
    }

    private enum class ButtonRole {
        START,
        STOP,
        SCAN,
    }

    private enum class BridgeUiState {
        STARTING,
        RUNNING,
        STOPPED,
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.kirisamenanoha.hidslimebridge.USB_PERMISSION"

        private const val KEY_IP = "ip"
        private const val KEY_PORT = "port"
        private const val KEY_SENSOR_MOUNT_ROTATION_PREFIX = "sensor_mount_rotation_"

        private const val SLIME_HID_VID = 0x1209
        private const val SLIME_HID_RECEIVER_PID = 0x7690
        private const val SLIME_HID_TRACKER_PID = 0x7692

        private const val MAX_USB_DEVICE_COUNT = 32
        private const val REQUEST_POST_NOTIFICATIONS = 100

        private const val COLOR_BACKGROUND = 0xFF0B1020.toInt()
        private const val COLOR_SURFACE = 0xFF121A2F.toInt()
        private const val COLOR_SURFACE_DARK = 0xFF0D1426.toInt()
        private const val COLOR_STROKE = 0xFF263653.toInt()

        private const val COLOR_TEXT_MAIN = 0xFFEAF0FF.toInt()
        private const val COLOR_TEXT_MUTED = 0xFF8FA3C7.toInt()

        private const val COLOR_ACCENT = 0xFF6EA8FE.toInt()
        private const val COLOR_ACCENT_PRESSED = 0xFF3F7FEA.toInt()
        private const val COLOR_BUTTON_PRESSED = 0xFF223555.toInt()

        private const val COLOR_OK = 0xFF4DFFB5.toInt()
        private const val COLOR_OK_DARK = 0xFF143C31.toInt()

        private const val COLOR_WARNING = 0xFFFFC857.toInt()
        private const val COLOR_WARNING_DARK = 0xFF8A5A00.toInt()

        private const val COLOR_ERROR = 0xFFFF5C7A.toInt()
        private const val COLOR_ERROR_DARK = 0xFF5A1724.toInt()
    }
}

private fun <T : Parcelable> Intent.parcelableExtraCompat(name: String, clazz: Class<T>): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, clazz)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
}