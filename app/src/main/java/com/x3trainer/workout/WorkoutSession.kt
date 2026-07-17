package com.x3trainer.workout

import com.x3trainer.audio.Sfx
import com.x3trainer.engine.TrainerHost

/**
 * One guided mat workout. The engine ticks this on the main thread; the GL
 * renderer reads it through snapshot() — every public mutator and the
 * snapshot are synchronized so the render thread always sees a coherent
 * frame. Voice fires only at phase boundaries (never mid-rep), keeping the
 * animation thread free of surprise audio work. Live vitals lines (heart
 * rate, zone, calories) are spoken through dynamic TTS because they embed
 * live numbers; everything else uses the pre-generated coach voice.
 *
 * Flow: GETSET (5 s, coach demos the first move slowly) -> WORK -> REST
 * (coach previews the next move) -> … -> DONE (victory pose + summary,
 * saved to the workout log).
 */
class WorkoutSession(
    private val host: TrainerHost,
    private val log: WorkoutLog,
    private val weightKg: () -> Int,
) {

    companion object {
        const val OFF = 0; const val GETSET = 1; const val WORK = 2; const val REST = 3; const val DONE = 4
        private const val GETSET_SEC = 5f
        private const val PREVIEW_TEMPO = 0.55f
    }

    var active = false; private set

    private var program: Program = Programs.ALL[0]
    private var programIdx = 0
    private var level = 1
    private var phase = OFF
    private var stepIdx = 0
    private var phaseT = 0f            // seconds into the phase
    private var u = 0f                 // animation phase 0..1
    private var reps = 0
    private var paused = false
    private var totalT = 0f
    private var totalReps = 0
    private var lastBeepSec = -1

    // Live vitals across the session (fed by the telemetry source).
    private var hr = 0
    private var zone = 0
    private var hrSum = 0L
    private var hrN = 0
    private var peakHr = 0
    private var restVitalsToggle = false
    private var doneKcal = 0
    private var doneStreak = 0
    private var doneSessions = 0
    private var doneLevelUp = false

    private fun step() = program.steps[stepIdx]
    private fun exId() = step().ex[level]
    private fun repTarget() = step().reps?.get(level) ?: 0
    private fun secTarget() = step().secs?.get(level) ?: 0
    private fun timed() = step().secs != null
    private fun tempo() = Levels.TEMPO[level]

    @Synchronized
    fun start(pIdx: Int, lvl: Int) {
        programIdx = pIdx.coerceIn(0, Programs.ALL.size - 1)
        program = Programs.ALL[programIdx]
        level = lvl.coerceIn(0, 2)
        stepIdx = 0; reps = 0; totalReps = 0; totalT = 0f
        phase = GETSET; phaseT = 0f; u = 0f; paused = false; lastBeepSec = -1
        hrSum = 0; hrN = 0; peakHr = 0; restVitalsToggle = false; doneLevelUp = false
        active = true
        host.say("wk_ready")
        if (program.met >= 5f) host.say("wk_warmup")
    }

    @Synchronized
    fun end() {
        if (active && phase != DONE) host.say("wk_end_early")
        active = false; phase = OFF
    }

    @Synchronized
    fun togglePause() {
        if (!active || phase == DONE) return
        paused = !paused
        host.sound(if (paused) Sfx.PAUSE else Sfx.START)
        host.say(if (paused) "timer_paused" else "wk_resume")
    }

    @Synchronized fun isPaused() = paused
    @Synchronized fun isDone() = phase == DONE

    fun pause() { synchronized(this) { if (active && phase != DONE && !paused) { paused = true } } }

    /** Double-tap while running: jump to the next exercise. */
    @Synchronized
    fun skip() {
        if (!active || phase == DONE || paused) return
        host.sound(Sfx.CONFIRM)
        host.say("wk_skip", urgent = true)
        advance(fromSkip = true)
    }

    @Synchronized
    fun update(dt: Float, liveHr: Int, liveZone: Int) {
        if (!active || paused || phase == OFF) return
        hr = liveHr; zone = liveZone
        if (hr > 0 && phase != DONE) {
            hrSum += hr; hrN++
            if (hr > peakHr) peakHr = hr
        }
        if (phase != DONE) { totalT += dt; phaseT += dt }
        when (phase) {
            GETSET -> {
                u += dt * PREVIEW_TEMPO / Exercises.ALL[exId()].cycleSec
                countdownBeeps(GETSET_SEC)
                // Hold the demo until the intro line finishes so the first
                // exercise cue isn't cut off by it (capped so it can't stall).
                if (phaseT >= GETSET_SEC && (!host.voiceBusy() || phaseT > GETSET_SEC + 6f)) startWork()
            }
            WORK -> {
                val ex = Exercises.ALL[exId()]
                u += dt * tempo() / ex.cycleSec
                if (timed()) {
                    countdownBeeps(secTarget().toFloat())
                    if (!saidHalf && secTarget() >= 25 && phaseT >= secTarget() / 2f) {
                        saidHalf = true
                        host.say("halfway")
                    }
                    if (phaseT >= secTarget()) { totalReps++; advance() }
                } else {
                    if (u >= 1f) {
                        u -= 1f
                        reps++
                        totalReps++
                        host.sound(Sfx.TICK, 1f + 0.25f * (reps.toFloat() / repTarget()), 0.9f)
                        if (reps >= repTarget()) advance()
                    }
                }
            }
            REST -> {
                u += dt * PREVIEW_TEMPO / Exercises.ALL[exId()].cycleSec
                countdownBeeps(program.restSec[level].toFloat())
                if (phaseT >= program.restSec[level]) startWork()
            }
            DONE -> u += dt / Exercises.ALL[Exercises.VICTORY].cycleSec
        }
    }

    /** Halfway encouragement fires once per long timed hold. */
    private var saidHalf = false

    private fun countdownBeeps(total: Float) {
        val left = (total - phaseT).toInt()
        if (left in 0..2 && left != lastBeepSec) {
            lastBeepSec = left
            host.sound(Sfx.BEEP, 1f + (2 - left) * 0.1f, 0.8f)
        }
    }

    private fun startWork() {
        phase = WORK; phaseT = 0f; u = 0f; reps = 0; lastBeepSec = -1; saidHalf = false
        host.sound(Sfx.GO)
        host.say("cue_" + Exercises.ALL[exId()].key)
    }

    private fun advance(fromSkip: Boolean = false) {
        if (stepIdx >= program.steps.size - 1) {
            finish()
            return
        }
        stepIdx++
        phase = REST; phaseT = 0f; u = 0f; lastBeepSec = -1
        if (!fromSkip) {
            host.sound(Sfx.DING)
            host.say(if (stepIdx == program.steps.size - 1) "wk_last" else "wk_rest")
            // Alternate rests: the coach reads your live vitals back to you.
            restVitalsToggle = !restVitalsToggle
            if (restVitalsToggle && hr > 0) {
                val comment = when {
                    zone >= 4 -> "Let it come down before we go again."
                    zone == 3 -> "Right in the working zone."
                    else -> "Nice and controlled."
                }
                host.sayLive("Heart rate $hr, zone $zone. $comment")
            }
        }
    }

    private fun finish() {
        phase = DONE; phaseT = 0f; u = 0f
        host.sound(Sfx.FANFARE)
        host.say("wk_done")

        // Save the session and speak a personalized summary with live vitals.
        val avgHr = if (hrN > 0) (hrSum / hrN).toInt() else 0
        doneKcal = (program.met * 3.5f * weightKg() / 200f * (totalT / 60f)).toInt()
        log.add(programIdx, level, totalT.toInt(), totalReps, avgHr, peakHr, doneKcal)
        doneStreak = log.streakDays()
        doneSessions = log.totalSessions()
        doneLevelUp = level < 2 && log.completions(programIdx, level) >= 3

        val mins = (totalT / 60f).toInt().coerceAtLeast(1)
        val sb = StringBuilder("That's $mins minutes and about $doneKcal calories.")
        if (avgHr > 0) sb.append(" Average heart rate $avgHr, peak $peakHr.")
        if (doneStreak >= 2) sb.append(" You're on a $doneStreak day streak!")
        if (doneLevelUp) sb.append(" You've mastered this level — try ${Levels.NAMES[level + 1].lowercase()} next time!")
        host.sayLive(sb.toString())
    }

    // ------------------------------------------------------------ snapshot

    /** Mutable value bag the GL renderer owns and refreshes once per frame. */
    class Snap {
        var active = false
        var phase = OFF
        var paused = false
        var exId = Exercises.IDLE       // what the coach is performing
        var u = 0f
        var reps = 0
        var repTarget = 0
        var timed = false
        var secsLeft = 0
        var stepIdx = 0
        var stepCount = 0
        var programName = ""
        var levelName = ""
        var curName = ""                // exercise being worked / previewed
        var totalSec = 0
        var totalReps = 0
        var countdown = 0               // GETSET seconds remaining, else 0
        var restLeft = 0                // REST seconds remaining, else 0
        // DONE-screen stats (valid when phase == DONE)
        var kcal = 0
        var avgHr = 0
        var peakHr = 0
        var streak = 0
        var sessions = 0
        var levelUp = false
    }

    @Synchronized
    fun snapshot(s: Snap) {
        s.active = active
        s.phase = phase
        s.paused = paused
        s.u = u
        s.stepIdx = stepIdx
        s.stepCount = program.steps.size
        s.programName = program.name
        s.levelName = Levels.NAMES[level]
        s.totalSec = totalT.toInt()
        s.totalReps = totalReps
        s.countdown = 0; s.restLeft = 0; s.secsLeft = 0
        when (phase) {
            DONE -> { s.exId = Exercises.VICTORY; s.curName = "" }
            OFF -> { s.exId = Exercises.IDLE; s.curName = "" }
            else -> {
                s.exId = exId()
                s.curName = Exercises.ALL[exId()].name
                when (phase) {
                    GETSET -> s.countdown = ((GETSET_SEC - phaseT).toInt() + 1).coerceAtLeast(1)
                    REST -> s.restLeft = (program.restSec[level] - phaseT).toInt() + 1
                    WORK -> if (timed()) s.secsLeft = (secTarget() - phaseT).toInt() + 1
                }
            }
        }
        s.reps = reps
        s.repTarget = repTarget()
        s.timed = timed()
        s.kcal = doneKcal
        s.avgHr = if (hrN > 0) (hrSum / hrN).toInt() else 0
        s.peakHr = peakHr
        s.streak = doneStreak
        s.sessions = doneSessions
        s.levelUp = doneLevelUp
    }
}
