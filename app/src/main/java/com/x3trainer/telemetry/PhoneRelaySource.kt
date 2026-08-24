package com.x3trainer.telemetry

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads live Active2 telemetry from the paired Galaxy phone over classic
 * Bluetooth RFCOMM. This is the X3 Pro's supported phone link; BLE scanning is
 * intentionally left to the S23 companion because the glasses vendor stack
 * does not reliably expose a BluetoothLeScanner to third-party apps.
 */
@SuppressLint("MissingPermission")
class PhoneRelaySource(private val context: Context) : TelemetrySource {
    companion object {
        private const val TAG = "X3TrainerPhoneLink"
        private val SERVICE_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        private const val STALE_MS = 8_000L
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val running = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var socket: BluetoothSocket? = null
    private var listener: TelemetrySource.Listener? = null
    private var lastPacketAt = 0L
    private var staleWarned = false

    private val staleCheck = object : Runnable {
        override fun run() {
            if (!running.get()) return
            if (lastPacketAt > 0L &&
                SystemClock.uptimeMillis() - lastPacketAt > STALE_MS &&
                !staleWarned
            ) {
                staleWarned = true
                listener?.onDeviceWarning("WATCH DATA STALE - CHECK PHONE BRIDGE")
            }
            handler.postDelayed(this, 2_000L)
        }
    }

    override fun start(listener: TelemetrySource.Listener) {
        this.listener = listener
        if (!hasPermission()) {
            listener.onStatus("ALLOW PHONE LINK")
            listener.onDeviceWarning("BLUETOOTH PERMISSION REQUIRED")
            return
        }
        if (adapter?.isEnabled != true) {
            listener.onStatus("PHONE LINK OFF")
            listener.onDeviceWarning("BLUETOOTH IS OFF")
            return
        }
        if (!running.compareAndSet(false, true)) return
        listener.onStatus("CONNECTING TO PHONE")
        Thread(::connectionLoop, "X3TrainerPhoneRelay").apply { isDaemon = true }.start()
        handler.postDelayed(staleCheck, 4_000L)
    }

    private fun connectionLoop() {
        while (running.get()) {
            val phone = findPhone()
            if (phone == null) {
                postStatus("PAIRED PHONE NOT FOUND")
                postWarning("PAIR X3 PRO WITH GALAXY PHONE")
                sleep(3_000L)
                continue
            }

            try {
                postStatus("CONNECTING GALAXY S23")
                val link = phone.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket = link
                Log.i(TAG, "Connecting RFCOMM to ${phone.name} ${phone.address}")
                link.connect()
                if (!running.get()) break
                postStatus("PHONE BRIDGE CONNECTED")
                synchronized(link.outputStream) {
                    link.outputStream.write("x3trainer-ready\n".toByteArray())
                    link.outputStream.flush()
                }
                val reader = BufferedReader(InputStreamReader(link.inputStream))
                while (running.get() && link.isConnected) {
                    val line = reader.readLine() ?: break
                    handle(line)
                }
            } catch (e: IOException) {
                if (running.get()) {
                    Log.w(TAG, "Phone RFCOMM connection ended", e)
                    postStatus("PHONE LINK RETRYING")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Phone Bluetooth permission failure", e)
                postWarning("BLUETOOTH PERMISSION REQUIRED")
                break
            } finally {
                closeSocket()
            }
            sleep(1_500L)
        }
    }

    private fun handle(line: String) {
        val json = runCatching { JSONObject(line) }
            .onFailure { Log.w(TAG, "Ignoring malformed bridge packet") }
            .getOrNull() ?: return
        when (json.optString("type")) {
            "telemetry" -> {
                val hr = json.optInt("hr", 0)
                if (hr <= 0) return
                val cadence = json.optInt("cadence", 0)
                val speed = json.optDouble("speed", -1.0).toFloat()
                lastPacketAt = SystemClock.uptimeMillis()
                staleWarned = false
                handler.post {
                    listener?.onStatus("ACTIVE2 VIA PHONE")
                    listener?.onSample(TelemetrySample(hr, cadence, speed, lastPacketAt))
                }
                Log.d(TAG, "sample hr=$hr cadence=$cadence speed=$speed")
            }
            "status" -> {
                val watch = json.optString("watch")
                if (lastPacketAt == 0L && watch.isNotBlank()) postStatus(watch.uppercase())
            }
        }
    }

    private fun findPhone(): BluetoothDevice? {
        val bonded = adapter?.bondedDevices.orEmpty()
        bonded.forEach { Log.i(TAG, "bonded device name=${it.name} address=${it.address}") }
        return bonded.firstOrNull {
            val name = it.name.orEmpty()
            name.contains("Galaxy", true) || name.contains("S23", true)
        } ?: bonded.firstOrNull {
            val name = it.name.orEmpty()
            !name.contains("Watch", true) && !name.contains("RayNeo", true)
        }
    }

    private fun postStatus(value: String) = handler.post { listener?.onStatus(value) }
    private fun postWarning(value: String) = handler.post { listener?.onDeviceWarning(value) }

    private fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    override fun stop() {
        running.set(false)
        handler.removeCallbacks(staleCheck)
        closeSocket()
        listener = null
    }
}
