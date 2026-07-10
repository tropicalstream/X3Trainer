package com.x3trainer

import android.content.Context
import android.os.Build

/** Persistent settings. RayNeo hardware detected by identity, not model (guide gotcha #24). */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("x3trainer", Context.MODE_PRIVATE)

    private val deviceText = listOf(
        Build.MODEL, Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.PRODUCT
    ).joinToString(" ").lowercase()

    private val isRayNeoX3 =
        "rayneo" in deviceText || "leiniao" in deviceText || "ffalcon" in deviceText ||
            ("x3" in deviceText && ("tcl" in deviceText || "falcon" in deviceText))

    init {
        if (isRayNeoX3 && !p.getBoolean("rayneoSbsV1", false)) {
            p.edit().putBoolean("sbs", true).putBoolean("rayneoSbsV1", true).apply()
        }
    }

    /** First-run health + moisture disclaimer accepted. */
    var disclaimerAccepted: Boolean
        get() = p.getBoolean("disclaimerOk", false)
        set(v) { p.edit().putBoolean("disclaimerOk", v).apply() }

    /**
     * 0 Demo, 1 HR Broadcast (standard BLE Heart Rate service — Apple Watch
     * via a broadcaster app like HeartCast, or any chest strap / sport watch
     * that broadcasts natively). The X3 Pro's consumer pairing is locked to
     * its companion phone, but standard HR broadcast is connectionless and
     * needs no pairing; whether app-level BLE scanning works on the glasses
     * is unverified — BleSource doubles as the on-device test.
     */
    var dataSource: Int
        get() = p.getInt("dataSource", 0)
        set(v) { p.edit().putInt("dataSource", ((v % 2) + 2) % 2).apply() }

    val dataSourceLabel: String
        get() = if (dataSource == 1) "HR Broadcast (BLE)" else "Demo"

    /** Max heart rate used for zone math. */
    var maxHr: Int
        get() = p.getInt("maxHr", 185)
        set(v) { p.edit().putInt("maxHr", v.coerceIn(120, 220)).apply() }

    /** Target running cadence, steps per minute. */
    var targetCadence: Int
        get() = p.getInt("targetCadence", 175)
        set(v) { p.edit().putInt("targetCadence", v.coerceIn(120, 220)).apply() }

    /** Target pace in seconds per km (delta velocity is measured against this). */
    var targetPaceSecPerKm: Int
        get() = p.getInt("targetPace", 330)
        set(v) { p.edit().putInt("targetPace", v.coerceIn(150, 900)).apply() }

    val targetVelocityMps: Float get() = 1000f / targetPaceSecPerKm

    val targetPaceLabel: String
        get() = "%d:%02d/km".format(targetPaceSecPerKm / 60, targetPaceSecPerKm % 60)

    /** Target HR zone (1..5) the coach steers you toward in free modes. */
    var targetZone: Int
        get() = p.getInt("targetZone", 3)
        set(v) { p.edit().putInt("targetZone", v.coerceIn(1, 5)).apply() }

    /** Coach chattiness: 0 off, 1 low, 2 normal, 3 high. */
    var coachLevel: Int
        get() = p.getInt("coachLevel", 2)
        set(v) { p.edit().putInt("coachLevel", v.coerceIn(0, 3)).apply() }

    val coachLevelLabel: String
        get() = when (coachLevel) { 0 -> "Off"; 1 -> "Low"; 3 -> "High"; else -> "Normal" }

    var voiceVolume: Int
        get() = p.getInt("voiceVol", 9)
        set(v) { p.edit().putInt("voiceVol", v.coerceIn(0, 10)).apply() }

    var soundVolume: Int
        get() = p.getInt("sndVol", 7)
        set(v) { p.edit().putInt("sndVol", v.coerceIn(0, 10)).apply() }

    // --- timer configuration ---

    var countdownMin: Int
        get() = p.getInt("cdMin", 20)
        set(v) { p.edit().putInt("cdMin", v.coerceIn(1, 120)).apply() }

    var intervalWorkSec: Int
        get() = p.getInt("intWork", 60)
        set(v) { p.edit().putInt("intWork", v.coerceIn(10, 600)).apply() }

    var intervalRestSec: Int
        get() = p.getInt("intRest", 30)
        set(v) { p.edit().putInt("intRest", v.coerceIn(5, 300)).apply() }

    var intervalRounds: Int
        get() = p.getInt("intRounds", 8)
        set(v) { p.edit().putInt("intRounds", v.coerceIn(1, 30)).apply() }

    var emomMin: Int
        get() = p.getInt("emomMin", 10)
        set(v) { p.edit().putInt("emomMin", v.coerceIn(1, 60)).apply() }

    var amrapMin: Int
        get() = p.getInt("amrapMin", 12)
        set(v) { p.edit().putInt("amrapMin", v.coerceIn(1, 90)).apply() }

    /** Last selected timer mode ordinal. */
    var timerMode: Int
        get() = p.getInt("timerMode", 0)
        set(v) { p.edit().putInt("timerMode", v).apply() }

    var swipeSens: Float
        get() = p.getFloat("swipeSens", 1.0f)
        set(v) { p.edit().putFloat("swipeSens", v.coerceIn(0.4f, 2.5f)).apply() }

    var sbs: Boolean
        get() = p.getBoolean("sbs", isRayNeoX3)
        set(v) { p.edit().putBoolean("sbs", v).apply() }

    /** Restore every preference to its default (keeps the disclaimer acceptance). */
    fun resetSettings() {
        p.edit()
            .remove("dataSource").remove("maxHr").remove("targetCadence")
            .remove("targetPace").remove("targetZone").remove("coachLevel")
            .remove("voiceVol").remove("sndVol")
            .remove("cdMin").remove("intWork").remove("intRest").remove("intRounds")
            .remove("emomMin").remove("amrapMin").remove("timerMode")
            .remove("swipeSens").remove("sbs")
            .apply()
    }
}
