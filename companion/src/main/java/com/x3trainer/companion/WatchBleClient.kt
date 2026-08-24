package com.x3trainer.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.ParcelUuid
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

/**
 * BLE central for the ADOPTED heart-rate and running-cadence services.
 *
 * Deliberately generic. It filters on service UUID 0x180D rather than on any
 * device name, so whatever is broadcasting a pulse is what it talks to: a
 * Pixel Watch with heart-rate broadcasting switched on, a Galaxy Watch
 * running the bundled X3Trainer Link app, or a plain chest strap. The named
 * fallback below exists only for watches that broadcast without putting the
 * service UUID in their advertisement.
 */
@SuppressLint("MissingPermission")
class WatchBleClient(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onSample: (hr: Int, cadence: Int, speedMps: Float) -> Unit
) {
    companion object {
        private const val TAG = "X3TrainerWatchLink"
        private val HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HR_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val RSC_SERVICE = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb")
        private val RSC_MEASUREMENT = UUID.fromString("00002a53-0000-1000-8000-00805f9b34fb")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Watches known to broadcast a pulse without naming 0x180D in their
         * advertisement, which the primary filter therefore cannot see. A last
         * resort, not the mechanism: anything listed properly is found by
         * service UUID and never reaches this list.
         */
        /** Watch-supplied cadence/speed older than this is no longer an answer. */
        private const val RSC_STALE_MS = 5_000L

        private val KNOWN_WATCH_NAMES = listOf(
            "Galaxy Watch", "Active2", "Pixel Watch", "X3Trainer"
        )
    }

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val descriptorQueue = ArrayDeque<BluetoothGattDescriptor>()
    private var running = false
    private var scanning = false
    private var fallback = false
    private var gatt: BluetoothGatt? = null
    private var hrReady = false
    private var hr = 0
    private var cadence = 0
    private var speed = -1f
    private var lastRscAt = 0L

    private val firstSampleTimeout = Runnable {
        if (running && hrReady && hr <= 0) {
            onStatus("On watch, confirm Galaxy S23 Ultra")
        }
    }

    private val scanTimeout = Runnable {
        if (!running || !scanning) return@Runnable
        if (!fallback) startScan(useFallback = true) else {
            stopScan()
            onStatus("No heart-rate broadcast found — switch it on at the watch")
            scheduleScan(2_500L)
        }
    }

    private val retryScan = Runnable { if (running) startScan(useFallback = false) }

    fun start() {
        if (running) return
        running = true
        // Connected Fitness stops advertising as soon as a central accepts it.
        // If Android restarts this service while the watch still says
        // "Sharing", scanning can never rediscover that already-established
        // session. Reattach through the persisted Pixel Watch bond first; the
        // GATT client then joins the existing encrypted ACL and can restore its
        // Heart Rate Measurement subscription.
        val sharingWatch = runCatching {
            adapter?.bondedDevices?.firstOrNull { device ->
                runCatching { device.name.orEmpty().contains("Pixel Watch", ignoreCase = true) }
                    .getOrDefault(false)
            }
        }.getOrNull()
        if (sharingWatch != null) {
            Log.i(TAG, "reattaching to bonded ${sharingWatch.name}")
            connect(sharingWatch)
        } else {
            startScan(useFallback = false)
        }
    }

    private fun startScan(useFallback: Boolean) {
        if (!running) return
        stopScan()
        val scanner = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || scanner == null) {
            onStatus("Waiting for phone Bluetooth")
            scheduleScan(1_500L)
            return
        }
        fallback = useFallback
        val filters = if (useFallback) emptyList() else listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        seenThisScan.clear()
        onStatus(if (useFallback) "Looking for a known watch" else "Scanning for a heart-rate broadcast")
        Log.i(TAG, "scan start fallback=$useFallback")
        try {
            scanner.startScan(filters, settings, scanCallback)
            handler.postDelayed(scanTimeout, if (useFallback) 18_000L else 10_000L)
        } catch (e: Exception) {
            scanning = false
            Log.e(TAG, "scan start failed", e)
            onStatus("Watch scan failed; retrying")
            scheduleScan(2_000L)
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (scanning) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
    }

    /** Addresses logged during the current scan, so one line each. */
    private val seenThisScan = mutableSetOf<String>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!running || !scanning) return
            val advertisedServices = result.scanRecord?.serviceUuids.orEmpty()
            val hasHr = advertisedServices.any { it.uuid == HR_SERVICE }
            val name = runCatching { result.scanRecord?.deviceName ?: result.device.name }
                .getOrNull().orEmpty()
            // Name matching is the fallback path only: some watches
            // broadcast a pulse without listing 0x180D in the advertisement,
            // and these are the ones known to do it.
            val knownWatch = KNOWN_WATCH_NAMES.any { name.contains(it, true) }
            // Every peripheral in earshot, once each per scan. On a chain this
            // long "nothing found" has too many possible causes — watch not
            // broadcasting, broadcasting under another name, advertising
            // without the service UUID — and they are indistinguishable
            // without seeing what the radio actually heard.
            if (seenThisScan.add(result.device.address)) {
                Log.i(TAG, "saw ${result.device.address} name='$name' rssi=${result.rssi} " +
                    "services=${advertisedServices.joinToString { it.uuid.toString().take(8) }}")
            }
            if (!hasHr && (!fallback || !knownWatch)) return
            Log.i(TAG, "watch candidate name='$name' address=${result.device.address} rssi=${result.rssi}")
            // Pixel Watch Connected Fitness advertises only for its visible
            // countdown. The receiving app must accept that advertisement
            // immediately; delaying here consumes the acceptance window and
            // produces a technically subscribed but permanently silent link.
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            handler.removeCallbacks(scanTimeout)
            Log.e(TAG, "scan failed code=$errorCode")
            onStatus("Watch scan error $errorCode; retrying")
            scheduleScan(2_000L)
        }
    }

    private fun connect(device: BluetoothDevice) {
        stopScan()
        descriptorQueue.clear()
        hrReady = false
        runCatching { gatt?.close() }
        // device.name needs BLUETOOTH_CONNECT and can be null before the
        // bond completes; the address is never a useful thing to show a human.
        val label = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
        onStatus(if (label != null) "Connecting to $label" else "Connecting to watch")
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connection status=$status state=$newState")
            if (!running) {
                g.close()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                gatt = g
                onStatus("Watch connected; discovering sensors")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!g.discoverServices()) fail(g, "Watch service discovery failed")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (gatt === g) gatt = null
                g.close()
                descriptorQueue.clear()
                hrReady = false
                handler.removeCallbacks(firstSampleTimeout)
                // FORGET WHAT THE OLD WATCH SAID. cadence/speed persisted
                // across a disconnect and were replayed on every later heart
                // beat, so one Galaxy Watch session left values that outranked
                // the phone's own measurements for the rest of the process —
                // permanently suppressing the fallback on a watch that serves
                // no cadence at all.
                cadence = 0
                speed = -1f
                onStatus("Watch disconnected; reconnecting")
                scheduleScan(1_500L)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.i(TAG, "services status=$status ${g.services.joinToString { it.uuid.toString() }}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(g, "Watch service error $status")
                return
            }
            val heart = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
            if (heart == null) {
                fail(g, "Watch stopped sharing heart rate")
                return
            }
            descriptorQueue.clear()
            if (!queueNotify(g, heart, required = true)) return
            g.getService(RSC_SERVICE)?.getCharacteristic(RSC_MEASUREMENT)?.let {
                queueNotify(g, it, required = false)
            }
            writeNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptorQueue.peekFirst() === descriptor) descriptorQueue.removeFirst()
            val heart = descriptor.characteristic.uuid == HR_MEASUREMENT
            Log.i(TAG, "subscription ${descriptor.characteristic.uuid} status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS && heart) {
                fail(g, "Heart-rate subscription error $status")
                return
            }
            if (heart) {
                hrReady = true
                onStatus("Subscribed — confirm on watch")
                handler.removeCallbacks(firstSampleTimeout)
                handler.postDelayed(firstSampleTimeout, 8_000L)
            }
            writeNext(g)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handle(characteristic.uuid, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) = handle(characteristic.uuid, value)
    }

    private fun queueNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic, required: Boolean): Boolean {
        val local = g.setCharacteristicNotification(ch, true)
        val descriptor = ch.getDescriptor(CCCD)
        if (!local || descriptor == null) {
            if (required) fail(g, "Watch notifications unavailable")
            return false
        }
        descriptorQueue.addLast(descriptor)
        return true
    }

    private fun writeNext(g: BluetoothGatt) {
        val d = descriptorQueue.peekFirst() ?: return
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
        if (!started) fail(g, "Watch subscription could not start")
    }

    private fun handle(uuid: UUID, data: ByteArray) {
        // Every notification, as bytes. "Subscribed but no numbers" has two
        // very different causes — a watch sending nothing, or a payload we
        // mis-parse — and they are indistinguishable without seeing the wire.
        Log.i(TAG, "notify ${uuid.toString().take(8)} ${data.joinToString(" ") {
            "%02x".format(it)
        }}")
        if (!hrReady) return
        when (uuid) {
            HR_MEASUREMENT -> {
                if (data.size < 2) return
                hr = if ((data[0].toInt() and 1) == 0) data[1].toInt() and 0xff
                else if (data.size >= 3) (data[1].toInt() and 0xff) or ((data[2].toInt() and 0xff) shl 8)
                else return
                if (hr > 0) handler.removeCallbacks(firstSampleTimeout)
            }
            RSC_MEASUREMENT -> {
                if (data.size < 4) return
                lastRscAt = SystemClock.elapsedRealtime()
                speed = (((data[2].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)) / 256f
                cadence = data[3].toInt() and 0xff
            }
            else -> return
        }
        // Age the watch's cadence/speed out rather than repeating the last
        // ones forever: a watch that stops serving RSC should hand the job
        // back to the phone, not keep answering from memory.
        val fresh = lastRscAt > 0 && SystemClock.elapsedRealtime() - lastRscAt <= RSC_STALE_MS
        if (hr > 0) onSample(hr, if (fresh) cadence else 0, if (fresh) speed else -1f)
    }

    private fun fail(g: BluetoothGatt, message: String) {
        Log.w(TAG, message)
        onStatus(message)
        if (gatt === g) gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
        descriptorQueue.clear()
        hrReady = false
        handler.removeCallbacks(firstSampleTimeout)
        scheduleScan(1_500L)
    }

    private fun scheduleScan(delay: Long) {
        handler.removeCallbacks(retryScan)
        if (running) handler.postDelayed(retryScan, delay)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(scanTimeout)
        handler.removeCallbacks(retryScan)
        handler.removeCallbacks(firstSampleTimeout)
        stopScan()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        descriptorQueue.clear()
    }
}
