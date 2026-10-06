package com.givenvoice.wearable

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Simulation-only stand-in for [UsbSerialSwitchSource]: reads the same
 * "BTN:n" lines from simulation/bridge.py over TCP. Only started in debug
 * builds on the emulator. The emulator's loopback reaches the PC running the
 * bridge through "adb reverse tcp:5000 tcp:5000" (run-emulator.ps1 sets it up).
 * Connecting to the PC's LAN-style address 10.0.2.2 instead is blocked for apps
 * on Android 17 without the ACCESS_LOCAL_NETWORK permission.
 * Retries every couple of seconds, so the bridge can be started at any time.
 */
class TcpSwitchSource(
    private val host: String,
    private val port: Int,
    private val onLine: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    private var lastError: String? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "TcpSwitchSource", isDaemon = true) {
            while (running) {
                try {
                    Socket().use { s ->
                        socket = s
                        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                        Log.i(TAG, "Simulation bridge connected at $host:$port")
                        s.getInputStream().bufferedReader(Charsets.US_ASCII).forEachLine { line ->
                            mainHandler.post { onLine(line) }
                        }
                    }
                } catch (e: IOException) {
                    // Bridge not running yet, or it was closed - try again shortly.
                    // Log each distinct reason once, so the retries don't flood Logcat.
                    val reason = e.javaClass.simpleName
                    if (reason != lastError) {
                        lastError = reason
                        Log.d(TAG, "Simulation bridge not reachable at $host:$port: $reason")
                    }
                }
                socket = null
                if (running) Thread.sleep(RETRY_MS)
            }
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }

    companion object {
        private const val TAG = "GivenVoice"
        private const val CONNECT_TIMEOUT_MS = 2000
        private const val RETRY_MS = 2000L
    }
}
