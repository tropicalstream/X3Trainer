package com.x3trainer.companion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log

/**
 * Cadence and ground speed, measured by the PHONE.
 *
 * The watch's native Connected Fitness broadcast carries a pulse and nothing
 * else: heart rate is service 0x180D, while cadence and speed live in Running
 * Speed & Cadence 0x1814, which it does not advertise. Only the old Tizen
 * broadcaster ever filled those in, so on a Pixel Watch the coach's cadence
 * and pace nudges would simply never fire.
 *
 * They do not have to come from the wrist. The phone is already in this chain
 * and cannot leave it — the glasses have no BLE scanner of their own, so the
 * phone is relaying regardless — which means it is on the wearer's body for
 * the whole workout, with a pedometer and a GPS that are at least as honest as
 * a watch's estimate of either. So it measures them here and the watch is
 * asked only for what it alone knows.
 *
 * Both readings are ALLOWED TO BE UNKNOWN, and say so rather than guessing:
 * cadence 0 and speed -1 are the contract's own "no data" values. A phone
 * face-up on a bench reports no cadence, which is true; inventing a plausible
 * number there would have the coach nag someone who is resting.
 */
@SuppressLint("MissingPermission")
class PhoneMotion(private val context: Context) : SensorEventListener, LocationListener {

    companion object {
        private const val TAG = "X3TrainerMotion"
        /** Steps are counted over this window; shorter reads as noise. */
        private const val CADENCE_WINDOW_MS = 10_000L
        /** Steps remembered for the detector-based rate. */
        private const val STEP_MEMORY = 8
        /** ...and how far back they may be before they stop counting. */
        private const val STEP_WINDOW_MS = 6_000L
        /** No step in this long means standing still, not "same cadence". */
        private const val CADENCE_IDLE_MS = 6_000L
        /** A fix older than this is history, not a speed. */
        private const val FIX_STALE_MS = 12_000L
        private const val GPS_INTERVAL_MS = 1_000L
    }

    /** Steps per minute, 0 when unknown or standing still. */
    @Volatile var cadence: Int = 0; private set
    /** Metres per second, negative when unknown. */
    @Volatile var speedMps: Float = -1f; private set

    private var sensors: SensorManager? = null
    private var locations: LocationManager? = null

    // stepsAtWindowStart/windowStartedAt are touched only on the callback
    // thread. lastStepAt/fixAt are written there and READ from the publishing
    // thread — the BLE callback that carries each heart-rate notification —
    // so they carry the same @Volatile guarantee as the values they gate.
    // Without it a reader may observe a fresh reading beside a stale stamp and
    // discard a perfectly good sample, or never see fixAt move off zero at all.
    private var stepsAtWindowStart = -1L
    private var windowStartedAt = 0L
    @Volatile private var lastStepAt = 0L
    @Volatile private var fixAt = 0L

    /**
     * Safe to call again. A permission granted after the service started —
     * from the prompt still on screen, or from Settings mid-run — would
     * otherwise leave the source unregistered for the life of the service,
     * because nothing re-runs onCreate. SensorManager keys a registration by
     * listener+sensor and requestLocationUpdates replaces a duplicate request
     * for the same listener, so re-arming delivers nothing twice and leaks
     * nothing.
     */
    fun start() {
        if (sensors == null) startSteps()
        if (locations == null) startGps()
    }

