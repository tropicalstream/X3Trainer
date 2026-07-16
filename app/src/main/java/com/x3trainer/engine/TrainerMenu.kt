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
 * DISCRETE: one temple-pad swipe = one step (suite convention). Up/down move
 * the selection; left/right adjust; tap activates. Reset Settings sits at the
 * bottom per suite convention.
 */
class TrainerMenu(private val engine: Trainer, private val store: SettingsStore) {
    var selected = 0
    private var confirmingReset = false

    fun onOpen() {
        selected = 0
        confirmingReset = false
    }

    val items: List<SettingsItem> = listOf(
        SettingsItem("Resume", { "" }, activate = { engine.closeSettings() }),
        SettingsItem("Mat Coach Workout", { "" }, activate = { engine.openWorkoutMenu() }),
        SettingsItem("Body Weight", { "${store.bodyWeightKg} kg" }, adjust = { d -> store.bodyWeightKg += d }),
        SettingsItem("Data Source", { store.dataSourceLabel }, adjust = { d ->
            store.dataSource += d
            engine.host.rebindTelemetry()
        }),
        SettingsItem("Max Heart Rate", { "${store.maxHr}" }, adjust = { d -> store.maxHr += d }),
        SettingsItem("Target Zone", { "Z${store.targetZone}" }, adjust = { d -> store.targetZone += d }),
        SettingsItem("Target Cadence", { "${store.targetCadence} spm" }, adjust = { d -> store.targetCadence += d * 5 }),
        SettingsItem("Target Pace", { store.targetPaceLabel }, adjust = { d -> store.targetPaceSecPerKm += d * 15 }),
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
        when (dir) {
            0 -> move(-1)
            1 -> move(1)
            2 -> adjust(-1)
            3 -> adjust(1)
        }
    }

    private fun move(d: Int) {
        selected = (selected + d + items.size) % items.size
        confirmingReset = false
        engine.host.sound(Sfx.TICK)
    }

    private fun adjust(d: Int) {
        items[selected].adjust?.invoke(d) ?: return
        // Timer config changes take effect on the next reset of that mode.
        if (!engine.timer.running) engine.timer.reset()
        engine.host.sound(Sfx.TICK, 1.3f)
    }

    fun activate() {
        val item = items[selected]
        if (item.activate != null) { item.activate.invoke(); engine.host.sound(Sfx.SELECT) }
        else { item.adjust?.invoke(1); engine.host.sound(Sfx.TICK, 1.3f) }
    }
}
