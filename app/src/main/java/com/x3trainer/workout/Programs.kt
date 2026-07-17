package com.x3trainer.workout

/**
 * The mat-workout catalog: six programs built from the most popular
 * single-mat circuits, each scaled across three levels (beginner /
 * intermediate / advanced) by reps, seconds, rest length, and — where the
 * classic progression is a different movement — by substitution (knee
 * push-ups become push-ups, squats become squat jumps).
 */
class Step(
    val ex: IntArray,           // exercise index per level (substitutions)
    val reps: IntArray? = null, // rep target per level…
    val secs: IntArray? = null, // …or timed seconds per level
) {
    constructor(ex: Int, reps: IntArray? = null, secs: IntArray? = null) :
        this(intArrayOf(ex, ex, ex), reps, secs)
}

class Program(
    val name: String,
    val tagline: String,
    val weights: Boolean,
    /** MET intensity for the calorie estimate (kcal = MET × 3.5 × kg / 200 per minute). */
    val met: Float,
    val restSec: IntArray,
    val steps: List<Step>,
    /**
     * A calm yoga/stretching program: the whole voice track — cues AND the
     * flow lines (rest, done, halfway…) — uses only the soothing voice, and the
     * peppy spoken-vitals callouts are suppressed, so it never mixes voices.
     */
    val calm: Boolean = false,
) {
    /** Rough total minutes at a level, for the picker. */
    fun estimateMin(level: Int): Int {
        var s = 5f
        for (st in steps) {
            val e = Exercises.ALL[st.ex[level]]
            s += st.secs?.get(level)?.toFloat() ?: (st.reps!![level] * e.cycleSec / Levels.TEMPO[level])
        }
        s += restSec[level] * (steps.size - 1)
        return ((s / 60f) + 0.5f).toInt().coerceAtLeast(1)
    }
}

object Levels {
    val NAMES = arrayOf("BEGINNER", "INTERMEDIATE", "ADVANCED")
    val TEMPO = floatArrayOf(0.88f, 1f, 1.12f)
}

object Programs {
    private fun r(b: Int, i: Int, a: Int) = intArrayOf(b, i, a)

