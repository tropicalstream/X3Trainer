package com.x3trainer.telemetry

/** One normalized reading from whatever device is feeding us. */
data class TelemetrySample(
    val hr: Int,             // beats per minute, 0 = unknown
    val cadence: Int,        // steps per minute, 0 = unknown
    val speedMps: Float,     // meters per second, <0 = unknown
    val atMs: Long,          // SystemClock.uptimeMillis of the reading
)

/**
 * A live telemetry feed (watch, strap, or simulator). Implementations push
 * samples and status through the listener from any thread; the engine
 * normalizes and consumes on the render tick.
 */
interface TelemetrySource {
    interface Listener {
        fun onSample(s: TelemetrySample)
        /** Human-short status for the HUD, e.g. "SCANNING", "CONNECTED". */
        fun onStatus(status: String)
        /** Device raised a problem the athlete must look at (triggers red flash). */
        fun onDeviceWarning(message: String)
    }

    fun start(listener: Listener)
    fun stop()
}
