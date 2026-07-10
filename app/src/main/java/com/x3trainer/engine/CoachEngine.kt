package com.x3trainer.engine

import com.x3trainer.SettingsStore
import kotlin.random.Random

/**
 * The coaching brain — a blend of the best habits of the popular apps, all
 * delivered as VOICE ONLY (the phrase ids map to fish.audio clips):
 *
 *  - zone guidance vs a target zone (Polar/Garmin style)
 *  - cadence nudges toward a target turnover (running-dynamics style)
 *  - pace/delta-velocity nudges vs target pace (structured-workout style)
 *  - interval work/rest calls + final-round push (Peloton/HIIT style)
 *  - time milestones and celebrations (Nike Run Club style)
 *  - hydration reminders on a steady cadence
 *  - hard-safety: sustained Z5 warning
 *
 * Every rule has its own cooldown so the coach never nags, and one global
 * gap keeps phrases spread out. The chattiness setting scales all of it.
 */
class CoachEngine(private val store: SettingsStore, private val speak: (id: String, urgent: Boolean) -> Unit) {

    private val rng = Random(System.nanoTime())
    private var lastSpokeAt = -999f
    private val lastByRule = HashMap<String, Float>()
    private var t = 0f

    private var z5Since = -1f
    private var announcedHalf = false
    var celebration: String? = null; private set   // center-view text, brief
    private var celebrationUntil = 0f

    /** Global minimum gap between any two phrases, scaled by chattiness. */
    private val globalGap: Float
        get() = when (store.coachLevel) { 1 -> 45f; 3 -> 14f; else -> 25f }

    fun reset() {
        t = 0f
        lastSpokeAt = -999f
        lastByRule.clear()
        z5Since = -1f
        announcedHalf = false
        celebration = null
    }

    private fun can(rule: String, cooldown: Float): Boolean {
        if (store.coachLevel == 0) return false
        if (t - lastSpokeAt < globalGap) return false
        if (t - (lastByRule[rule] ?: -999f) < cooldown) return false
        return true
    }

    private fun fire(rule: String, id: String, urgent: Boolean = false) {
        lastByRule[rule] = t
        lastSpokeAt = t
        speak(id, urgent)
    }

    /** Safety and event phrases skip the chattiness gate but keep per-rule cooldowns. */
    private fun canUrgent(rule: String, cooldown: Float): Boolean =
        t - (lastByRule[rule] ?: -999f) >= cooldown

    fun celebrate(text: String, showFor: Float = 2.5f) {
        celebration = text
        celebrationUntil = t + showFor
    }

    /**
     * Tick with live state. timerRunning gates most advice: the coach talks
     * during an active session, not while you stand at a crosswalk.
     */
    fun update(dt: Float, timerRunning: Boolean, elapsed: Float, hr: Int, zone: Int, cadence: Int, deltaV: Float) {
        t += dt
        if (celebration != null && t > celebrationUntil) celebration = null

        // --- safety first: sustained Z5 (always on, even with coach Off) ---
        if (zone >= 5 && hr > 0) {
            if (z5Since < 0f) z5Since = t
            if (t - z5Since > 75f && canUrgent("z5", 90f)) {
                fire("z5", "z5_warning", urgent = true)
            }
        } else z5Since = -1f

        if (!timerRunning) return

        // --- warm-up note in the opening minutes ---
        if (elapsed in 20f..25f && can("warmup", 9999f)) { fire("warmup", "warmup"); return }

        // --- time milestones (celebrations may also show center text) ---
        if (elapsed in 300f..305f && canUrgent("m5", 9999f)) {
            lastByRule["m5"] = t; lastSpokeAt = t; speak("five_min", false); return
        }
        if (elapsed in 600f..605f && canUrgent("m10", 9999f)) {
            lastByRule["m10"] = t; lastSpokeAt = t; speak("ten_min", false)
            celebrate("10 MINUTES!"); return
        }

        // --- hydration every ~15 min ---
        if (elapsed > 0f && elapsed.toInt() % 900 in 0..4 && elapsed > 100f && can("hydrate", 600f)) {
            fire("hydrate", "hydrate"); return
        }

        // --- zone guidance toward the target zone ---
        if (hr > 0 && can("zone", 40f)) {
            val target = store.targetZone
            when {
                zone < target - 0 && zone < target -> { fire("zone", "zone_up"); return }
                zone > target && zone < 5 -> { fire("zone", "zone_down"); return }
                zone == target && rng.nextFloat() < 0.35f -> { fire("zone", "zone_perfect"); return }
            }
        }

        // --- cadence nudges when 10+ spm off target ---
        if (cadence > 0 && can("cadence", 55f)) {
            val diff = cadence - store.targetCadence
            if (diff <= -10) { fire("cadence", "cadence_up"); return }
            if (diff >= 12) { fire("cadence", "cadence_down"); return }
        }

        // --- delta-velocity nudges vs target pace ---
        if (deltaV > -90f && can("pace", 60f)) {
            if (deltaV < -0.45f) { fire("pace", "pace_up"); return }
            if (deltaV > 0.6f) { fire("pace", "pace_down"); return }
        }

        // --- pure motivation, frequency by chattiness ---
        val motivateEvery = when (store.coachLevel) { 1 -> 210f; 3 -> 75f; else -> 130f }
        if (can("motivate", motivateEvery) && rng.nextFloat() < 0.02f) {
            fire("motivate", "motivate_${1 + rng.nextInt(10)}")
        }
    }
}
