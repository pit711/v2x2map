package org.opentrafficmap.receiver

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * BLE counterpart of [UsbSerialController].
 *
 * Robustness improvements over the original:
 *  - Caches the BluetoothDevice after first scan; reconnects skip the scan.
 *  - Requests CONNECTION_PRIORITY_HIGH immediately after the GATT link comes
 *    up, reducing the connection interval from ~40 ms to ~7.5 ms so the CCCD
 *    write lands inside the first BLE window the C5 opens.
 *  - Caches the negotiated MTU; reconnects skip MTU renegotiation.
 *  - CCCD watchdog: if onDescriptorWrite does not fire within CCCD_TIMEOUT_MS
 *    after service discovery, we forcefully disconnect and let backoff retry.
 *  - Exponential backoff with jitter: 1 → 2 → 4 → 8 → 30 s (repeating).
 *  - Retry counter shown in the status string so the user can see progress.
 */
class BluetoothController(
    private val context: Context,
    private val onBytes: (ByteArray) -> Unit,
    private val onState: (State, String?) -> Unit,
    private val onConfig: ((CycleConfig) -> Unit)? = null,
) {
    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, ERROR }

    data class CycleConfig(
        val discSniffMs: Int,
        val discBleMs:   Int,
        val connSniffMs: Int,
        val connBleMs:   Int,
    )

    private val tag = "BluetoothController"
    private val mainHandler = Handler(Looper.getMainLooper())

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = btManager.adapter

    private var gatt: BluetoothGatt? = null
    @Volatile private var scanning   = false
    @Volatile private var stopped    = false

    // ── persistence across reconnects ──────────────────────────────────────
    @Volatile private var cachedDevice: BluetoothDevice? = null
    @Volatile private var cachedMtu: Int = 0

    // ── retry / watchdog state ─────────────────────────────────────────────
    private var retryCount  = 0
    private val cccdWatchdog: Runnable = Runnable {
        if (!stopped) {
            Log.w(tag, "CCCD watchdog fired – forcing reconnect")
            onState(State.SCANNING, reconnectLabel())
            try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
            gatt = null
            scheduleReconnect()
        }
    }

    // ── public API ─────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun start() {
        stopped    = false
        retryCount = 0
        connectOrScan()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        stopped = true
        cancelWatchdog()
        stopScan()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        onState(State.IDLE, null)
    }

    // ── internal connect logic ─────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun connectOrScan() {
        if (adapter == null || !adapter.isEnabled) {
            onState(State.ERROR, "Bluetooth nicht verfügbar"); return
        }
        if (!hasScanPermission()) {
            onState(State.ERROR, "BLE-Berechtigung fehlt"); return
        }
        val dev = cachedDevice
        if (dev != null) {
            onState(State.CONNECTING, reconnectLabel())
            doConnect(dev)
        } else {
            startScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun doConnect(dev: BluetoothDevice) {
        try {
            gatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            onState(State.ERROR, "Connect verweigert: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            onState(State.ERROR, "BLE-Scanner nicht verfügbar"); return
        }
        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settingsBuilder
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_FEW_ADVERTISEMENT)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        }
        scanning = true
        onState(State.SCANNING, "Suche $DEVICE_NAME…")
        try {
            scanner.startScan(null, settingsBuilder.build(), scanCallback)
        } catch (e: SecurityException) {
            onState(State.ERROR, "Scan verweigert"); scanning = false; return
        }
        mainHandler.postDelayed({
            if (scanning) { stopScan(); onState(State.ERROR, "$DEVICE_NAME nicht gefunden") }
        }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    private fun scheduleReconnect() {
        val backoffMs = backoffDelay(retryCount)
        retryCount++
        mainHandler.postDelayed({ if (!stopped) connectOrScan() }, backoffMs)
    }

    private fun cancelWatchdog() = mainHandler.removeCallbacks(cccdWatchdog)

    private fun reconnectLabel(): String =
        if (retryCount == 0) "Verbinde…"
        else "Reconnect #$retryCount (${backoffDelay(retryCount) / 1000} s)…"

    // ── scan callback ──────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val record = result.scanRecord
            val name   = result.device.name ?: record?.deviceName
            val uuids  = record?.serviceUuids?.map { it.uuid } ?: emptyList()
            if (name != DEVICE_NAME && SERVICE_UUID !in uuids) return
            stopScan()
            cachedDevice = result.device
            onState(State.CONNECTING, "Gefunden: ${name ?: result.device.address}")
            doConnect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onState(State.ERROR, "Scan-Fehler $errorCode")
        }
    }

    // ── GATT callbacks ─────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(tag, "GATT connected (status=$status)")
                // ① Reduce connection interval immediately so GATT ops fit in
                //    the first BLE window the C5 opens after its Wi-Fi slice.
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                // ② Skip MTU renegotiation if we already know the value.
                if (cachedMtu > 0) {
                    Log.i(tag, "using cached MTU=$cachedMtu, skipping negotiation")
                    g.discoverServices()
                } else {
                    g.requestMtu(517)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(tag, "GATT disconnected status=$status")
                cancelWatchdog()
                try { g.close() } catch (_: Exception) {}
                gatt = null
                if (stopped) {
                    onState(State.IDLE, null)
                } else {
                    onState(State.SCANNING, reconnectLabel())
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(tag, "MTU=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) cachedMtu = mtu
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onState(State.ERROR, "Service-Discovery fehlgeschlagen: $status"); return
            }
            val chr = g.getService(SERVICE_UUID)
                ?.getCharacteristic(NOTIFY_CHR_UUID) ?: run {
                onState(State.ERROR, "Notify-Characteristic fehlt"); return
            }
            if (!g.setCharacteristicNotification(chr, true)) {
                onState(State.ERROR, "setCharacteristicNotification fehlgeschlagen"); return
            }
            val cccd = chr.getDescriptor(CCCD_UUID) ?: run {
                onState(State.ERROR, "CCCD fehlt"); return
            }
            // ③ Watchdog: if CCCD write doesn't complete before the C5 supervision
            //    timeout, force disconnect so retry can start fresh.
            cancelWatchdog()
            mainHandler.postDelayed(cccdWatchdog, CCCD_TIMEOUT_MS)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            cancelWatchdog()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                retryCount = 0   // reset backoff on clean connect
                onState(State.CONNECTED, g.device.name ?: g.device.address)
                val cfg = g.getService(SERVICE_UUID)?.getCharacteristic(CONFIG_CHR_UUID)
                if (cfg != null) g.readCharacteristic(cfg)
            } else {
                Log.w(tag, "CCCD write status=$status – retrying")
                onState(State.SCANNING, reconnectLabel())
                try { g.disconnect(); g.close() } catch (_: Exception) {}
                gatt = null
                scheduleReconnect()
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CONFIG_CHR_UUID)
                characteristic.value?.let { decodeConfig(it)?.let { c -> onConfig?.invoke(c) } }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CONFIG_CHR_UUID)
                decodeConfig(value)?.let { onConfig?.invoke(it) }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS && characteristic.uuid == CONFIG_CHR_UUID)
                Log.w(tag, "config write failed: status=$status")
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == NOTIFY_CHR_UUID)
                characteristic.value?.let { onBytes(it) }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == NOTIFY_CHR_UUID) onBytes(value)
        }
    }

    // ── config write ───────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun writeConfig(c: CycleConfig): Boolean {
        val g   = gatt ?: return false
        val chr = g.getService(SERVICE_UUID)?.getCharacteristic(CONFIG_CHR_UUID) ?: return false
        val buf = ByteArray(8)
        fun pack(off: Int, v: Int) {
            val cl = v.coerceIn(100, 60000)
            buf[off]     = (cl and 0xff).toByte()
            buf[off + 1] = ((cl shr 8) and 0xff).toByte()
        }
        pack(0, c.discSniffMs); pack(2, c.discBleMs)
        pack(4, c.connSniffMs); pack(6, c.connBleMs)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(chr, buf, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION") run {
                    chr.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    chr.value = buf
                    g.writeCharacteristic(chr)
                }
            }
        } catch (_: SecurityException) { false }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun decodeConfig(bytes: ByteArray): CycleConfig? {
        if (bytes.size != 8) return null
        fun u16(o: Int) = (bytes[o].toInt() and 0xff) or ((bytes[o+1].toInt() and 0xff) shl 8)
        return CycleConfig(u16(0), u16(2), u16(4), u16(6))
    }

    /** Exponential backoff: 1 s → 2 s → 4 s → 8 s → 30 s (stays there). */
    private fun backoffDelay(attempt: Int): Long {
        val base = minOf(1L shl attempt.coerceAtMost(4), 30L) * 1000L
        val jitter = (Math.random() * 500).toLong()
        return base + jitter
    }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    companion object {
        const val DEVICE_NAME      = "ITS-G5-RX"
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val CCCD_TIMEOUT_MS = 6_000L   // must fire before C5's 5 s supervision timeout

        val SERVICE_UUID:    UUID = UUID.fromString("b6e57e90-12d8-4a47-9b21-3f0000000001")
        val NOTIFY_CHR_UUID: UUID = UUID.fromString("b6e57e90-12d8-4a47-9b21-3f0000000002")
        val CONFIG_CHR_UUID: UUID = UUID.fromString("b6e57e90-12d8-4a47-9b21-3f0000000003")
        val CCCD_UUID:       UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun runtimePermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
