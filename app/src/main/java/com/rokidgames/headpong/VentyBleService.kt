package com.rokidgames.headpong

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * BLE service for Storz & Bickel Venty vaporizer.
 *
 * Protocol reverse-engineered from reactive-volcano-app.
 * Venty uses a single service with one control characteristic
 * for both commands (Write Without Response) and state notifications.
 *
 * Device name prefix: "S&B VY" (Venty), "S&B VZ" (Veazy).
 */
class VentyBleService(private val ctx: Context) {

    // ---- Connection state ----
    enum class ConnState { IDLE, SCANNING, CONNECTING, DISCOVERING, SUBSCRIBING, CONNECTED, DISCONNECTED }

    private val _connState = MutableStateFlow(ConnState.IDLE)
    val connState: StateFlow<ConnState> = _connState.asStateFlow()

    private val _ventyState = MutableStateFlow(VentyState())
    val ventyState: StateFlow<VentyState> = _ventyState.asStateFlow()

    private val _deviceInfo = MutableStateFlow(VentyDeviceInfo())
    val deviceInfo: StateFlow<VentyDeviceInfo> = _deviceInfo.asStateFlow()

    // ---- Session timer ----
    private var sessionStartMs = 0L
    val sessionSeconds: Long get() =
        if (sessionStartMs == 0L) 0L else (System.currentTimeMillis() - sessionStartMs) / 1000L

    fun startSession() { sessionStartMs = System.currentTimeMillis() }
    fun stopSession()  { sessionStartMs = 0L }

    /** True after first state notification (CMD 0x01) received. */
    var stateReceived = false
        private set

    // ---- BLE internals ----
    private val adapter: BluetoothAdapter?
        get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var controlChar: BluetoothGattCharacteristic? = null
    private var scanCallback: ScanCallback? = null

    // ---- UUIDs ----
    companion object {
        // Venty service + characteristic
        private val SERVICE_PRIMARY = UUID.fromString("00000000-5354-4f52-5a26-4249434b454c")
        private val CHAR_CONTROL    = UUID.fromString("00000001-5354-4f52-5a26-4249434b454c")
        private val CCC_UUID        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Device name prefixes from reactive-volcano-app */
        private val SCAN_PREFIXES = listOf("S&B VY", "S&B VZ", "S&B", "STORZ&BICKEL")

        // Write masks for CMD 0x01
        const val MASK_TEMPERATURE = 0x02   // 1<<1
        const val MASK_BOOST       = 0x04   // 1<<2
        const val MASK_SUPERBOOST  = 0x08   // 1<<3
        const val MASK_AUTOOFF     = 0x10   // 1<<4
        const val MASK_HEATER      = 0x20   // 1<<5
        const val MASK_SETTINGS    = 0x80   // 1<<7
    }

    // ====================================================================
    // Public API
    // ====================================================================

