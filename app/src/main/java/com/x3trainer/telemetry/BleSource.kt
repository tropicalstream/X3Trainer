package com.x3trainer.telemetry

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.util.UUID

/**
 * Standard-profile BLE client: subscribes to the Bluetooth SIG Heart Rate
 * service (0x180D) and, when present, Running Speed & Cadence (0x1814).
 * Covers chest straps, sport watches with native broadcast (Garmin/Polar
 * style), and Apple Watch running an HR-broadcast app such as HeartCast —
 * the Watch never exposes HR over standard BLE natively, and RayNeo's
 * Apple Watch gesture link carries no health data.
 *
 * NOTE: whether the X3 Pro allows app-level BLE central scanning at all is
 * unverified (its consumer pairing is phone-only, but HR broadcast needs no
 * pairing). This class is deliberately also the on-device test: failure
 * surfaces as a scan warning, never a crash. If scanning proves blocked, a
 * the X3Trainer Active2 broadcaster supplies the standard profile directly.
 *
 * Permission checks live in MainActivity; every call here is wrapped so a
 * revoked permission degrades to a device warning instead of a crash.
 */
@SuppressLint("MissingPermission")
class BleSource(private val context: Context) : TelemetrySource {

    companion object {
        private const val TAG = "X3TrainerBle"
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val RSC_SERVICE: UUID = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb")
        val RSC_MEASUREMENT: UUID = UUID.fromString("00002a53-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val STALE_MS = 8000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var listener: TelemetrySource.Listener? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var lastPacketAt = 0L
    private var warnedStale = false

    // Latest partial values, merged into each emitted sample.
    private var hr = 0
    private var cadence = 0
    private var speed = -1f

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val staleCheck = object : Runnable {
        override fun run() {
            val l = listener ?: return
            if (lastPacketAt > 0 && SystemClock.uptimeMillis() - lastPacketAt > STALE_MS && !warnedStale) {
                warnedStale = true
                l.onDeviceWarning("SIGNAL LOST - CHECK DEVICE")
            }
            handler.postDelayed(this, 2000L)
        }
    }

    override fun start(listener: TelemetrySource.Listener) {
        this.listener = listener
        val a = adapter
        if (a == null || !a.isEnabled) {
            listener.onStatus("BT OFF")
            listener.onDeviceWarning("BLUETOOTH IS OFF - CHECK DEVICE")
            return
        }
        startScan(a)
        handler.postDelayed(staleCheck, 4000L)
    }

    private fun startScan(a: BluetoothAdapter) {
        runCatching {
            val scanner = a.bluetoothLeScanner ?: return
            val filters = listOf(
                ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()
            )
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanning = true
            listener?.onStatus("SCANNING")
            scanner.startScan(filters, settings, scanCb)
        }.onFailure {
            Log.w(TAG, "scan failed", it)
            listener?.onDeviceWarning("SCAN FAILED - CHECK PERMISSIONS")
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            scanning = false
            runCatching { adapter?.bluetoothLeScanner?.stopScan(this) }
            listener?.onStatus("PAIRING")
            runCatching {
                gatt = result.device.connectGatt(context, false, gattCb)
            }.onFailure { listener?.onDeviceWarning("CONNECT FAILED - CHECK DEVICE") }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener?.onDeviceWarning("SCAN FAILED ($errorCode) - CHECK DEVICE")
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener?.onStatus("LINKED")
                runCatching { g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                listener?.onStatus("RECONNECTING")
                listener?.onDeviceWarning("SENSOR DISCONNECTED - CHECK DEVICE")
                // Fresh scan after a short pause; simplest robust reconnect.
                handler.postDelayed({ adapter?.let { startScan(it) } }, 1500L)
                runCatching { g.close() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            var found = false
            g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)?.let {
                enableNotify(g, it); found = true
            }
            g.getService(RSC_SERVICE)?.getCharacteristic(RSC_MEASUREMENT)?.let {
                // The second CCCD write must wait for the first to complete;
                // a small delay is the dependency-free way.
                handler.postDelayed({ enableNotify(g, it) }, 700L)
            }
            if (found) {
                // Start the stale clock at connection time. A connected relay that never
                // produces a fresh health sample must not look healthy indefinitely.
                lastPacketAt = SystemClock.uptimeMillis()
                listener?.onStatus("ACTIVE2 DIRECT")
            } else {
                listener?.onStatus("NO HR SERVICE")
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val data = ch.value ?: return
            when (ch.uuid) {
                HR_MEASUREMENT -> parseHr(data)
                RSC_MEASUREMENT -> parseRsc(data)
            }
            lastPacketAt = SystemClock.uptimeMillis()
            warnedStale = false
            listener?.onSample(TelemetrySample(hr, cadence, speed, lastPacketAt))
        }
    }

    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        runCatching {
            g.setCharacteristicNotification(ch, true)
            val d: BluetoothGattDescriptor = ch.getDescriptor(CCCD) ?: return
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    /** org.bluetooth.characteristic.heart_rate_measurement */
    private fun parseHr(d: ByteArray) {
        if (d.isEmpty()) return
        val flags = d[0].toInt()
        hr = if (flags and 1 == 0) d.getOrNull(1)?.toInt()?.and(0xFF) ?: 0
        else ((d.getOrNull(2)?.toInt()?.and(0xFF) ?: 0) shl 8) or (d.getOrNull(1)?.toInt()?.and(0xFF) ?: 0)
    }

    /** org.bluetooth.characteristic.rsc_measurement: speed u16 (1/256 m/s), cadence u8. */
    private fun parseRsc(d: ByteArray) {
        if (d.size < 4) return
        val rawSpeed = ((d[2].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
        speed = rawSpeed / 256f
        // Bluetooth SIG RSC cadence is already expressed in 1/minute.
        cadence = d[3].toInt() and 0xFF
    }

    override fun stop() {
        handler.removeCallbacks(staleCheck)
        if (scanning) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCb) }
        scanning = false
        runCatching { gatt?.close() }
        gatt = null
        listener = null
    }
}
