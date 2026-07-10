package com.x3trainer.engine

import android.os.SystemClock
import com.x3trainer.SettingsStore
import com.x3trainer.audio.Sfx
import com.x3trainer.telemetry.TelemetrySample
import com.x3trainer.telemetry.TelemetrySource

enum class AppState { DISCLAIMER, HUD, SETTINGS }

/** What the engine needs from the Activity. */
interface TrainerHost {
    fun applySettings()
    fun sound(id: Int, pitch: Float = 1f, vol: Float = 1f)
    fun say(id: String, urgent: Boolean = false)
    /** (Re)build the telemetry source for the current settings choice. */
    fun rebindTelemetry()
}

/**
 * Core state machine. Layout contract (rendered by Renderer):
 *   top 10%    — sports timer        (tap start/pause, double-tap reset,
 *                                     swipe+confirm switches mode)
 *   center 80% — EMPTY sightline     (only transient celebrations/warnings)
 *   bottom 10% — telemetry string    (tinted by current HR zone)
 * Triple tap opens settings.
 */
class Trainer(val store: SettingsStore, val host: TrainerHost) : TimerEvents, TelemetrySource.Listener {

    var state = AppState.DISCLAIMER; private set
    val timer = SportsTimer(store, this)
    val coach = CoachEngine(store) { id, urgent -> host.say(id, urgent) }
    val menu = TrainerMenu(this, store)

    // --- live telemetry (normalized) ---
    var hr = 0; private set
    var cadence = 0; private set
    var speedMps = -1f; private set
    var sourceStatus = ""; private set
    private var lastSampleAt = 0L

    val zone: Int get() = HrZones.zone(hr, store.maxHr)
    val deltaV: Float get() = if (speedMps < 0f) -99f else speedMps - store.targetVelocityMps

    // --- transient center-view events ---
    var warningText: String? = null; private set
    var warningFlash = 0f; private set        // red frame alpha driver, decays
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
            coach.update(dt, timer.running, timer.elapsed, hr, zone, cadence, deltaV)
        }
        if (warningFlash > 0f) warningFlash = maxOf(0f, warningFlash - dt * 0.5f)
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
        }
    }

    fun doubleTap() {
        when (state) {
            AppState.DISCLAIMER -> {}
            AppState.HUD -> {
                if (pendingMode != null) { pendingMode = null; host.sound(Sfx.CANCEL); return }
                timer.reset()
                host.sound(Sfx.RESET)
                host.say("timer_reset")
            }
            AppState.SETTINGS -> closeSettings()
        }
    }

    fun tripleTap() {
        when (state) {
            AppState.DISCLAIMER -> {}
            AppState.HUD -> {
                state = AppState.SETTINGS
                menu.onOpen()
                host.sound(Sfx.SELECT)
            }
            AppState.SETTINGS -> closeSettings()
        }
    }

    fun closeSettings() {
        state = AppState.HUD
        host.sound(Sfx.SELECT)
        host.applySettings()
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
                }
            }
        }
    }

    fun onBack(): Boolean {
        if (state == AppState.SETTINGS) { closeSettings(); return true }
        return false
    }

    fun onAppPause() {
        // Keep it simple and honest: pause the workout clock with the app.
        if (timer.running) timer.toggle()
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

    override fun onDeviceWarning(message: String) {
        warningText = message
        warningUntil = time + 6f
        warningFlash = 1f
        host.sound(Sfx.WARN)
        host.say("device_warning", urgent = true)
    }
}
