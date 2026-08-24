package com.x3trainer.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Classic Bluetooth server. The X3 Pro already has a bonded BR/EDR link to the phone. */
@SuppressLint("MissingPermission")
class PhoneRfcommServer(
    context: Context,
    private val onStatus: (String) -> Unit
) {
    companion object {
        const val TAG = "X3TrainerPhoneLink"
        // The X3 Pro firmware only completes RFCOMM SDP reliably for SPP.
        // This is the same transport convention proven by Everyday on-device.
        val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        private const val SERVICE_NAME = "X3TrainerBridge"
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var clientSocket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread(::acceptLoop, "X3TrainerRfcommAccept").apply { isDaemon = true }.start()
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                if (adapter?.isEnabled != true) {
                    onStatus("Phone Bluetooth is off")
                    Thread.sleep(2_000L)
                    continue
                }
                onStatus("Waiting for X3Trainer")
                val listener = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                serverSocket = listener
                val socket = listener.accept()
                clientSocket = socket
                output = socket.outputStream
                onStatus("X3 Pro connected")
                Log.i(TAG, "X3 RFCOMM connected: ${socket.remoteDevice.name}")
                sendStatus("bridge-ready")

                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                while (running.get() && socket.isConnected) {
                    if (reader.readLine() == null) break
                }
            } catch (e: IOException) {
                if (running.get()) Log.w(TAG, "RFCOMM link ended", e)
            } catch (e: SecurityException) {
                onStatus("Bluetooth permission required")
                Log.e(TAG, "RFCOMM permission failure", e)
                break
            } finally {
                closeClient()
                runCatching { serverSocket?.close() }
                serverSocket = null
                if (running.get()) {
                    onStatus("X3 disconnected; listening again")
                    Thread.sleep(800L)
                }
            }
        }
    }

    fun publish(hr: Int, cadence: Int, speedMps: Float) {
        // A HEART RATE IS NOT THE PRICE OF ADMISSION. This used to drop every
        // sample without one, which made sense when the watch was the only
        // sensor in the chain — but the phone now measures cadence and ground
        // speed itself, and a run with no pulse still has a pace worth
        // coaching. The glasses already render each field's own "unknown".
        if (hr <= 0 && cadence <= 0 && speedMps < 0f) return
        send(
            JSONObject()
                .put("type", "telemetry")
                .put("hr", hr)
                .put("cadence", cadence)
                .put("speed", speedMps.toDouble())
                .put("time", System.currentTimeMillis())
                .toString()
        )
    }

    fun sendStatus(watchStatus: String) {
        send(
            JSONObject()
                .put("type", "status")
                .put("watch", watchStatus)
                .put("time", System.currentTimeMillis())
                .toString()
        )
    }

    private fun send(line: String) {
        val stream = output ?: return
        try {
            synchronized(writeLock) {
                stream.write((line + "\n").toByteArray(Charsets.UTF_8))
                stream.flush()
            }
        } catch (e: IOException) {
            Log.w(TAG, "RFCOMM send failed", e)
            closeClient()
        }
    }

    private fun closeClient() {
        output = null
        runCatching { clientSocket?.close() }
        clientSocket = null
    }

    fun stop() {
        running.set(false)
        closeClient()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
