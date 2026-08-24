package com.x3trainer.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.UUID

/**
 * The watch end of X3Trainer's telemetry chain: read the wearer's pulse and
 * cadence, and hand them to the phone as a STANDARD Bluetooth heart-rate
 * monitor would.
 *
 * WHY PRETEND TO BE A CHEST STRAP. The phone half of this app already scans
 * for anything advertising the adopted Heart Rate service and subscribes to
 * it — that is how it talked to the Galaxy Watch, whose Tizen app did exactly
 * this in C. Speaking the same standard here means the phone needs no change
 * at all to gain a new watch, and it means a plain BLE chest strap works in
 * place of a watch for anyone who owns one. The alternative — the Wear Data
 * Layer — is the more idiomatic way for a watch to reach its own phone, but
 * it would bind both apps to Play Services, work only for the paired phone,
 * and leave the standard path unbuilt.
 *
 * Wear OS is Android, so all of this is plain platform API: a GATT server, an
 * advertiser, and two sensors. No support library, no Play Services, nothing
 * vendor-specific — the same zero-dependency rule the rest of the project
 * keeps.
 */
class BroadcastService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "X3TrainerWear"
        private const val CHANNEL = "x3trainer_wear"
        private const val NOTIFICATION_ID = 3715

        /** Adopted 16-bit UUIDs, expanded into the Bluetooth base UUID. */
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val RSC_SERVICE: UUID = UUID.fromString("00001814-0000-1000-8000-00805f9b34fb")
        val RSC_MEASUREMENT: UUID = UUID.fromString("00002a53-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** How often to push a sample, whether or not the sensor refreshed. */
        private const val PUBLISH_MS = 1_000L
        /** Cadence is steps over a rolling window; shorter reads as noise. */
        private const val CADENCE_WINDOW_MS = 10_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var hrChar: BluetoothGattCharacteristic? = null
    private var rscChar: BluetoothGattCharacteristic? = null
    /** Queued until the first service is acknowledged — see onServiceAdded. */
    private var pendingRscService: BluetoothGattService? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Everyone who has subscribed, so a notify goes to the right places. */
    private val subscribers = mutableSetOf<BluetoothDevice>()

    @Volatile private var heartRate = 0
    @Volatile private var cadence = 0
    private var stepsAtWindowStart = -1L
    private var windowStartedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotice()
        // The screen goes off two seconds into a run. Without this the CPU
        // sleeps between sensor batches and the phone sees a feed that stalls
        // and resumes rather than a pulse.
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "X3Trainer:WearBroadcast")
            .apply { acquire() }
        startSensors()
        startGattServer()
        handler.post(publisher)
    }

    // ── Sensors ──────────────────────────────────────────────────────────

    private fun startSensors() {
        val sm = getSystemService(SENSOR_SERVICE) as? SensorManager ?: run {
            WearState.set(status = "No sensor service"); return
        }
        val hr = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (hr == null) {
            WearState.set(status = "No heart-rate sensor")
        } else {
            // SENSOR_DELAY_NORMAL, not FASTEST: the wrist sensor produces a
            // reading every second or so no matter what is asked of it, and
            // asking for more only spends battery.
            sm.registerListener(this, hr, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values.firstOrNull()?.toInt() ?: return
                // A zero or negative reading means "not in contact", which is
                // information, not a measurement — publishing it as a pulse
                // would have the coach react to a watch being taken off.
                if (bpm > 0) heartRate = bpm
            }
            Sensor.TYPE_STEP_COUNTER -> {
                // TYPE_STEP_COUNTER is monotonic since boot, so cadence is a
                // difference over a window rather than the value itself.
                val total = event.values.firstOrNull()?.toLong() ?: return
                val now = SystemClock.elapsedRealtime()
                if (stepsAtWindowStart < 0) {
                    stepsAtWindowStart = total; windowStartedAt = now; return
                }
                val elapsed = now - windowStartedAt
                if (elapsed >= CADENCE_WINDOW_MS) {
                    val steps = (total - stepsAtWindowStart).coerceAtLeast(0)
                    cadence = (steps * 60_000L / elapsed).toInt().coerceIn(0, 255)
                    stepsAtWindowStart = total; windowStartedAt = now
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* not acted on */ }

    // ── GATT server ──────────────────────────────────────────────────────

    private fun startGattServer() {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = bm.adapter
        if (adapter?.isEnabled != true) {
            WearState.set(status = "Turn Bluetooth on")
            handler.postDelayed({ startGattServer() }, 3_000L)
            return
        }
        val server = runCatching { bm.openGattServer(this, serverCallback) }.getOrNull()
        if (server == null) {
            WearState.set(status = "Bluetooth permission needed")
            return
        }
        gattServer = server

        // Heart rate: NOTIFY only, plus the client-configuration descriptor
        // that a subscription is actually written to. Omit the CCCD and a
        // well-behaved central has nowhere to register, so it never hears
        // anything — the classic silent failure of a hand-rolled GATT server.
        val hrService = BluetoothGattService(HR_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        hrChar = BluetoothGattCharacteristic(
            HR_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { addDescriptor(newCccd()) }
        hrService.addCharacteristic(hrChar)
        server.addService(hrService)

        // Built now, added LATER. A GATT server accepts one addService at a
        // time: issue the second before the first is acknowledged and it is
        // silently dropped, leaving a server that advertises cadence and
        // never serves it. onServiceAdded below adds this one.
        rscChar = BluetoothGattCharacteristic(
            RSC_MEASUREMENT,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply { addDescriptor(newCccd()) }
        pendingRscService =
            BluetoothGattService(RSC_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                .apply { addCharacteristic(rscChar) }
    }

    private fun newCccd() = BluetoothGattDescriptor(
        CCCD,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
    )

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "addService(${service?.uuid}) failed: $status")
                WearState.set(status = "Bluetooth service refused")
                return
            }
            val next = pendingRscService
            if (next != null) {
                pendingRscService = null
                runCatching { gattServer?.addService(next) }
            } else {
                // Both services are live; only now is there anything worth
                // finding, so this is where advertising begins.
                startAdvertising()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            device ?: return
            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                subscribers.remove(device)
                WearState.set(status = "Phone disconnected", linked = subscribers.isNotEmpty())
                // Advertising stops once a central connects; put it back so
                // the phone can find us again after a walk out of range.
                startAdvertising()
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (descriptor?.uuid == CCCD && device != null) {
                val on = value != null && value.isNotEmpty() && value[0].toInt() != 0
                if (on) subscribers.add(device) else subscribers.remove(device)
                WearState.set(
                    status = if (subscribers.isEmpty()) "Phone listening" else "Sending to phone",
                    linked = subscribers.isNotEmpty()
                )
                Log.i(TAG, "subscription ${if (on) "on" else "off"} from ${device.address}")
            }
            if (responseNeeded) {
                runCatching {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val payload = when (characteristic?.uuid) {
                HR_MEASUREMENT -> hrPayload()
                RSC_MEASUREMENT -> rscPayload()
                else -> null
            }
            runCatching {
                gattServer?.sendResponse(
                    device, requestId,
                    if (payload != null) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    offset, payload
                )
            }
        }
    }

    private fun startAdvertising() {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adv = bm.adapter?.bluetoothLeAdvertiser ?: run {
            WearState.set(status = "Advertising unsupported"); return
        }
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // The name is deliberately NOT included: an advertisement is 31 bytes
        // and a watch's name plus two 16-bit service UUIDs does not reliably
        // fit, and an over-long payload is rejected outright rather than
        // truncated. The phone matches on the HR service UUID, not the name.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(HR_SERVICE))
            .addServiceUuid(ParcelUuid(RSC_SERVICE))
            .build()
        runCatching { adv.stopAdvertising(advertiseCallback) }
        runCatching { adv.startAdvertising(settings, data, advertiseCallback) }
            .onFailure {
                Log.w(TAG, "advertise start threw: ${it.message}")
                WearState.set(status = "Advertising failed")
            }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "advertising")
            WearState.set(status = "Waiting for phone")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertise failed code=$errorCode")
            WearState.set(status = "Advertising error $errorCode")
            if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED) {
                handler.postDelayed({ startAdvertising() }, 5_000L)
            }
        }
    }

    // ── Publishing ───────────────────────────────────────────────────────

    /**
     * Heart Rate Measurement, 0x2A37. Flags byte first: bit 0 clear means the
     * value that follows is one byte, set means two, little-endian. The
     * phone's parser reads exactly this, and so does every other heart-rate
     * client in existence.
     */
    private fun hrPayload(): ByteArray {
        val bpm = heartRate
        return if (bpm in 0..255) byteArrayOf(0x00, (bpm and 0xff).toByte())
        else byteArrayOf(0x01, (bpm and 0xff).toByte(), ((bpm shr 8) and 0xff).toByte())
    }

    /**
     * RSC Measurement, 0x2A53. Flags, then speed as uint16 in units of 1/256
     * of a metre per second, then cadence in steps per minute. Speed is left
     * at zero: a wrist sensor has no honest way to produce it, and inventing
     * one would feed the coach's pace nudges with a guess.
     */
    private fun rscPayload(): ByteArray =
        byteArrayOf(0x00, 0x00, 0x00, (cadence and 0xff).toByte())

    private val publisher = object : Runnable {
        override fun run() {
            if (subscribers.isNotEmpty()) {
                notifyAll(hrChar, hrPayload())
                notifyAll(rscChar, rscPayload())
            }
            WearState.set(hr = heartRate, cadence = cadence)
            handler.postDelayed(this, PUBLISH_MS)
        }
    }

    private fun notifyAll(ch: BluetoothGattCharacteristic?, value: ByteArray) {
        val server = gattServer ?: return
        val characteristic = ch ?: return
        for (device in subscribers.toList()) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, characteristic, false, value)
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = value
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, characteristic, false)
                }
            }.onFailure { Log.w(TAG, "notify failed: ${it.message}") }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this) }
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { gattServer?.close() }
        runCatching { wakeLock?.release() }
        WearState.set(status = "Stopped", linked = false)
        super.onDestroy()
    }

    private fun startForegroundNotice() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "X3Trainer Link", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("X3Trainer Link")
            .setContentText("Sending pulse to your glasses")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, n)
    }
}
