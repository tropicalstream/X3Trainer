package com.x3trainer.engine

import com.x3trainer.SettingsStore
import com.x3trainer.audio.Sfx

class SettingsItem(
    val label: String,
    val value: () -> String,
    val adjust: ((Int) -> Unit)? = null,
    val activate: (() -> Unit)? = null,
)

/**
 * Settings overlay (triple-tap to enter, double-tap to leave). Navigation is
 * DISCRETE: one temple-pad swipe = one step (suite convention).
 *
 * TWO MODES, BECAUSE THE PAD HAS ONE USABLE AXIS. This used to put movement
 * on up/down and adjustment on left/right, which assumes a pad that reports
 * vertical swipes — and on the X3 Pro's temple strip they do not arrive at
 * all, leaving the wearer unable to move off the first row. So the same
 * horizontal swipe does both jobs, and a tap says which one it is doing:
 *
 *   BROWSE  swipe = move through the list, tap = open the selected row
 *   EDIT    swipe = change this row's value,  tap = done, back to browsing
 *
 * Vertical still works wherever the hardware provides it — it is simply no
 * longer required for anything. Rows that DO something rather than hold a
 * value (Resume, Mat Coach, Reset) act on tap and never enter edit mode,
 * because there is nothing there to adjust.
 *
 * Reset Settings sits at the bottom per suite convention.
 */
class TrainerMenu(private val engine: Trainer, private val store: SettingsStore) {
    var selected = 0
    private var confirmingReset = false

    /** True while a swipe changes the selected row's VALUE rather than the row. */
    var editing = false
        private set

    fun onOpen() {
        selected = 0
        confirmingReset = false
        editing = false
    }

    val items: List<SettingsItem> = listOf(
        SettingsItem("Resume", { "" }, activate = { engine.closeSettings() }),
        SettingsItem("Mat Coach Workout", { "" }, activate = { engine.openWorkoutMenu() }),
        // Units first among the display choices: it changes how every row
        // below it reads, so it belongs above them.
        // Above every target it governs: changing this changes what they
        // all mean, and each activity keeps its own set.
        SettingsItem("Exercise", { store.exerciseMode.label }, adjust = { d ->
            store.exerciseMode = ExerciseMode.of(store.exerciseMode.ordinal + d)
        }),
        SettingsItem("Units", { store.unitsLabel }, adjust = { _ -> store.imperial = !store.imperial }),
        // Adjusted in KILOGRAMS whichever units are shown — one stored step
        // per press. Stepping by a pound would round to the same kilogram and
        // the number would refuse to move at all.
        SettingsItem("Body Weight", { store.bodyWeightLabel }, adjust = { d -> store.bodyWeightKg += d }),
        SettingsItem("Data Source", { store.dataSourceLabel }, adjust = { d ->
            store.dataSource += d
            engine.host.rebindTelemetry()
        }),
        SettingsItem("Max Heart Rate", { "${store.maxHr}" }, adjust = { d -> store.maxHr += d }),
        SettingsItem("Target Zone", { "Z${store.targetZone}" }, adjust = { d -> store.targetZone += d }),
        // Kept visible in every mode so the list does not reshuffle under
        // the wearer's finger, but honest about not applying to a bike.
        SettingsItem("Target Cadence", {
            if (store.exerciseMode.stepCadence) "${store.targetCadence} spm" else "n/a on a bike"
        }, adjust = { d -> if (store.exerciseMode.stepCadence) store.targetCadence += d * 5 }),
        SettingsItem("Target Pace", { store.targetPaceLabel }, adjust = { d -> store.targetPaceSecPerKm += d * 15 }),
        SettingsItem("Target Calories", { "${store.targetKcal} kcal" }, adjust = { d -> store.targetKcal += d * 25 }),
        SettingsItem("Coach", { store.coachLevelLabel }, adjust = { d -> store.coachLevel += d }),
        SettingsItem("Voice Volume", { "${store.voiceVolume * 10}%" }, adjust = { d ->
            store.voiceVolume += d; engine.host.applySettings()
        }),
        SettingsItem("Sound Volume", { "${store.soundVolume * 10}%" }, adjust = { d ->
            store.soundVolume += d; engine.host.applySettings()
        }),
        SettingsItem("Countdown Mins", { "${store.countdownMin}" }, adjust = { d -> store.countdownMin += d }),
        SettingsItem("Interval Work", { "${store.intervalWorkSec}s" }, adjust = { d -> store.intervalWorkSec += d * 5 }),
        SettingsItem("Interval Rest", { "${store.intervalRestSec}s" }, adjust = { d -> store.intervalRestSec += d * 5 }),
        SettingsItem("Interval Rounds", { "${store.intervalRounds}" }, adjust = { d -> store.intervalRounds += d }),
        SettingsItem("EMOM Mins", { "${store.emomMin}" }, adjust = { d -> store.emomMin += d }),
        SettingsItem("AMRAP Mins", { "${store.amrapMin}" }, adjust = { d -> store.amrapMin += d }),
        SettingsItem("Swipe Sensitivity", { "%.1f".format(store.swipeSens) }, adjust = { d ->
            store.swipeSens += d * 0.1f
        }),
        // Kept at the bottom by suite convention.
        SettingsItem("Reset Settings", { if (confirmingReset) "tap again!" else "" }, activate = {
            if (confirmingReset) {
                store.resetSettings()
                engine.host.applySettings()
                engine.host.rebindTelemetry()
                engine.timer.loadMode()
                confirmingReset = false
            } else confirmingReset = true
        }),
    )

    /** One discrete step from a single swipe gesture. dir: 0 up,1 down,2 left,3 right. */
    fun onDir(dir: Int) {
        // Left and up decrease, right and down increase — the same pair of
        // meanings whichever axis the hardware happens to report, so a wearer
        // never has to know which one they are on.
        val step = if (dir == 0 || dir == 2) -1 else 1
        if (editing) adjust(step) else move(step)
    }

    private fun move(d: Int) {
        selected = (selected + d + items.size) % items.size
        confirmingReset = false
        engine.host.sound(Sfx.TICK)
    }

    /** Double-tap leaves settings, so it must also leave edit mode cleanly. */
    fun onClose() { editing = false }

    private fun adjust(d: Int) {
        items[selected].adjust?.invoke(d) ?: return
        // Timer config changes take effect on the next reset of that mode.
        if (!engine.timer.running) engine.timer.reset()
        engine.host.sound(Sfx.TICK, 1.3f)
    }

    fun activate() {
        val item = items[selected]
        // An action row does its thing and stays put; there is no value to edit.
        if (item.activate != null) {
            editing = false
            item.activate.invoke()
            engine.host.sound(Sfx.SELECT)
            return
        }
        if (item.adjust == null) return
        // A value row toggles between browsing and editing. Tapping no longer
        // nudges the value by one: that made every accidental tap a silent
        // edit, and with swipes now doing the adjusting it is not needed.
        editing = !editing
        engine.host.sound(if (editing) Sfx.SELECT else Sfx.CONFIRM)
    }
}