    private fun has(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun startSteps() {
        startDetector()
        // ACTIVITY_RECOGNITION guards the step counter from API 29. Without it
        // registerListener quietly succeeds and never delivers, which is
        // indistinguishable from a wearer who is not moving.
        if (!has(Manifest.permission.ACTIVITY_RECOGNITION)) {
            Log.i(TAG, "no activity-recognition permission — cadence stays unknown")
            return
        }
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sensors = sm
        val counter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (counter == null) {
            Log.i(TAG, "no step counter on this phone — cadence stays unknown")
            return
        }
        sm.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun startGps() {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Log.i(TAG, "no location permission — speed stays unknown")
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locations = lm
        runCatching {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, this
            )
        }.onFailure { Log.w(TAG, "GPS unavailable: ${it.message}") }
    }

    fun stop() {
        runCatching { sensors?.unregisterListener(this) }
        runCatching { locations?.removeUpdates(this) }
        sensors = null; locations = null
        cadence = 0; speedMps = -1f
        // The window belongs to the run that just ended. Left in place, a
        // later re-arm computes its first rate across everything in between.
        stepsAtWindowStart = -1L; windowStartedAt = 0L; lastStepAt = 0L; fixAt = 0L
        stepTimes.clear()
    }

    // ── Cadence ──────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
            onStep(SystemClock.elapsedRealtime())
            return
        }
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        // The detector, when present, is the better answer — let it own the
        // number rather than having two sources fight over it.
        if (stepTimes.isNotEmpty()) { lastStepAt = SystemClock.elapsedRealtime(); return }
        // TYPE_STEP_COUNTER counts since boot, so a rate needs two readings
        // and the gap between them — the value itself means nothing here.
        val total = event.values.firstOrNull()?.toLong() ?: return
        val now = SystemClock.elapsedRealtime()
        if (stepsAtWindowStart < 0) {
            stepsAtWindowStart = total; windowStartedAt = now; lastStepAt = now
            return
        }
        // A PAUSE IS NOT SLOW WALKING. TYPE_STEP_COUNTER is an on-change
        // sensor: standing still delivers no events at all, so the window is
        // only ever rolled by a step arriving — and the window then spans the
        // entire pause. Divide seven steps by a minute of waiting at a
        // crossing and the wearer resumes at 180 spm while the coach is told
        // 6, confidently, for a full window. Restart the window instead: the
        // gap is not data about cadence, it is the absence of it.
        val restedTooLong = lastStepAt > 0 && now - lastStepAt > CADENCE_IDLE_MS
        if (total > stepsAtWindowStart) lastStepAt = now
        if (restedTooLong) {
            stepsAtWindowStart = total; windowStartedAt = now; cadence = 0
            return
        }
        val elapsed = now - windowStartedAt
        if (elapsed >= CADENCE_WINDOW_MS) {
            val steps = (total - stepsAtWindowStart).coerceAtLeast(0)
            cadence = (steps * 60_000L / elapsed).toInt().coerceIn(0, 300)
            stepsAtWindowStart = total; windowStartedAt = now
        }
    }

    /**
     * TYPE_STEP_DETECTOR fires once per step, as it happens.
     *
     * The counter this class started with is a running total, so a rate from
     * it is only ever an average over a whole window — ten seconds late to
     * every change of pace, which is exactly the "it does not match my actual
     * steps" complaint. Timing the gaps between individual steps gives the
     * real thing: a wearer walking at 100 spm reads 100 within two steps.
     *
     * The counter is kept as the fallback, because the detector is optional
     * hardware while the counter is nearly universal.
     */
    private fun startDetector() {
        val sm = sensors ?: (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
        val detector = sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return
        sensors = sm
        sm.registerListener(this, detector, SensorManager.SENSOR_DELAY_FASTEST)
    }

    /** Timestamps of the most recent steps, newest last. */
    private val stepTimes = ArrayDeque<Long>()

    private fun onStep(now: Long) {
        lastStepAt = now
        stepTimes.addLast(now)
        // A short memory keeps it responsive; anything older describes a pace
        // the wearer has already left behind.
        while (stepTimes.size > STEP_MEMORY) stepTimes.removeFirst()
        while (stepTimes.isNotEmpty() && now - stepTimes.first() > STEP_WINDOW_MS) {
            stepTimes.removeFirst()
        }
        // Two steps is one interval, which is the least that can define a
        // rate at all.
        if (stepTimes.size >= 2) {
            val span = stepTimes.last() - stepTimes.first()
            if (span > 0) {
                cadence = ((stepTimes.size - 1) * 60_000L / span).toInt().coerceIn(0, 300)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* not acted on */ }

    // ── Speed ────────────────────────────────────────────────────────────

    override fun onLocationChanged(location: Location) {
        // hasSpeed() is the honest test: a fix without a Doppler speed gives
        // getSpeed() == 0, which would read as "stopped" rather than "unknown"
        // and pull the coach's pace nudges toward a number nobody measured.
        if (location.hasSpeed()) {
            speedMps = location.speed
            fixAt = SystemClock.elapsedRealtime()
        }
    }

    @Deprecated("Required by LocationListener on older API levels")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
    override fun onProviderEnabled(provider: String) { }
    override fun onProviderDisabled(provider: String) {
        speedMps = -1f
    }

    /**
     * The readings as they stand, with anything gone stale reported as
     * unknown. Called on the publishing thread rather than updated by timer so
     * that "how old is this" is judged at the moment it is about to be sent.
     */
    fun sample(): Pair<Int, Float> {
        val now = SystemClock.elapsedRealtime()
        val liveCadence = if (lastStepAt > 0 && now - lastStepAt <= CADENCE_IDLE_MS) cadence else 0
        val liveSpeed = if (fixAt > 0 && now - fixAt <= FIX_STALE_MS) speedMps else -1f
        return liveCadence to liveSpeed
    }
}