    val ALL: List<Program> = listOf(
        Program(
            "FULL BODY START", "The classic all-round mat circuit", false, 6f, r(40, 30, 20),
            listOf(
                Step(Exercises.JUMPING_JACKS, secs = r(30, 40, 50)),
                Step(Exercises.SQUAT, reps = r(10, 14, 18)),
                Step(intArrayOf(Exercises.KNEE_PUSHUP, Exercises.PUSHUP, Exercises.PUSHUP), reps = r(6, 10, 15)),
                Step(Exercises.GLUTE_BRIDGE, reps = r(10, 14, 18)),
                Step(Exercises.BIRD_DOG, reps = r(5, 8, 10)),
                Step(Exercises.DEAD_BUG, reps = r(5, 8, 10)),
                Step(Exercises.PLANK, secs = r(20, 35, 50)),
            )
        ),
        Program(
            "CORE CRUSHER", "Abs, obliques and back on the mat", false, 5f, r(35, 25, 20),
            listOf(
                Step(Exercises.CRUNCH, reps = r(12, 16, 22)),
                Step(intArrayOf(Exercises.DEAD_BUG, Exercises.BICYCLE, Exercises.BICYCLE), reps = r(8, 12, 16)),
                Step(Exercises.LEG_RAISE, reps = r(8, 12, 16)),
                Step(intArrayOf(Exercises.RUSSIAN_TWIST, Exercises.RUSSIAN_TWIST, Exercises.RUSSIAN_TWIST_DB), reps = r(12, 16, 20)),
                Step(Exercises.SUPERMAN, reps = r(8, 12, 15)),
                Step(Exercises.SIDE_PLANK, secs = r(15, 25, 35)),
                Step(Exercises.PLANK, secs = r(25, 40, 60)),
            )
        ),
        Program(
            "HIIT SWEAT", "Short, sharp, heart-pumping intervals", false, 8f, r(30, 25, 15),
            listOf(
                Step(Exercises.JUMPING_JACKS, secs = r(30, 40, 45)),
                Step(Exercises.HIGH_KNEES, secs = r(20, 30, 40)),
                Step(intArrayOf(Exercises.SQUAT, Exercises.SQUAT_JUMP, Exercises.SQUAT_JUMP), reps = r(12, 14, 18)),
                Step(Exercises.MOUNTAIN_CLIMBER, secs = r(20, 30, 40)),
                Step(Exercises.PLANK_JACK, secs = r(15, 25, 35)),
                Step(Exercises.BURPEE, reps = r(5, 8, 12)),
            )
        ),
        Program(
            "DUMBBELL POWER", "Full-body strength with a pair of dumbbells", true, 5.5f, r(45, 35, 25),
            listOf(
                Step(Exercises.GOBLET_SQUAT, reps = r(10, 12, 15)),
                Step(Exercises.ROW_BENT, reps = r(10, 12, 15)),
                Step(Exercises.FLOOR_PRESS, reps = r(10, 12, 15)),
                Step(Exercises.RDL, reps = r(10, 12, 15)),
                Step(Exercises.SHOULDER_PRESS, reps = r(8, 10, 12)),
                Step(Exercises.BICEP_CURL, reps = r(10, 12, 15)),
                Step(Exercises.LATERAL_RAISE, reps = r(8, 10, 12)),
                Step(Exercises.RUSSIAN_TWIST_DB, reps = r(12, 16, 20)),
            )
        ),
        Program(
            "LOWER BODY BURN", "Legs and glutes, floor to standing", true, 6f, r(40, 30, 20),
            listOf(
                Step(Exercises.SQUAT, reps = r(12, 15, 20)),
                Step(Exercises.LUNGE, reps = r(6, 9, 12)),
                Step(Exercises.GLUTE_BRIDGE, reps = r(12, 16, 20)),
                Step(Exercises.RDL, reps = r(10, 12, 15)),
                Step(intArrayOf(Exercises.SQUAT, Exercises.SQUAT_JUMP, Exercises.SQUAT_JUMP), reps = r(10, 12, 15)),
                Step(Exercises.PLANK, secs = r(20, 30, 45)),
            )
        ),
        Program(
            "YOGA FLOW", "A calm standing-and-floor yoga sequence", false, 3f, r(12, 12, 12),
            listOf(
                Step(Exercises.MOUNTAIN_REACH, secs = r(30, 40, 50)),
                Step(Exercises.FORWARD_FOLD, secs = r(25, 35, 45)),
                Step(Exercises.CHAIR, secs = r(20, 30, 40)),
                Step(Exercises.WARRIOR, secs = r(32, 45, 60)),
                Step(Exercises.TRIANGLE, secs = r(32, 45, 60)),
                Step(Exercises.DOWNWARD_DOG, secs = r(20, 30, 40)),
                Step(Exercises.TREE, secs = r(32, 45, 60)),
                Step(Exercises.CHILDS_POSE, secs = r(30, 40, 50)),
            ),
            calm = true,
        ),
        Program(
            "FULL STRETCH", "Head-to-toe general stretching", false, 2.5f, r(10, 10, 10),
            listOf(
                Step(Exercises.NECK_ROLLS, secs = r(20, 30, 40)),
                Step(Exercises.SHOULDER_CROSS, secs = r(28, 30, 45)),
                Step(Exercises.TRICEP_OVERHEAD, secs = r(28, 30, 45)),
                Step(Exercises.QUAD_STRETCH, secs = r(32, 45, 60)),
                Step(Exercises.HIP_FLEXOR_LUNGE, secs = r(32, 45, 60)),
                Step(Exercises.SEATED_FOLD, secs = r(30, 40, 50)),
                Step(Exercises.BUTTERFLY, secs = r(30, 40, 50)),
                Step(Exercises.FIGURE_FOUR, secs = r(32, 45, 60)),
                Step(Exercises.COBRA, secs = r(20, 30, 40)),
            ),
            calm = true,
        ),
        Program(
            "COOL-DOWN FLOW", "Gentle stretches to finish any session", false, 2.5f, r(10, 10, 10),
            listOf(
                Step(Exercises.CAT_COW, secs = r(30, 40, 50)),
                Step(Exercises.COBRA, secs = r(20, 30, 40)),
                Step(Exercises.DOWNWARD_DOG, secs = r(20, 30, 40)),
                Step(Exercises.CHILDS_POSE, secs = r(30, 40, 50)),
            ),
            calm = true,
        ),
    )
}