    fun connect() {
        try {
            val bt = adapter ?: run { _connState.value = ConnState.DISCONNECTED; return }
            if (!bt.isEnabled) { _connState.value = ConnState.DISCONNECTED; return }

            scanner = bt.bluetoothLeScanner ?: run { _connState.value = ConnState.DISCONNECTED; return }
            _connState.value = ConnState.SCANNING

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = result.device.name ?: return
                    if (SCAN_PREFIXES.any { name.uppercase().startsWith(it) }) {
                        stopScan()
                        _connState.value = ConnState.CONNECTING
                        connectToDevice(result.device)
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    _connState.value = ConnState.DISCONNECTED
                }
            }
            scanCallback = cb
            scanner!!.startScan(null, settings, cb)
        } catch (_: SecurityException) {
            _connState.value = ConnState.DISCONNECTED
        }
    }

    fun disconnect() {
        stopScan()
        stopSession()
        stateReceived = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        controlChar = null
        _connState.value = ConnState.IDLE
    }

    /** Set target temperature (absolute, in °C). Mask 0x02, bytes 4–5 = u16 LE × 10. */
    fun setTargetTemp(celsius: Float) {
        val ch = controlChar ?: return
        val raw = (celsius * 10f).toInt().coerceIn(400, 2300)
        val buf = ByteArray(20)
        buf[0] = 0x01
        buf[1] = MASK_TEMPERATURE.toByte()
        buf[4] = (raw and 0xFF).toByte()
        buf[5] = ((raw shr 8) and 0xFF).toByte()
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = buf
        gatt?.writeCharacteristic(ch)
    }

    /** Adjust target temperature by delta °C. */
    fun adjustTemp(delta: Int) {
        val cur = _ventyState.value.targetTempC
        if (cur > 0f) setTargetTemp(cur + delta)
    }

    /** Request current state — sends 0x01 with a dummy mask bit to trigger notification. */
    private fun requestState() {
        val ch = controlChar ?: return
        val buf = ByteArray(20)
        buf[0] = 0x01
        buf[1] = 0x02  // MASK_TEMPERATURE — dummy mask to trigger response
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = buf
        gatt?.writeCharacteristic(ch)
    }

    /** Toggle heater: off → on, on → off, boost → off etc. */
    fun toggleHeater() {
        val ch = controlChar ?: return
        val mode = _ventyState.value.heaterMode
        val newMode = if (mode == 0) 1 else 0  // off→on, anything else→off
        val buf = ByteArray(20)
        buf[0] = 0x01
        buf[1] = MASK_HEATER.toByte()
        buf[11] = newMode.toByte()
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = buf
        gatt?.writeCharacteristic(ch)
    }

    // ====================================================================
    // Internal
    // ====================================================================

    private fun stopScan() {
        scanCallback?.let { try { scanner?.stopScan(it) } catch (_: Throwable) {} }
        scanCallback = null
    }

    private fun connectToDevice(device: BluetoothDevice) {
        gatt = device.connectGatt(ctx, false, gattCallback)
    }

    // ====================================================================
    // GATT Callback
    // ====================================================================

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connState.value = ConnState.DISCONNECTED
                gatt.close()
                this@VentyBleService.gatt = null
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connState.value = ConnState.DISCOVERING
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connState.value = ConnState.DISCONNECTED
                    gatt.close()
                    this@VentyBleService.gatt = null
                    controlChar = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connState.value = ConnState.DISCONNECTED
                return
            }

            val service = gatt.getService(SERVICE_PRIMARY)
            if (service == null) {
                _connState.value = ConnState.DISCONNECTED
                return
            }

            val ch = service.getCharacteristic(CHAR_CONTROL)
            if (ch == null) {
                _connState.value = ConnState.DISCONNECTED
                return
            }

            controlChar = ch
            _connState.value = ConnState.SUBSCRIBING

            // Enable notifications on the control characteristic
            gatt.setCharacteristicNotification(ch, true)
            val desc = ch.getDescriptor(CCC_UUID)
            if (desc != null) {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            } else {
                // Some devices may not expose CCC descriptor — try init directly
                sendInitSequence(gatt)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS &&
                desc.characteristic.uuid == CHAR_CONTROL &&
                java.util.Arrays.equals(desc.value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                sendInitSequence(gatt)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (ch.uuid == CHAR_CONTROL) {
                ch.value?.let { parseNotification(it) }
            }
        }
    }

    // ====================================================================
    // Init sequence — matches reactive-volcano-app exactly
    // ====================================================================

    private fun sendInitSequence(gatt: BluetoothGatt) {
        val ch = controlChar ?: return
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

        // Sequential writes via a simple queue (posted to GATT handler)
        val commands = byteArrayOf(0x02, 0x1D.toByte(), 0x01, 0x04)
        var idx = 0

        fun writeNext() {
            if (idx >= commands.size) {
                _connState.value = ConnState.CONNECTED
                // Poll for state after connect — Venty only responds to masked writes
                android.os.Handler(ctx.mainLooper).postDelayed({
                    requestState()
                }, 200)
                return
            }
            val buf = ByteArray(20)
            buf[0] = commands[idx]
            ch.value = buf
            idx++
            android.os.Handler(ctx.mainLooper).postDelayed({ writeNext() }, 100)
        }
        writeNext()
    }

    // ====================================================================
    // Protocol parser
    // ====================================================================

    private fun parseNotification(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0].toInt() and 0xFF) {
            0x01 -> parseState(data)
            0x02 -> parseFirmware(data)
            0x04 -> parseExtended(data)
            0x05 -> parseDeviceId(data)
            0x06 -> parseSettings(data)
        }
    }

    private fun parseState(data: ByteArray) {
        if (data.size < 15) return
        stateReceived = true
        val targetRaw  = ((data[5].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val boostOff   = data[6].toInt() and 0xFF
        val sboostOff  = data[7].toInt() and 0xFF
        val battery    = data[8].toInt() and 0xFF
        val autoOff    = ((data[10].toInt() and 0xFF) shl 8) or (data[9].toInt() and 0xFF)
        val heaterMode = data[11].toInt() and 0xFF
        val charging   = (data[13].toInt() and 0xFF) != 0
        val settings   = data[14].toInt() and 0xFF
        val permBle    = if (data.size >= 17) (data[16].toInt() and 0x01) != 0 else false

        // Session timer: start on heat, reset on standby
        val prevMode = _ventyState.value.heaterMode
        if (heaterMode > 0 && prevMode == 0) startSession()
        else if (heaterMode == 0) stopSession()

        _ventyState.value = VentyState(
            targetTempC       = targetRaw / 10.0f,
            boostOffsetC      = boostOff,
            superboostOffsetC = sboostOff,
            batteryPercent    = battery,
            autoOffSeconds    = autoOff,
            heaterMode        = heaterMode,
            isCharging        = charging,
            isCelsius         = (settings and 0x01) == 0,
            setpointReached   = (settings and 0x02) != 0,
            chargeOpt         = (settings and 0x08) != 0,
            voltageLimit      = (settings and 0x20) != 0,
            boostVisual       = (settings and 0x40) != 0,
            permanentBle      = permBle
        )
    }

    private fun parseFirmware(data: ByteArray) {
        if (data.size < 9) return
        _deviceInfo.value = _deviceInfo.value.copy(
            firmware   = "${data[1].toInt() and 0xFF}.${data[2].toInt() and 0xFF}.${data[3].toInt() and 0xFF}.${data[4].toInt() and 0xFF}",
            bootloader = "${data[5].toInt() and 0xFF}.${data[6].toInt() and 0xFF}.${data[7].toInt() and 0xFF}.${data[8].toInt() and 0xFF}"
        )
    }

    private fun parseExtended(data: ByteArray) {
        if (data.size < 7) return
        val heatMin = (data[1].toInt() and 0xFF) or
                      ((data[2].toInt() and 0xFF) shl 8) or
                      ((data[3].toInt() and 0xFF) shl 16)
        val chargeMin = (data[4].toInt() and 0xFF) or
                        ((data[5].toInt() and 0xFF) shl 8) or
                        ((data[6].toInt() and 0xFF) shl 16)
        _deviceInfo.value = _deviceInfo.value.copy(
            heaterRuntimeMin = heatMin,
            chargingTimeMin  = chargeMin
        )
    }

    private fun parseDeviceId(data: ByteArray) {
        if (data.size < 19) return
        val namePart   = String(data, 9, 6).trimEnd(' ')
        val prefixPart = String(data, 15, 2).trimEnd(' ')
        _deviceInfo.value = _deviceInfo.value.copy(
            serial     = "$prefixPart$namePart",
            colorIndex = data[18].toInt() and 0xFF
        )
    }

    private fun parseSettings(data: ByteArray) {
        if (data.size < 7) return
        val s = _ventyState.value
        _ventyState.value = s.copy(
            boostVisual = (data[5].toInt() and 0xFF) == 1
        )
    }
}

// ====================================================================
// Data classes
// ====================================================================

data class VentyState(
    val targetTempC:       Float   = 0f,
    val boostOffsetC:      Int     = 0,
    val superboostOffsetC: Int     = 0,
    val batteryPercent:    Int     = 0,
    val autoOffSeconds:    Int     = 0,
    val heaterMode:        Int     = 0,      // 0=off, 1=on, 2=boost, 3=superboost
    val isCharging:        Boolean = false,
    val isCelsius:         Boolean = true,
    val setpointReached:   Boolean = false,
    val chargeOpt:         Boolean = false,
    val voltageLimit:      Boolean = false,
    val boostVisual:       Boolean = false,
    val permanentBle:      Boolean = false
) {
    val heaterLabel: String get() = when (heaterMode) {
        0 -> "OFF"
        1 -> "ON"
        2 -> "BOOST"
        3 -> "S-BOOST"
        else -> "?"
    }
    val tempDisplay: String get() = if (isCelsius) "%.0f°C".format(targetTempC)
        else "%.0f°F".format(targetTempC * 9f / 5f + 32f)
    val effectiveTemp: Float get() = when (heaterMode) {
        2 -> targetTempC + boostOffsetC
        3 -> targetTempC + superboostOffsetC
        else -> targetTempC
    }
}

data class VentyDeviceInfo(
    val firmware:          String = "?.?.?.?",
    val bootloader:        String = "?.?.?.?",
    val serial:            String = "",
    val colorIndex:        Int = 0,
    val heaterRuntimeMin:  Int = 0,
    val chargingTimeMin:   Int = 0
) {
    val heaterRuntimeHours: Float get() = heaterRuntimeMin / 60f
}
