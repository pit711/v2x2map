package org.opentrafficmap.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

/**
 * Wraps `usb-serial-for-android` and surfaces a clean Kotlin API:
 *   start() — find an attached USB serial device, request permission, open it,
 *             start a background reader that calls `onBytes` for every chunk.
 *   stop()  — close everything.
 *
 * `onBytes` runs on a worker thread. The caller is responsible for marshalling
 * to the main thread (e.g. via Handler / coroutines) before touching the UI.
 */
class UsbSerialController(
    private val context: Context,
    private val onBytes: (ByteArray) -> Unit,
    private val onState: (State, String?) -> Unit,
) {
    enum class State { IDLE, REQUESTING, CONNECTED, ERROR }

    private val tag = "UsbSerialController"
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var deviceLabel: String? = null

    private val permissionAction = "${context.packageName}.USB_PERMISSION"
    private val pendingFlags =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device = intent.getParcelableExtraCompat(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (granted && device != null) {
                openDevice(device)
            } else {
                onState(State.ERROR, "USB permission denied")
            }
        }
    }

    fun start() {
        val devices = findDrivers()
        if (devices.isEmpty()) {
            onState(State.ERROR, "kein USB-Gerät gefunden – OTG-Kabel angeschlossen?")
            return
        }
        val driver = devices.first()
        val device = driver.device

        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permissionReceiver, filter)
        }

        if (usbManager.hasPermission(device)) {
            openDevice(device, driver)
        } else {
            onState(State.REQUESTING, "requesting USB permission…")
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(permissionAction).setPackage(context.packageName), pendingFlags
            )
            usbManager.requestPermission(device, pi)
        }
    }

    /**
     * Returns all detected serial drivers: default prober (FTDI, CP210x, CH34x, …) PLUS
     * Espressif USB-Serial-JTAG devices that are missing from the stock probe table.
     * ESP32-C3/C5/S3/C6/H2 all use VID=0x303A; PID varies:
     *   0x1001  USB JTAG/serial (C3, C5, S3, C6, H2)
     *   0x0002  ESP32-S2 USB CDC
     *   0x8001  USB CDC-only build
     */
    private fun findDrivers(): List<UsbSerialDriver> {
        val defaultHits = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (defaultHits.isNotEmpty()) return defaultHits
        val espressifTable = ProbeTable().apply {
            addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java)
            addProduct(0x303A, 0x0002, CdcAcmSerialDriver::class.java)
            addProduct(0x303A, 0x8001, CdcAcmSerialDriver::class.java)
        }
        return UsbSerialProber(espressifTable).findAllDrivers(usbManager)
    }

    private fun openDevice(device: UsbDevice, drv: UsbSerialDriver? = null) {
        try {
            val driver = drv
                ?: UsbSerialProber.getDefaultProber().probeDevice(device)
                ?: run {
                    onState(State.ERROR, "no matching driver for device")
                    return
                }
            val connection = usbManager.openDevice(driver.device)
                ?: run {
                    onState(State.ERROR, "could not open device")
                    return
                }
            val p = driver.ports.first()
            p.open(connection)
            // ESP32-C5 USB-Serial-JTAG ignores most of these but the driver insists.
            p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try {
                p.dtr = false
                p.rts = false
            } catch (e: Exception) {
                Log.w(tag, "DTR/RTS not supported, ignoring", e)
            }

            val mgr = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) { onBytes(data) }
                override fun onRunError(e: Exception) {
                    onState(State.ERROR, "read error: ${e.message ?: e.javaClass.simpleName}")
                    closeQuietly()
                }
            })
            executor.submit(mgr)
            port = p
            ioManager = mgr
            deviceLabel = "${driver.javaClass.simpleName} VID=%04X PID=%04X".format(device.vendorId, device.productId)
            onState(State.CONNECTED, deviceLabel)
        } catch (e: Exception) {
            onState(State.ERROR, e.message ?: e.javaClass.simpleName)
            closeQuietly()
        }
    }

    /** Pulse RTS to soft-reset the ESP32 (works on USB-Serial-JTAG). */
    fun resetDevice() {
        try {
            port?.let {
                it.dtr = false
                it.rts = true
                Thread.sleep(100)
                it.rts = false
            }
        } catch (e: Exception) {
            Log.w(tag, "reset failed", e)
        }
    }

    fun stop() {
        closeQuietly()
        try {
            context.unregisterReceiver(permissionReceiver)
        } catch (_: IllegalArgumentException) { /* not registered */ }
        onState(State.IDLE, null)
    }

    private fun closeQuietly() {
        try { ioManager?.stop() } catch (_: Exception) {}
        try { port?.close() } catch (_: Exception) {}
        ioManager = null
        port = null
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T> Intent.getParcelableExtraCompat(name: String, clazz: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(name, clazz)
    else getParcelableExtra(name)
