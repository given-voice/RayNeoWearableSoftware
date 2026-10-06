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
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect()
                    else Log.w(TAG, "USB permission denied for switch box")
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> connect()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> disconnect()
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
        connect()
    }

    fun stop() {
        context.unregisterReceiver(usbReceiver)
        disconnect()
    }

    private fun connect() {
        if (port != null) return
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull() ?: return
        val device = driver.device
        if (!usbManager.hasPermission(device)) {
            // The system adds EXTRA_PERMISSION_GRANTED to this intent, so it must be mutable.
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
            usbManager.requestPermission(device, pi)
            return
        }
        val connection = usbManager.openDevice(device) ?: return
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
        Log.i(TAG, "Switch box connected: ${driver.javaClass.simpleName}")
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
