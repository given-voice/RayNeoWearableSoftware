package com.givenvoice.wearable

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException

/**
 * Reads lines from the ESP32 switch box over a USB-C OTG cable
 * (115200 baud, 8N1) and hands each complete line to [onLine] on the main
 * thread. Connects on [start], when the box is plugged in, and after the
 * user grants USB permission; disconnects on [stop] or when unplugged.
 */
class UsbSerialSwitchSource(
    private val context: Context,
    private val onLine: (String) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {
    // Lazy: the source is created as an Activity field, before its context is ready.
    private val usbManager by lazy { context.getSystemService(UsbManager::class.java) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = StringBuilder()
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION ->
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.i(TAG, "USB permission granted"); onStatus("USB: permission granted")
                        connect()
                    } else {
                        Log.w(TAG, "USB permission denied for switch box")
                        onStatus("USB: permission denied")
                    }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB device attached"); onStatus("USB: device attached")
                    connect()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB device detached")
                    disconnect()
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        logVisibleDevices()
        connect()
    }

    /** Lists every USB device the glasses can see. "none" means the ESP32 is not detected at all
     * (no power, no host mode, or a bad cable/hub), as opposed to a problem inside the app. */
    private fun logVisibleDevices() {
        val devices = usbManager.deviceList.values.joinToString("; ") {
            "${it.deviceName} vid=${it.vendorId} pid=${it.productId}"
        }.ifEmpty { "none" }
        Log.i(TAG, "USB devices visible: $devices")
        onStatus("USB: " + (if (devices == "none") "no device seen" else devices))
    }

    fun stop() {
        context.unregisterReceiver(usbReceiver)
        disconnect()
    }

    private fun connect() {
        if (port != null) return
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
        if (driver == null) {
            Log.i(TAG, "No USB serial device found (visible devices: ${usbManager.deviceList.size})")
            return
        }
        val device = driver.device
        Log.i(TAG, "Found ${driver.javaClass.simpleName} vid=${device.vendorId} pid=${device.productId}")
        if (!usbManager.hasPermission(device)) {
            Log.i(TAG, "Requesting USB permission"); onStatus("USB: requesting permission")
            // The system adds EXTRA_PERMISSION_GRANTED to this intent, so it must be mutable.
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
            usbManager.requestPermission(device, pi)
            return
        }
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.w(TAG, "openDevice returned null for the switch box"); onStatus("USB: open failed")
            return
        }
        val serialPort = driver.ports[0]
        try {
            serialPort.open(connection)
            serialPort.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: IOException) {
            Log.e(TAG, "Could not open switch box serial port", e)
            runCatching { serialPort.close() }
            return
        }
        port = serialPort
        ioManager = SerialInputOutputManager(serialPort, listener).also { it.start() }
        Log.i(TAG, "Switch box connected: ${driver.javaClass.simpleName}"); onStatus("USB: switch box connected")
    }

    private fun disconnect() {
        ioManager?.stop()
        ioManager = null
        runCatching { port?.close() }
        if (port != null) Log.i(TAG, "Switch box disconnected")
        port = null
        pending.clear()
    }

    private val listener = object : SerialInputOutputManager.Listener {
        // Called on the I/O thread; bytes can arrive split across calls.
        override fun onNewData(data: ByteArray) {
            mainHandler.post {
                pending.append(String(data, Charsets.US_ASCII))
                var newline = pending.indexOf("\n")
                while (newline >= 0) {
                    onLine(pending.substring(0, newline))
                    pending.delete(0, newline + 1)
                    newline = pending.indexOf("\n")
                }
            }
        }

        override fun onRunError(e: Exception) {
            Log.w(TAG, "Switch box serial stopped", e)
            mainHandler.post { disconnect() }
        }
    }

    companion object {
        private const val TAG = "GivenVoice"
        private const val BAUD_RATE = 115200
        private const val ACTION_USB_PERMISSION = "com.givenvoice.wearable.USB_PERMISSION"
    }
}
