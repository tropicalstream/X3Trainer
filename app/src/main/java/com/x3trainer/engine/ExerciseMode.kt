package com.x3trainer.engine

/**
 * What the wearer is actually doing, which decides what the numbers mean.
 *
 * The app was tuned throughout for running — 175 spm, five and a half minutes
 * per kilometre — and those are not merely different values for a walker,
 * they are values that make the coach wrong. A walker holding a perfectly good
 * 110 spm sits 65 below target and gets told to speed up, over and over,
 * because the target belonged to a different activity.
 *
 * Cycling is not a retune at all, it is a different measurement. Cadence here
 * comes from a PEDOMETER, and a bicycle has no steps: the honest reading is
 * nothing, and the dishonest one is road vibration counted as footfalls. So
 * this carries a flag saying whether step cadence means anything, rather than
 * leaving the coach to nudge a wearer toward a number nobody measured.
 *
 * Each mode keeps its OWN targets. Switching to walk and back must not cost
 * the runner the pace they spent a season tuning.
 */
enum class ExerciseMode(
    val label: String,
    /** Preference-key suffix, so each mode remembers its own targets. */
    val key: String,
    val defaultCadence: Int,
    val defaultPaceSecPerKm: Int,
    /** Intensity for the calorie estimate when there is no heart rate. */
    val met: Float,
    /**
     * Whether steps per minute describe this activity. False on a bike, where
     * the meaningful cadence is crank RPM from a sensor this app does not yet
     * read — see the note on Cycling Speed & Cadence below.
     */
    val stepCadence: Boolean,
    /**
     * Whether to read progress as PACE (minutes per distance, how runners and
     * walkers think) or as SPEED (how cyclists do).
     */
    val paceOriented: Boolean,
) {
    WALK("Walk", "walk", defaultCadence = 115, defaultPaceSecPerKm = 660, met = 3.5f,
        stepCadence = true, paceOriented = true),
    RUN("Run", "run", defaultCadence = 175, defaultPaceSecPerKm = 330, met = 9.0f,
        stepCadence = true, paceOriented = true),
    // A bike sensor would speak Cycling Speed & Cadence (0x1816) — the phone
    // bridge already subscribes to the running equivalent (0x1814), so that is
    // a small addition rather than a new mechanism. Until then this mode is
    // honest about having no cadence rather than reporting the pedometer's.
    BIKE("Bike", "bike", defaultCadence = 85, defaultPaceSecPerKm = 150, met = 8.0f,
        stepCadence = false, paceOriented = false);

    companion object {
        fun of(ordinal: Int): ExerciseMode = entries[((ordinal % entries.size) + entries.size) % entries.size]
    }
}
