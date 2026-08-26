package com.x3trainer.engine

import android.os.SystemClock
import com.x3trainer.SettingsStore
import com.x3trainer.audio.Sfx
import com.x3trainer.telemetry.TelemetrySample
import com.x3trainer.telemetry.TelemetrySource
import com.x3trainer.workout.Programs
import com.x3trainer.workout.WorkoutLog
import com.x3trainer.workout.WorkoutSession

enum class AppState { DISCLAIMER, HUD, SETTINGS, WORKOUT_MENU, WORKOUT }

/** What the engine needs from the Activity. */
interface TrainerHost {
    fun applySettings()
    fun sound(id: Int, pitch: Float = 1f, vol: Float = 1f)
    fun say(id: String, urgent: Boolean = false)
    /** Speak dynamic text (live vitals, stats) that can't be pre-generated. */
    fun sayLive(text: String)
    /** True while the coach is talking (or has a line queued) — for pacing. */
    fun voiceBusy(): Boolean
    /** (Re)build the telemetry source for the current settings choice. */
    fun rebindTelemetry()
    /** Show/hide the GL mat-coach surface (true while state == WORKOUT). */
    fun workoutSurface(active: Boolean)
}

/**
 * Core state machine. Layout contract (rendered by Renderer):
 *   top 10%    — sports timer        (tap start/pause, double-tap reset,
 *                                     swipe+confirm switches mode)
 *   center 80% — EMPTY sightline     (only transient celebrations/warnings)
 *   bottom 10% — telemetry string    (tinted by current HR zone)
 * Triple tap opens settings.
 */
