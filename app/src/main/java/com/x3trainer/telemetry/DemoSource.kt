package com.x3trainer.telemetry

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.sin
import kotlin.random.Random

/**
 * Simulated workout so the HUD, zones, and coach can be exercised with no
 * sensor: HR ramps up through the zones in slow waves, cadence and speed
 * wander around realistic run values.
 */
class DemoSource : TelemetrySource {

    private val handler = Handler(Looper.getMainLooper())
    private var listener: TelemetrySource.Listener? = null
    private var startAt = 0L
    private val rng = Random(System.nanoTime())

    private val tick = object : Runnable {
        override fun run() {
            val l = listener ?: return
            val t = (SystemClock.uptimeMillis() - startAt) / 1000f
            // Slow effort wave (7 min period) + wobble: covers Z1..Z5 over time.
            val effort = 0.45f + 0.38f * sin(t / 210f * 6.2832f - 1.5708f) + 0.06f * sin(t / 23f * 6.2832f)
            val hr = (95 + effort.coerceIn(0f, 1.1f) * 95).toInt() + rng.nextInt(-2, 3)
            val cad = (168 + 14 * sin(t / 47f * 6.2832f)).toInt() + rng.nextInt(-2, 3)
            val spd = 2.9f + 0.8f * sin(t / 63f * 6.2832f) + rng.nextFloat() * 0.1f
            l.onSample(TelemetrySample(hr, cad, spd, SystemClock.uptimeMillis()))
            handler.postDelayed(this, 1000L)
        }
    }

    override fun start(listener: TelemetrySource.Listener) {
        this.listener = listener
        startAt = SystemClock.uptimeMillis()
        listener.onStatus("DEMO")
        handler.post(tick)
    }

    override fun stop() {
        handler.removeCallbacks(tick)
        listener = null
    }
}
