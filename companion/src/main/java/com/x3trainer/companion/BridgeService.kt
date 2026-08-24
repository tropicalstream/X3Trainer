package com.x3trainer.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

class BridgeService : Service() {
    companion object {
        private const val TAG = "X3TrainerBridge"
        private const val CHANNEL = "x3trainer_bridge"
        private const val NOTIFICATION_ID = 3714
        const val ACTION_TEST_SAMPLE = "com.x3trainer.companion.TEST_SAMPLE"
        /** No watch sample for this long means the phone should speak for itself. */
        private const val WATCHLESS_MS = 5_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var watch: WatchBleClient
    private lateinit var glasses: PhoneRfcommServer
    private lateinit var motion: PhoneMotion
    private var wakeLock: PowerManager.WakeLock? = null

    private val heartbeat = object : Runnable {
        override fun run() {
            val s = BridgeState.snapshot
            glasses.sendStatus(s.watchStatus)
            // The watch drives the sample rate when it is present. When it is
            // not — no broadcast, out of range, still finishing its own
            // handshake — the phone's own pedometer and GPS are still
            // measuring, and there is no reason for the glasses to show
            // nothing at all. Published on the heartbeat so the wearer sees a
            // live cadence and pace with the pulse simply unknown.
            val watchIsQuiet = s.lastSampleAt == 0L ||
                SystemClock.uptimeMillis() - s.lastSampleAt > WATCHLESS_MS
            if (watchIsQuiet && ::motion.isInitialized) {
                val (cadence, speed) = motion.sample()
                if (cadence > 0 || speed >= 0f) {
                    BridgeState.update { it.copy(cadence = cadence, speedMps = speed, heartRate = 0) }
                    glasses.publish(0, cadence, speed)
                }
            }
            if (s.lastSampleAt > 0 && SystemClock.uptimeMillis() - s.lastSampleAt > 10_000L) {
                BridgeState.update { it.copy(watchStatus = "Sensor feed stale — check watch fit") }
            }
            handler.postDelayed(this, 3_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "X3Trainer:Bridge")
            .apply { acquire() }

        glasses = PhoneRfcommServer(this) { status ->
            Log.i(TAG, "glasses: $status")
            BridgeState.update { it.copy(glassesStatus = status) }
        }
        watch = WatchBleClient(
            this,
            onStatus = { status ->
                Log.i(TAG, "watch: $status")
                BridgeState.update { it.copy(watchStatus = status) }
            },
            onSample = { hr, watchCadence, watchSpeed ->
                val now = SystemClock.uptimeMillis()
                // THE WATCH WINS WHERE IT ACTUALLY KNOWS. A Galaxy Watch
                // running X3Trainer Link serves Running Speed & Cadence and
                // its numbers come from the same limb that is doing the work,
                // so they are preferred. A Pixel Watch's native broadcast
                // carries no such service and leaves these at "unknown", and
                // that is where the phone's own pedometer and GPS answer
                // instead of nobody answering.
                val (phoneCadence, phoneSpeed) = motion.sample()
                val cadence = if (watchCadence > 0) watchCadence else phoneCadence
                val speed = if (watchSpeed >= 0f) watchSpeed else phoneSpeed
                BridgeState.update {
                    it.copy(
                        watchStatus = "Live sensor feed",
                        heartRate = hr,
                        cadence = cadence,
                        speedMps = speed,
                        lastSampleAt = now
                    )
                }
                glasses.publish(hr, cadence, speed)
            }
        )
        motion = PhoneMotion(this)
        motion.start()
        glasses.start()
        watch.start()
        handler.post(heartbeat)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-arm the phone's own sensors on every start. The activity starts
        // this service as soon as Bluetooth is granted, which can be while the
        // location/activity-recognition prompt is still on screen — and a
        // grant that lands afterwards would otherwise never be picked up,
        // because onCreate does not run again for an already-live service.
        if (::motion.isInitialized) motion.start()
        if (intent?.action == ACTION_TEST_SAMPLE &&
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            ::glasses.isInitialized
        ) {
            val now = SystemClock.uptimeMillis()
            BridgeState.update {
                it.copy(
                    watchStatus = "Diagnostic sensor sample",
                    heartRate = 88,
                    cadence = 164,
                    speedMps = 2.5f,
                    lastSampleAt = now
                )
            }
            glasses.publish(88, 164, 2.5f)
            Log.i(TAG, "Published debug telemetry sample")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        if (::watch.isInitialized) watch.stop()
        if (::glasses.isInitialized) glasses.stop()
        if (::motion.isInitialized) motion.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "X3Trainer bridge", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("X3Trainer Bridge")
            .setContentText("Watch telemetry link is running")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }
}
