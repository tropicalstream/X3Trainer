package com.x3trainer

import android.content.Context
import android.os.Build
import com.x3trainer.engine.ExerciseMode
import kotlin.math.roundToInt

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

    /** 0 Demo, 1 live watch sensor through the paired Galaxy phone. Live is the default. */
    var dataSource: Int
        get() = p.getInt("dataSource", 1)
        set(v) { p.edit().putInt("dataSource", ((v % 2) + 2) % 2).apply() }

    val dataSourceLabel: String
        get() = if (dataSource == 1) "Watch via phone" else "Demo (simulated)"

    /** Max heart rate used for zone math. */
    var maxHr: Int
        get() = p.getInt("maxHr", 185)
        set(v) { p.edit().putInt("maxHr", v.coerceIn(120, 220)).apply() }

    /** Target running cadence, steps per minute. */
    /**
     * What the wearer is doing. Every target below is scoped to it, so
     * switching to a walk and back does not overwrite a runner's numbers.
     */
    var exerciseMode: ExerciseMode
        get() = ExerciseMode.of(p.getInt("exerciseMode", ExerciseMode.RUN.ordinal))
        set(v) { p.edit().putInt("exerciseMode", v.ordinal).apply() }

    /** Target cadence in steps per minute, per mode. */
    var targetCadence: Int
        get() = p.getInt("cadence_${exerciseMode.key}", exerciseMode.defaultCadence)
        set(v) {
            p.edit().putInt("cadence_${exerciseMode.key}", v.coerceIn(40, 220)).apply()
        }

    /** Target pace in seconds per km (delta velocity is measured against this). */
    var targetPaceSecPerKm: Int
        get() = p.getInt("pace_${exerciseMode.key}", exerciseMode.defaultPaceSecPerKm)
        set(v) {
            // 90 s/km is ~40 km/h, fast for a bicycle and impossible on foot;
            // the floor has to clear cycling now that cycling is a mode.
            p.edit().putInt("pace_${exerciseMode.key}", v.coerceIn(90, 1200)).apply()
        }

    val targetVelocityMps: Float get() = 1000f / targetPaceSecPerKm

    /**
     * Imperial display. STORAGE STAYS METRIC, always — only the reading
     * changes. Converting the stored value on each toggle would round it a
     * little every time, so a wearer flipping back and forth would watch
     * their own body weight drift.
     */
    var imperial: Boolean
        get() = p.getBoolean("imperial", false)
        set(v) { p.edit().putBoolean("imperial", v).apply() }

    val unitsLabel: String get() = if (imperial) "Imperial" else "Metric"

    /**
     * The target, read the way this activity is normally read: minutes per
     * distance on foot, plain speed on a bike. Both describe the same stored
     * number — cyclists simply do not think in minutes per kilometre.
     */
    val targetPaceLabel: String
        get() {
            if (!exerciseMode.paceOriented) {
                val kmh = 3600f / targetPaceSecPerKm
                return if (imperial) "%.0f mph".format(kmh / KM_PER_MILE)
                else "%.0f km/h".format(kmh)
            }
            val secs = if (imperial) (targetPaceSecPerKm * KM_PER_MILE).toInt()
            else targetPaceSecPerKm
            val unit = if (imperial) "/mi" else "/km"
            return "%d:%02d%s".format(secs / 60, secs % 60, unit)
        }

    /** Body weight as the wearer reads it — kilograms or pounds. */
    val bodyWeightLabel: String
        get() = if (imperial) "${(bodyWeightKg * LB_PER_KG).roundToInt()} lb"
        else "$bodyWeightKg kg"

    /** A speed in m/s, rendered in the wearer's units. Cadence stays spm in
     *  both: steps per minute is what every running app uses and there is no
     *  imperial equivalent to convert to. */
    fun speedText(mps: Float): String =
        if (imperial) "%.1fmph".format(mps * MPH_PER_MPS) else "%.1fm/s".format(mps)

    /** The same, without the unit — for the "actual > target" pairing. */
    fun speedValue(mps: Float): String =
        if (imperial) "%.1f".format(mps * MPH_PER_MPS) else "%.1f".format(mps)

    /** Calorie goal for one session; the HUD counts towards it. */
    var targetKcal: Int
        get() = p.getInt("targetKcal", 300)
        set(v) { p.edit().putInt("targetKcal", v.coerceIn(50, 2000)).apply() }

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

    // --- mat-coach workout ---

    /** Body weight in kg — used only for the workout calorie estimate. */
    var bodyWeightKg: Int
        get() = p.getInt("bodyKg", 75)
        set(v) { p.edit().putInt("bodyKg", v.coerceIn(35, 200)).apply() }

    /** Last selected workout program index. */
    var workoutProgram: Int
        get() = p.getInt("wkProgram", 0)
        set(v) { p.edit().putInt("wkProgram", v.coerceAtLeast(0)).apply() }

    /** Last selected workout level: 0 beginner, 1 intermediate, 2 advanced. */
    var workoutLevel: Int
        get() = p.getInt("wkLevel", 0)
        set(v) { p.edit().putInt("wkLevel", v.coerceIn(0, 2)).apply() }

    var sbs: Boolean
        get() = p.getBoolean("sbs", isRayNeoX3)
        set(v) { p.edit().putBoolean("sbs", v).apply() }

    /** Restore every preference to its default (keeps the disclaimer acceptance). */
    fun resetSettings() {
        p.edit()
            .remove("dataSource").remove("maxHr").remove("targetCadence")
            .remove("targetPace").remove("targetZone").remove("coachLevel")
            .remove("targetKcal").remove("imperial")
            .remove("voiceVol").remove("sndVol")
            .remove("cdMin").remove("intWork").remove("intRest").remove("intRounds")
            .remove("emomMin").remove("amrapMin").remove("timerMode")
            .remove("swipeSens").remove("sbs")
            .remove("wkProgram").remove("wkLevel").remove("bodyKg")
            .apply()
    }

    private companion object {
        /** Exactly, by definition of the international mile. */
        const val KM_PER_MILE = 1.609344f
        const val LB_PER_KG = 2.2046226f
        const val MPH_PER_MPS = 2.2369363f
    }
}