class Trainer(
    val store: SettingsStore,
    val host: TrainerHost,
    val log: WorkoutLog,
) : TimerEvents, TelemetrySource.Listener {

    var state = AppState.DISCLAIMER; private set
    val timer = SportsTimer(store, this)
    val coach = CoachEngine(store) { id, urgent -> host.say(id, urgent) }
    val menu = TrainerMenu(this, store)
    val workout = WorkoutSession(host, log) { store.bodyWeightKg }

    // Workout picker selection (state == WORKOUT_MENU).
    var wkProgram = 0; private set
    var wkLevel = 1; private set

    // --- live telemetry (normalized) ---
    var hr = 0; private set
    var cadence = 0; private set
    var speedMps = -1f; private set
    var sourceStatus = ""; private set
    private var lastSampleAt = 0L

    val zone: Int get() = HrZones.zone(hr, store.maxHr)
    val deltaV: Float get() = if (speedMps < 0f) -99f else speedMps - store.targetVelocityMps

    /**
     * Energy burned so far this session, in kilocalories.
     *
     * Heart rate is used when there is one, through the Keytel relation —
     * it responds to how hard the WEARER is actually working, which a clock
     * cannot. Age is not asked for anywhere in this app, so it is inferred
     * from the max-heart-rate setting the wearer already tunes (the usual
     * 220-minus-age), which keeps the estimate honest without adding a
     * second thing to configure.
     *
     * With no pulse it falls back to a moderate MET, because "unknown" is
     * not a useful thing to show where a target is being counted towards,
     * and a clock plus a body weight is still a defensible estimate of
     * general exercise.
     */
    var kcal: Float = 0f
        private set

    private fun burnPerMinute(): Float {
        val kg = store.bodyWeightKg.toFloat()
        if (hr > 0) {
            val age = (220 - store.maxHr).coerceIn(15, 90).toFloat()
            // Keytel et al., male coefficients, clamped at zero: at rest the
            // formula goes negative and a workout cannot un-burn calories.
            val perMin = (-55.0969f + 0.6309f * hr + 0.1988f * kg + 0.2017f * age) / 4.184f
            return perMin.coerceAtLeast(0f)
        }
        // No pulse: fall back to the activity's own intensity rather than
        // one number for everything — a walk and a run are not the same burn.
        return store.exerciseMode.met * 3.5f * kg / 200f
    }

    // --- transient center-view events ---
    var warningText: String? = null; private set
    private var warningUntil = 0f

    // --- swipe+confirm timer mode switch ---
    var pendingMode: TimerMode? = null; private set
    var pendingUntil = 0f; private set

    var time = 0f; private set

    fun boot() {
        state = if (store.disclaimerAccepted) AppState.HUD else AppState.DISCLAIMER
        timer.loadMode()
        if (state == AppState.HUD) {
            host.rebindTelemetry()
            host.say("welcome")
        }
    }

    fun update(dt: Float) {
        time += dt
        timer.update(dt)
        if (state == AppState.HUD) {
            // Only while the clock runs: a paused timer is a rest, and a
            // calorie counter that climbs during rest makes the target
            // meaningless.
            if (timer.running) kcal += burnPerMinute() * dt / 60f
            coach.update(dt, timer.running, timer.elapsed, hr, zone, cadence, deltaV)
        }
        if (state == AppState.WORKOUT) workout.update(dt, hr, zone)
        if (warningText != null && time > warningUntil) warningText = null
        if (pendingMode != null && time > pendingUntil) {
            pendingMode = null
            host.sound(Sfx.CANCEL)
        }
        // Stale telemetry on a live source counts as a device problem.
        if (state == AppState.HUD && store.dataSource != 0 && lastSampleAt > 0 &&
            SystemClock.uptimeMillis() - lastSampleAt > 15000 && warningText == null
        ) {
            onDeviceWarning("NO DATA - CHECK DEVICE")
        }
    }

    // ---------------------------------------------------------------- input

    fun tap() {
        when (state) {
            AppState.DISCLAIMER -> {
                store.disclaimerAccepted = true
                state = AppState.HUD
                host.sound(Sfx.SELECT)
                host.rebindTelemetry()
                host.say("welcome")
            }
            AppState.HUD -> {
                val pm = pendingMode
                if (pm != null) {
                    // Confirming the swiped mode switch.
                    timer.setMode(pm)
                    pendingMode = null
                    host.sound(Sfx.SELECT)
                    host.say("mode_switched")
                    return
                }
                timer.toggle()
                if (timer.running) { host.sound(Sfx.START); host.say("timer_start") }
                else if (!timer.finished) { host.sound(Sfx.PAUSE); host.say("timer_paused") }
            }
            AppState.SETTINGS -> menu.activate()
            AppState.WORKOUT_MENU -> startWorkout()
            AppState.WORKOUT -> if (workout.isDone()) endWorkout() else workout.togglePause()
        }
    }

    fun doubleTap() {
        when (state) {
            AppState.DISCLAIMER -> {}
            AppState.HUD -> {
                if (pendingMode != null) { pendingMode = null; host.sound(Sfx.CANCEL); return }
                timer.reset()
                kcal = 0f          // same session, same zero
                host.sound(Sfx.RESET)
                host.say("timer_reset")
            }
            AppState.SETTINGS -> closeSettings()
            AppState.WORKOUT_MENU -> { state = AppState.HUD; host.sound(Sfx.CANCEL) }
            AppState.WORKOUT ->
                if (workout.isDone() || workout.isPaused()) endWorkout() else workout.skip()
        }
    }

    fun tripleTap() {
        when (state) {
            AppState.DISCLAIMER -> {}
            AppState.HUD, AppState.WORKOUT_MENU -> {
                state = AppState.SETTINGS
                menu.onOpen()
                host.sound(Sfx.SELECT)
            }
            AppState.WORKOUT -> {
                workout.pause()
                state = AppState.SETTINGS
                host.workoutSurface(false)
                menu.onOpen()
                host.sound(Sfx.SELECT)
            }
            AppState.SETTINGS -> closeSettings()
        }
    }

    fun closeSettings() {
        // Leaving settings mid-edit must not leave the menu believing a swipe
        // still means "change this value" when it reopens.
        menu.onClose()
        state = if (workout.active) AppState.WORKOUT else AppState.HUD
        host.workoutSurface(state == AppState.WORKOUT)
        host.sound(Sfx.SELECT)
        host.applySettings()
    }

    // ------------------------------------------------------- mat workout

    fun openWorkoutMenu() {
        if (workout.active) workout.end()
        wkProgram = store.workoutProgram
        wkLevel = store.workoutLevel
        state = AppState.WORKOUT_MENU
        host.workoutSurface(false)
        host.sound(Sfx.SELECT)
    }

    private fun startWorkout() {
        store.workoutProgram = wkProgram
        store.workoutLevel = wkLevel
        state = AppState.WORKOUT
        host.workoutSurface(true)
        host.sound(Sfx.GO)
        workout.start(wkProgram, wkLevel)
    }

    fun endWorkout() {
        workout.end()   // says the early-exit line only if the workout wasn't finished
        state = AppState.HUD
        host.workoutSurface(false)
        host.sound(Sfx.SELECT)
    }

    /** dir: 0 up, 1 down, 2 left, 3 right (one discrete step per gesture). */
    fun swipeDir(dir: Int) {
        when (state) {
            AppState.DISCLAIMER -> {}
            AppState.SETTINGS -> menu.onDir(dir)
            AppState.HUD -> {
                if (dir == 2 || dir == 3) {
                    // Propose the next/previous timer type; tap confirms.
                    val entries = TimerMode.entries
                    val base = pendingMode ?: timer.mode
                    val next = entries[(base.ordinal + (if (dir == 3) 1 else entries.size - 1)) % entries.size]
                    pendingMode = next
                    pendingUntil = time + 4f
                    host.sound(Sfx.CONFIRM)
                } else {
                    // Swipe up/down: open the mat-coach workout picker.
                    openWorkoutMenu()
                }
            }
            AppState.WORKOUT_MENU -> {
                val n = Programs.ALL.size
                when (dir) {
                    0 -> { wkProgram = (wkProgram + n - 1) % n; host.sound(Sfx.TICK) }
                    1 -> { wkProgram = (wkProgram + 1) % n; host.sound(Sfx.TICK) }
                    2 -> { wkLevel = (wkLevel + 2) % 3; host.sound(Sfx.TICK, 1.3f) }
                    3 -> { wkLevel = (wkLevel + 1) % 3; host.sound(Sfx.TICK, 1.3f) }
                }
            }
            AppState.WORKOUT -> {}
        }
    }

    fun onBack(): Boolean {
        when (state) {
            AppState.SETTINGS -> { closeSettings(); return true }
            AppState.WORKOUT -> { endWorkout(); return true }
            AppState.WORKOUT_MENU -> { state = AppState.HUD; host.sound(Sfx.CANCEL); return true }
            else -> return false
        }
    }

    fun onAppPause() {
        // Keep it simple and honest: pause the workout clock with the app.
        if (timer.running) timer.toggle()
        workout.pause()
    }

    // -------------------------------------------------------- timer events

    override fun onCountBeep(secondsLeft: Int) = host.sound(Sfx.BEEP, 1f + (3 - secondsLeft) * 0.1f)

    override fun onWorkStart(round: Int, lastRound: Boolean) {
        host.sound(Sfx.GO)
        host.say(if (lastRound) "last_round" else "work_start")
    }

    override fun onRestStart() {
        host.sound(Sfx.DING)
        host.say("rest_start")
    }

    override fun onRoundComplete(round: Int) {
        host.sound(Sfx.DING)
        if (round % 4 == 0) coach.celebrate("ROUND $round DONE!")
    }

    override fun onFinished() {
        host.sound(Sfx.FANFARE)
        host.say("timer_done")
        coach.celebrate("WORKOUT COMPLETE!", 4f)
    }

    override fun onMinuteMark(minute: Int) {
        host.sound(Sfx.GO, 1.1f)
        host.say("minute_mark")
    }

    // ---------------------------------------------------- telemetry events

    override fun onSample(s: TelemetrySample) {
        hr = s.hr
        cadence = s.cadence
        speedMps = s.speedMps
        lastSampleAt = s.atMs
    }

    override fun onStatus(status: String) { sourceStatus = status }

    /**
     * A telemetry link went quiet — the watch, the phone bridge, Bluetooth.
     *
     * Deliberately QUIET about it. This used to throw a red frame around the
     * whole viewport, print across the centre of the wearer's sight, sound an
     * alarm and speak an urgent line — for something that is usually a phone
     * in the wrong pocket, and which resolves itself. Mid-workout that is
     * startling out of all proportion, and it put text in the one part of the
     * display the layout contract keeps clear.
     *
     * Now it is a small pulsing line along the bottom: visible if you look,
     * ignorable if you are busy. Nothing here is a health signal — the
     * sustained Zone-5 warning is the coach's own, still spoken, still
     * unconditional.
     */
    override fun onDeviceWarning(message: String) {
        warningText = message
        warningUntil = time + 6f
    }

}
