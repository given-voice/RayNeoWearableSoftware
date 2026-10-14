package com.givenvoice.wearable

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Tells the ESP32 box which phrase to speak on the Bluetooth speaker
 * (hardware/switch_audio). Like the button link there is no pairing or connection:
 * the phrase id goes out in BLE advertising manufacturer data (company id 0xFFFF)
 * as 'G' 'S' phraseId seq_hi seq_lo for a couple of seconds. The box acts once per seq.
 */
class BlePhraseSender(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var callback: AdvertiseCallback? = null
    private var seq = (System.currentTimeMillis() and 0xFFFF).toInt()

    /** Returns true if the request was handed to the Bluetooth stack. */
    fun send(phraseId: Int): Boolean {
        if (!hasPermission()) {
            Log.w(TAG, "Bluetooth advertise permission missing")
            return false
        }
        val advertiser = context.getSystemService(BluetoothManager::class.java)
            ?.adapter?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "No BLE advertiser")
            return false
        }
        stop(advertiser)
        seq = (seq + 1) and 0xFFFF
        val payload = byteArrayOf(
            'G'.code.toByte(), 'S'.code.toByte(), phraseId.toByte(),
            (seq shr 8).toByte(), (seq and 0xFF).toByte(),
        )
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(COMPANY_ID, payload)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "Phrase $phraseId advertised to speaker box (seq $seq)")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "Advertising phrase failed: $errorCode")
            }
        }
        callback = cb
        advertiser.startAdvertising(settings, data, cb)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stop(advertiser) }, ADVERTISE_MS)
        return true
    }

    private fun stop(advertiser: android.bluetooth.le.BluetoothLeAdvertiser) {
        callback?.let { runCatching { advertiser.stopAdvertising(it) } }
        callback = null
    }

    private fun hasPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 31) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "GivenVoice"
        private const val COMPANY_ID = 0xFFFF
        private const val ADVERTISE_MS = 2500L
    }
}
