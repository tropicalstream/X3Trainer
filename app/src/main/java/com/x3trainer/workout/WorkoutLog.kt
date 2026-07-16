package com.x3trainer.workout

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent workout history — the industry-standard progress loop: every
 * completed session is saved (date, program, level, duration, reps, heart
 * rate, estimated calories), driving streaks, weekly counts, lifetime totals,
 * and the level-up suggestion (3 completions of a program at a level ->
 * suggest the next level). Stored as JSON in SharedPreferences, newest first,
 * capped at 120 entries.
 */
class WorkoutLog(context: Context) {

    private val p = context.getSharedPreferences("x3trainer_log", Context.MODE_PRIVATE)

    class Entry(
        val epochDay: Long,
        val program: Int,
        val level: Int,
        val secs: Int,
        val reps: Int,
        val avgHr: Int,
        val peakHr: Int,
        val kcal: Int,
    )

    private val entries = ArrayList<Entry>()

    init {
        runCatching {
            val arr = JSONArray(p.getString("history", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(
                    Entry(
                        o.getLong("d"), o.getInt("p"), o.getInt("l"), o.getInt("s"),
                        o.getInt("r"), o.optInt("ah"), o.optInt("ph"), o.optInt("k")
                    )
                )
            }
        }
    }

    private fun today(): Long = System.currentTimeMillis() / 86_400_000L

    @Synchronized
    fun add(program: Int, level: Int, secs: Int, reps: Int, avgHr: Int, peakHr: Int, kcal: Int) {
        entries.add(0, Entry(today(), program, level, secs, reps, avgHr, peakHr, kcal))
        while (entries.size > 120) entries.removeAt(entries.size - 1)
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("d", e.epochDay).put("p", e.program).put("l", e.level)
                    .put("s", e.secs).put("r", e.reps)
                    .put("ah", e.avgHr).put("ph", e.peakHr).put("k", e.kcal)
            )
        }
        p.edit().putString("history", arr.toString()).apply()
    }

    @Synchronized fun totalSessions(): Int = entries.size

    /** Consecutive days trained, counting back from today (or yesterday). */
    @Synchronized
    fun streakDays(): Int {
        if (entries.isEmpty()) return 0
        val days = entries.map { it.epochDay }.toSortedSet()
        var day = today()
        if (day !in days) day-- // an unbroken streak survives until tomorrow
        var n = 0
        while (day in days) { n++; day-- }
        return n
    }

    /** Sessions in the last 7 days, including today. */
    @Synchronized
    fun weekCount(): Int {
        val cutoff = today() - 6
        return entries.count { it.epochDay >= cutoff }
    }

    /** How many times this program has been completed at this level. */
    @Synchronized
    fun completions(program: Int, level: Int): Int =
        entries.count { it.program == program && it.level == level }
}
