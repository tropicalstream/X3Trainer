package com.x3trainer.engine

import android.graphics.Color

/**
 * The standard five-zone model (% of max HR): Z1 <60, Z2 60-70, Z3 70-80,
 * Z4 80-90, Z5 90+. Zone colors follow the near-universal watch convention
 * (grey/blue/green/orange/red) — the telemetry line is tinted with these. No
 * pure black anywhere: black is transparent on the waveguide.
 */
object HrZones {

    val colors = intArrayOf(
        Color.rgb(150, 160, 175),  // Z1 recovery grey
        Color.rgb(80, 170, 255),   // Z2 easy blue
        Color.rgb(80, 220, 120),   // Z3 aerobic green
        Color.rgb(255, 170, 40),   // Z4 threshold orange
        Color.rgb(255, 70, 60),    // Z5 max red
    )

    fun zone(hr: Int, maxHr: Int): Int {
        if (hr <= 0 || maxHr <= 0) return 1
        val pct = hr * 100 / maxHr
        return when {
            pct < 60 -> 1
            pct < 70 -> 2
            pct < 80 -> 3
            pct < 90 -> 4
            else -> 5
        }
    }

    fun color(zone: Int): Int = colors[(zone - 1).coerceIn(0, 4)]
}
