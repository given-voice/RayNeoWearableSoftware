package com.givenvoice.wearable

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Listens for the ESP32 switch box over Bluetooth LE advertising
 * (hardware/switch_ble/switch_ble.ino). There is no pairing and no connection:
 * the box puts its last press in manufacturer data (company id 0xFFFF) as
 * 'G' 'V' button seq_hi seq_lo, and this scanner reads it straight from the
 * advertisement.
 *
 * The same advert is heard many times, so a press is acted on only when its
 * seq changes. The first seq heard after the scan starts is only remembered,
 * so an old press is never replayed when the app opens.
 */
class BleSwitchSource(
    private val context: Context,
    private val onButton: (SwitchButton) -> Unit,
    private val onStatus: (String) -> Unit = {},
) {
    private var scanning = false
    private var lastSeq = -1

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(COMPANY_ID) ?: return
            if (data.size < 5 || data[0] != 'G'.code.toByte() || data[1] != 'V'.code.toByte()) return
            val button = data[2].toInt()
            val seq = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
            if (lastSeq == -1) {
                lastSeq = seq
                Log.i(TAG, "Switch box heard over Bluetooth (rssi ${result.rssi})")
                onStatus("Bluetooth: switch box found")
                return
            }
            if (seq == lastSeq) return
            lastSeq = seq
            val pressed = when (button) {
                1 -> SwitchButton.NEXT
                2 -> SwitchButton.SELECT
                3 -> SwitchButton.PREVIOUS
                else -> return
            }
            Log.d(TAG, "Switch box (Bluetooth): $pressed")
            onButton(pressed)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Log.w(TAG, "Bluetooth scan failed: $errorCode")
            onStatus("Bluetooth: scan failed ($errorCode)")
        }
    }

    fun start() {
        if (scanning) return
        if (!hasPermission()) {
            Log.w(TAG, "Bluetooth scan permission missing")
            onStatus("Bluetooth: permission missing")
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "Bluetooth is off or has no LE scanner")
            onStatus("Bluetooth: off")
            return
        }
        val filter = ScanFilter.Builder()
            .setManufacturerData(COMPANY_ID, byteArrayOf('G'.code.toByte(), 'V'.code.toByte()))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // a press should arrive within ~100 ms
            .build()
        lastSeq = -1
        scanner.startScan(listOf(filter), settings, callback)
        scanning = true
        Log.i(TAG, "Bluetooth scan started")
    }

    fun stop() {
        if (!scanning) return
        scanning = false
        if (hasPermission()) {
            runCatching {
                context.getSystemService(BluetoothManager::class.java)
                    ?.adapter?.bluetoothLeScanner?.stopScan(callback)
            }
        }
    }

    private fun hasPermission(): Boolean {
        val permission = if (android.os.Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN
        else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "GivenVoice"
        private const val COMPANY_ID = 0xFFFF
    }
}
