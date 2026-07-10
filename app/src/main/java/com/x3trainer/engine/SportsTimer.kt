package com.x3trainer.engine

import com.x3trainer.SettingsStore

/** The popular sports-timer types, cycled by swipe (+confirm) in the HUD. */
enum class TimerMode(val label: String) {
    STOPWATCH("STOPWATCH"),
    COUNTDOWN("COUNTDOWN"),
    INTERVAL("INTERVAL"),
    TABATA("TABATA"),
    EMOM("EMOM"),
    AMRAP("AMRAP"),
}

/** Events the timer raises for sound/voice/celebration hooks. */
interface TimerEvents {
    fun onCountBeep(secondsLeft: Int)   // 3..1 into any phase end
    fun onWorkStart(round: Int, lastRound: Boolean)
    fun onRestStart()
    fun onRoundComplete(round: Int)
    fun onFinished()
    fun onMinuteMark(minute: Int)       // EMOM top-of-minute
}

/**
 * One timer engine for all six modes. Phases: for INTERVAL/TABATA a
 * work/rest alternation across rounds; EMOM repeated 60s work phases; AMRAP
 * and COUNTDOWN a single descending block; STOPWATCH a single ascending one.
 */
class SportsTimer(private val store: SettingsStore, private val events: TimerEvents) {

    var mode = TimerMode.STOPWATCH; private set
    var running = false; private set
    var elapsed = 0f; private set        // total seconds since start
    var phaseLeft = 0f; private set      // seconds left in current phase (descending modes)
    var round = 0; private set
    var totalRounds = 0; private set
    var inWork = true; private set
    var finished = false; private set

    private var lastWholeSecond = -1

    fun setMode(m: TimerMode) {
        mode = m
        store.timerMode = m.ordinal
        reset()
    }

    fun loadMode() {
        mode = TimerMode.entries.getOrElse(store.timerMode) { TimerMode.STOPWATCH }
        reset()
    }

    fun toggle() {
        if (finished) { reset(); return }
        running = !running
    }

    fun reset() {
        running = false
        finished = false
        elapsed = 0f
        round = 1
        inWork = true
        lastWholeSecond = -1
        when (mode) {
            TimerMode.STOPWATCH -> { phaseLeft = 0f; totalRounds = 0 }
            TimerMode.COUNTDOWN -> { phaseLeft = store.countdownMin * 60f; totalRounds = 0 }
            TimerMode.INTERVAL -> { phaseLeft = store.intervalWorkSec.toFloat(); totalRounds = store.intervalRounds }
            TimerMode.TABATA -> { phaseLeft = 20f; totalRounds = 8 }
            TimerMode.EMOM -> { phaseLeft = 60f; totalRounds = store.emomMin }
            TimerMode.AMRAP -> { phaseLeft = store.amrapMin * 60f; totalRounds = 0 }
        }
    }

    fun update(dt: Float) {
        if (!running || finished) return
        elapsed += dt
        if (mode == TimerMode.STOPWATCH) return

        phaseLeft -= dt
        val whole = phaseLeft.toInt()
        if (whole != lastWholeSecond && whole in 0..2 && phaseLeft > 0f) {
            lastWholeSecond = whole
            events.onCountBeep(whole + 1)
        }
        if (phaseLeft > 0f) return

        when (mode) {
            TimerMode.COUNTDOWN, TimerMode.AMRAP -> finish()
            TimerMode.EMOM -> {
                if (round >= totalRounds) finish()
                else { round++; phaseLeft += 60f; events.onMinuteMark(round) }
            }
            TimerMode.INTERVAL, TimerMode.TABATA -> {
                val workLen = if (mode == TimerMode.TABATA) 20f else store.intervalWorkSec.toFloat()
                val restLen = if (mode == TimerMode.TABATA) 10f else store.intervalRestSec.toFloat()
                if (inWork) {
                    events.onRoundComplete(round)
                    if (round >= totalRounds) { finish(); return }
                    inWork = false
                    phaseLeft += restLen
                    events.onRestStart()
                } else {
                    inWork = true
                    round++
                    phaseLeft += workLen
                    events.onWorkStart(round, round == totalRounds)
                }
            }
            TimerMode.STOPWATCH -> {}
        }
        lastWholeSecond = -1
    }

    private fun finish() {
        finished = true
        running = false
        events.onFinished()
    }

    /** Big HUD string, e.g. "12:34" or "00:19". */
    fun clockText(): String {
        val t = if (mode == TimerMode.STOPWATCH) elapsed else maxOf(0f, phaseLeft)
        val s = t.toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Secondary HUD string: phase and rounds, e.g. "WORK 3/8". */
    fun phaseText(): String = when (mode) {
        TimerMode.STOPWATCH -> "TOTAL"
        TimerMode.COUNTDOWN -> "REMAINING"
        TimerMode.INTERVAL, TimerMode.TABATA ->
            (if (inWork) "WORK" else "REST") + " $round/$totalRounds"
        TimerMode.EMOM -> "MIN $round/$totalRounds"
        TimerMode.AMRAP -> "AMRAP"
    }
}
