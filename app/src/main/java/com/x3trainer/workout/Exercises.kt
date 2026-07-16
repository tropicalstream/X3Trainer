package com.x3trainer.workout

/**
 * The exercise library: the most popular single-mat movements, each authored
 * as a short keyframe loop for the vector skeleton coach. Floor work is shown
 * side-on (viewYaw 90), standing work face-on or at a 3/4 angle — whatever
 * reads clearest through the waveguide. One animation cycle = one rep.
 *
 * Every exercise has a matching voice cue in phrases.json: cue_<key>.
 */
object Exercises {

    // Indices into ALL — programs reference these.
    const val IDLE = 0
    const val VICTORY = 1
    const val JUMPING_JACKS = 2
    const val HIGH_KNEES = 3
    const val SQUAT = 4
    const val SQUAT_JUMP = 5
    const val PUSHUP = 6
    const val KNEE_PUSHUP = 7
    const val PLANK = 8
    const val SIDE_PLANK = 9
    const val PLANK_JACK = 10
    const val MOUNTAIN_CLIMBER = 11
    const val BURPEE = 12
    const val LUNGE = 13
    const val GLUTE_BRIDGE = 14
    const val CRUNCH = 15
    const val BICYCLE = 16
    const val DEAD_BUG = 17
    const val BIRD_DOG = 18
    const val LEG_RAISE = 19
    const val SUPERMAN = 20
    const val RUSSIAN_TWIST = 21
    const val RUSSIAN_TWIST_DB = 22
    const val GOBLET_SQUAT = 23
    const val ROW_BENT = 24
    const val FLOOR_PRESS = 25
    const val RDL = 26
    const val SHOULDER_PRESS = 27
    const val BICEP_CURL = 28
    const val LATERAL_RAISE = 29
    const val CAT_COW = 30
    const val COBRA = 31
    const val DOWNWARD_DOG = 32
    const val CHILDS_POSE = 33

    private fun t(vararg v: Float) = v

    val ALL: Array<Exercise> = arrayOf(

        // ------------------------------------------------ rest / ceremony
        Exercise("idle", "READY", 3.5f, 15f, Db.NONE, t(0f, 0.5f),
            pose { spine(pitch = 1f); arms(abd = 4f, elbow = 4f) },
            pose { spine(pitch = 3f); arms(abd = 7f, elbow = 6f); head(2f) }),

        Exercise("victory", "VICTORY", 1.6f, 0f, Db.NONE, t(0f, 0.5f),
            pose { arms(abd = 160f, elbow = 10f); head(-8f) },
            pose { hop(0.06f); arms(abd = 172f, elbow = 4f); head(-12f); legs(abd = 6f) }),

        // ------------------------------------------------ cardio, standing
        Exercise("jumping_jacks", "JUMPING JACKS", 0.9f, 0f, Db.NONE, t(0f, 0.25f, 0.5f, 0.75f),
            pose { },
            pose { hop(0.10f); legs(abd = 14f); arms(abd = 82f) },
            pose { legs(abd = 28f); arms(abd = 165f, elbow = 8f) },
            pose { hop(0.10f); legs(abd = 14f); arms(abd = 82f) }),

        Exercise("high_knees", "HIGH KNEES", 0.7f, 20f, Db.NONE, t(0f, 0.25f, 0.5f, 0.75f),
            pose { legL(hip = 85f, knee = 95f); legR(hip = -10f); armL(pitch = -25f, elbow = 70f); armR(pitch = 35f, elbow = 70f); hop(0.03f) },
            pose { legs(hip = 20f, knee = 30f); arms(pitch = 5f, elbow = 70f); hop(0.07f) },
            pose { legR(hip = 85f, knee = 95f); legL(hip = -10f); armR(pitch = -25f, elbow = 70f); armL(pitch = 35f, elbow = 70f); hop(0.03f) },
            pose { legs(hip = 20f, knee = 30f); arms(pitch = 5f, elbow = 70f); hop(0.07f) }),

        Exercise("squat", "SQUATS", 3.2f, 25f, Db.NONE, t(0f, 0.42f, 0.58f),
            pose { arms(pitch = 8f) },
            pose { legs(hip = 95f, knee = 105f); spine(pitch = 18f); arms(pitch = 75f) },
            pose { legs(hip = 95f, knee = 105f); spine(pitch = 18f); arms(pitch = 75f) }),

        Exercise("squat_jump", "SQUAT JUMPS", 1.5f, 25f, Db.NONE, t(0f, 0.35f, 0.55f),
            pose { legs(hip = 92f, knee = 102f); spine(pitch = 20f); arms(pitch = -28f) },
            pose { hop(0.28f); legs(ankle = 30f); arms(pitch = -12f, abd = 10f); spine(pitch = -4f) },
            pose { legs(hip = 45f, knee = 55f); spine(pitch = 10f); arms(pitch = 15f) }),

        // ------------------------------------------------ push / plank family
        Exercise("pushup", "PUSH-UPS", 2.4f, 90f, Db.NONE, t(0f, 0.45f, 0.6f),
            pose { body(pitch = 88f); arms(pitch = 90f, elbow = 3f); head(-14f) },
            pose { body(pitch = 88f); arms(pitch = 75f, elbow = 95f); head(-14f) },
            pose { body(pitch = 88f); arms(pitch = 75f, elbow = 95f); head(-14f) }),

        Exercise("knee_pushup", "KNEE PUSH-UPS", 2.4f, 90f, Db.NONE, t(0f, 0.45f, 0.6f),
            pose { body(pitch = 82f); legs(hip = -6f, knee = 92f); arms(pitch = 90f, elbow = 3f); head(-14f) },
            pose { body(pitch = 82f); legs(hip = -6f, knee = 92f); arms(pitch = 74f, elbow = 95f); head(-14f) },
            pose { body(pitch = 82f); legs(hip = -6f, knee = 92f); arms(pitch = 74f, elbow = 95f); head(-14f) }),

        Exercise("plank", "PLANK", 4f, 90f, Db.NONE, t(0f, 0.5f),
            pose { body(pitch = 87f); arms(pitch = 92f, elbow = 90f); head(-16f) },
            pose { body(pitch = 87f); spine(pitch = 3f); arms(pitch = 92f, elbow = 90f); head(-13f) }),

        Exercise("side_plank", "SIDE PLANK", 4f, 0f, Db.NONE, t(0f, 0.5f),
            pose { body(roll = -82f); armR(abd = -85f, elbow = 90f); armL(abd = 95f); head(4f) },
            pose { body(roll = -87f); armR(abd = -85f, elbow = 90f); armL(abd = 98f); head(4f) }),

        Exercise("plank_jack", "PLANK JACKS", 0.9f, 90f, Db.NONE, t(0f, 0.25f, 0.5f, 0.75f),
            pose { body(pitch = 88f); arms(pitch = 90f, elbow = 3f); head(-14f) },
            pose { body(pitch = 88f); hop(0.05f); legs(abd = 12f); arms(pitch = 90f, elbow = 3f); head(-14f) },
            pose { body(pitch = 88f); legs(abd = 24f); arms(pitch = 90f, elbow = 3f); head(-14f) },
            pose { body(pitch = 88f); hop(0.05f); legs(abd = 12f); arms(pitch = 90f, elbow = 3f); head(-14f) }),

        Exercise("mountain_climber", "MOUNTAIN CLIMBERS", 0.8f, 90f, Db.NONE, t(0f, 0.25f, 0.5f, 0.75f),
            pose { body(pitch = 82f); legL(hip = 95f, knee = 100f); legR(hip = 5f); arms(pitch = 90f, elbow = 3f); head(-12f) },
            pose { body(pitch = 82f); legs(hip = 45f, knee = 50f); arms(pitch = 90f, elbow = 3f); head(-12f) },
            pose { body(pitch = 82f); legR(hip = 95f, knee = 100f); legL(hip = 5f); arms(pitch = 90f, elbow = 3f); head(-12f) },
            pose { body(pitch = 82f); legs(hip = 45f, knee = 50f); arms(pitch = 90f, elbow = 3f); head(-12f) }),

        Exercise("burpee", "BURPEES", 3.6f, 30f, Db.NONE, t(0f, 0.18f, 0.36f, 0.55f, 0.72f, 0.85f),
            pose { arms(abd = 12f) },
            pose { legs(hip = 115f, knee = 125f); spine(pitch = 40f); arms(pitch = 70f); head(10f) },
            pose { body(pitch = 86f); legs(hip = 5f, knee = 5f); spine(pitch = 4f); arms(pitch = 90f, elbow = 4f); head(-14f) },
            pose { legs(hip = 115f, knee = 125f); spine(pitch = 40f); arms(pitch = 70f); head(10f) },
            pose { hop(0.30f); arms(abd = 168f, elbow = 5f); legs(ankle = 25f); head(-8f) },
            pose { legs(hip = 25f, knee = 30f); arms(abd = 20f) }),

        // ------------------------------------------------ lower body
        Exercise("lunge", "LUNGES", 3.4f, 35f, Db.NONE, t(0f, 0.3f, 0.5f, 0.8f),
            pose { arms(elbow = 8f) },
            pose { legL(hip = 62f, knee = 88f); legR(hip = -25f, knee = 85f); spine(pitch = 6f); arms(elbow = 8f) },
            pose { arms(elbow = 8f) },
            pose { legR(hip = 62f, knee = 88f); legL(hip = -25f, knee = 85f); spine(pitch = 6f); arms(elbow = 8f) }),

        Exercise("glute_bridge", "GLUTE BRIDGE", 2.8f, 90f, Db.NONE, t(0f, 0.35f, 0.55f),
            pose { body(pitch = -88f); legs(hip = 55f, knee = 115f); head(20f) },
            pose { body(pitch = -88f); spine(pitch = -25f); legs(hip = 18f, knee = 105f); head(30f) },
            pose { body(pitch = -88f); spine(pitch = -25f); legs(hip = 18f, knee = 105f); head(30f) }),

        // ------------------------------------------------ core, on the mat
        Exercise("crunch", "CRUNCHES", 2.2f, 90f, Db.NONE, t(0f, 0.4f, 0.55f),
            pose { body(pitch = -88f); legs(hip = 50f, knee = 105f); arms(pitch = 55f, elbow = 115f); head(8f) },
            pose { body(pitch = -88f); spine(pitch = 32f); legs(hip = 50f, knee = 105f); arms(pitch = 55f, elbow = 115f); head(22f) },
            pose { body(pitch = -88f); spine(pitch = 32f); legs(hip = 50f, knee = 105f); arms(pitch = 55f, elbow = 115f); head(22f) }),

        Exercise("bicycle", "BICYCLE CRUNCH", 1.6f, 90f, Db.NONE, t(0f, 0.25f, 0.5f, 0.75f),
            pose { body(pitch = -88f); spine(pitch = 24f, yaw = 18f); legL(hip = 95f, knee = 100f); legR(hip = 15f, knee = 5f); arms(pitch = 55f, elbow = 115f); head(18f) },
            pose { body(pitch = -88f); spine(pitch = 18f); legs(hip = 55f, knee = 55f); arms(pitch = 55f, elbow = 115f); head(14f) },
            pose { body(pitch = -88f); spine(pitch = 24f, yaw = -18f); legR(hip = 95f, knee = 100f); legL(hip = 15f, knee = 5f); arms(pitch = 55f, elbow = 115f); head(18f) },
            pose { body(pitch = -88f); spine(pitch = 18f); legs(hip = 55f, knee = 55f); arms(pitch = 55f, elbow = 115f); head(14f) }),

        Exercise("dead_bug", "DEAD BUG", 4.5f, 90f, Db.NONE, t(0f, 0.2f, 0.35f, 0.5f, 0.7f, 0.85f),
            pose { body(pitch = -88f); legs(hip = 90f, knee = 90f); arms(pitch = 90f) },
            pose { body(pitch = -88f); legL(hip = 15f, knee = 8f); legR(hip = 90f, knee = 90f); armR(pitch = 165f); armL(pitch = 90f) },
            pose { body(pitch = -88f); legL(hip = 15f, knee = 8f); legR(hip = 90f, knee = 90f); armR(pitch = 165f); armL(pitch = 90f) },
            pose { body(pitch = -88f); legs(hip = 90f, knee = 90f); arms(pitch = 90f) },
            pose { body(pitch = -88f); legR(hip = 15f, knee = 8f); legL(hip = 90f, knee = 90f); armL(pitch = 165f); armR(pitch = 90f) },
            pose { body(pitch = -88f); legR(hip = 15f, knee = 8f); legL(hip = 90f, knee = 90f); armL(pitch = 165f); armR(pitch = 90f) }),

        Exercise("bird_dog", "BIRD DOG", 4.5f, 90f, Db.NONE, t(0f, 0.2f, 0.35f, 0.5f, 0.7f, 0.85f),
            pose { body(pitch = 90f); legs(hip = 90f, knee = 90f); arms(pitch = 90f); head(-18f) },
            pose { body(pitch = 90f); legL(hip = -8f, knee = 4f); legR(hip = 90f, knee = 90f); armR(pitch = 178f); armL(pitch = 90f); head(-18f) },
            pose { body(pitch = 90f); legL(hip = -8f, knee = 4f); legR(hip = 90f, knee = 90f); armR(pitch = 178f); armL(pitch = 90f); head(-18f) },
            pose { body(pitch = 90f); legs(hip = 90f, knee = 90f); arms(pitch = 90f); head(-18f) },
            pose { body(pitch = 90f); legR(hip = -8f, knee = 4f); legL(hip = 90f, knee = 90f); armL(pitch = 178f); armR(pitch = 90f); head(-18f) },
            pose { body(pitch = 90f); legR(hip = -8f, knee = 4f); legL(hip = 90f, knee = 90f); armL(pitch = 178f); armR(pitch = 90f); head(-18f) }),

        Exercise("leg_raise", "LEG RAISES", 2.6f, 90f, Db.NONE, t(0f, 0.4f, 0.55f),
            pose { body(pitch = -88f); legs(hip = 8f); head(6f) },
            pose { body(pitch = -88f); legs(hip = 88f); head(6f) },
            pose { body(pitch = -88f); legs(hip = 88f); head(6f) }),

        Exercise("superman", "SUPERMAN", 3f, 90f, Db.NONE, t(0f, 0.4f, 0.6f),
            pose { body(pitch = 88f); arms(pitch = 172f); head(-8f) },
            pose { body(pitch = 88f); spine(pitch = -22f); legs(hip = -18f); arms(pitch = 168f); head(-22f) },
            pose { body(pitch = 88f); spine(pitch = -22f); legs(hip = -18f); arms(pitch = 168f); head(-22f) }),

        Exercise("russian_twist", "RUSSIAN TWISTS", 1.6f, 15f, Db.NONE, t(0f, 0.5f),
            pose { body(pitch = -42f); spine(pitch = 12f, yaw = 38f); legs(hip = 72f, knee = 55f); arms(pitch = 85f, elbow = 25f); head(28f) },
            pose { body(pitch = -42f); spine(pitch = 12f, yaw = -38f); legs(hip = 72f, knee = 55f); arms(pitch = 85f, elbow = 25f); head(28f) }),

        Exercise("russian_twist_db", "WEIGHTED TWISTS", 1.7f, 15f, Db.SINGLE, t(0f, 0.5f),
            pose { body(pitch = -42f); spine(pitch = 12f, yaw = 38f); legs(hip = 72f, knee = 55f); arms(pitch = 85f, elbow = 25f); head(28f) },
            pose { body(pitch = -42f); spine(pitch = 12f, yaw = -38f); legs(hip = 72f, knee = 55f); arms(pitch = 85f, elbow = 25f); head(28f) }),

        // ------------------------------------------------ dumbbell strength
        Exercise("goblet_squat", "GOBLET SQUATS", 3.2f, 25f, Db.SINGLE, t(0f, 0.42f, 0.58f),
            pose { arms(pitch = 35f, elbow = 118f) },
            pose { legs(hip = 95f, knee = 105f); spine(pitch = 15f); arms(pitch = 35f, elbow = 118f) },
            pose { legs(hip = 95f, knee = 105f); spine(pitch = 15f); arms(pitch = 35f, elbow = 118f) }),

        Exercise("row_bent", "BENT-OVER ROWS", 2.2f, 30f, Db.PAIR, t(0f, 0.4f, 0.55f),
            pose { body(pitch = 42f); legs(hip = -42f, knee = 25f); arms(pitch = 48f); head(-20f) },
            pose { body(pitch = 42f); legs(hip = -42f, knee = 25f); arms(pitch = 25f, elbow = 95f); head(-20f) },
            pose { body(pitch = 42f); legs(hip = -42f, knee = 25f); arms(pitch = 25f, elbow = 95f); head(-20f) }),

        Exercise("floor_press", "FLOOR PRESS", 2.4f, 90f, Db.PAIR, t(0f, 0.4f, 0.55f),
            pose { body(pitch = -88f); legs(hip = 50f, knee = 105f); arms(pitch = 12f, abd = 65f, elbow = 95f); head(6f) },
            pose { body(pitch = -88f); legs(hip = 50f, knee = 105f); arms(pitch = 85f, abd = 25f, elbow = 8f); head(6f) },
            pose { body(pitch = -88f); legs(hip = 50f, knee = 105f); arms(pitch = 85f, abd = 25f, elbow = 8f); head(6f) }),

        Exercise("rdl", "ROMANIAN DEADLIFT", 3f, 30f, Db.PAIR, t(0f, 0.45f, 0.6f),
            pose { arms(pitch = 12f) },
            pose { body(pitch = 55f); legs(hip = -50f, knee = 18f); arms(pitch = 55f); head(-18f) },
            pose { body(pitch = 55f); legs(hip = -50f, knee = 18f); arms(pitch = 55f); head(-18f) }),

        Exercise("shoulder_press", "SHOULDER PRESS", 2.6f, 10f, Db.PAIR, t(0f, 0.4f, 0.55f),
            pose { arms(abd = 88f, elbow = 95f) },
            pose { arms(abd = 168f, elbow = 6f) },
            pose { arms(abd = 168f, elbow = 6f) }),

        Exercise("bicep_curl", "BICEP CURLS", 2.2f, 15f, Db.PAIR, t(0f, 0.4f, 0.55f),
            pose { arms(pitch = 5f, elbow = 8f) },
            pose { arms(pitch = 5f, elbow = 135f) },
            pose { arms(pitch = 5f, elbow = 135f) }),

        Exercise("lateral_raise", "LATERAL RAISES", 2.8f, 0f, Db.PAIR, t(0f, 0.4f, 0.55f),
            pose { arms(abd = 8f, elbow = 12f) },
            pose { arms(abd = 88f, elbow = 12f) },
            pose { arms(abd = 88f, elbow = 12f) }),

        // ------------------------------------------------ cool-down stretches
        Exercise("cat_cow", "CAT COW", 4f, 90f, Db.NONE, t(0f, 0.5f),
            pose { body(pitch = 90f); legs(hip = 90f, knee = 90f); arms(pitch = 90f); spine(pitch = -22f); head(-30f) },
            pose { body(pitch = 90f); legs(hip = 90f, knee = 90f); arms(pitch = 90f); spine(pitch = 35f); head(25f) }),

        Exercise("cobra", "COBRA STRETCH", 5f, 90f, Db.NONE, t(0f, 0.45f, 0.65f),
            pose { body(pitch = 88f); spine(pitch = -5f); arms(pitch = 55f, elbow = 85f); head(-10f) },
            pose { body(pitch = 88f); spine(pitch = -35f); arms(pitch = 55f, elbow = 15f); head(-24f) },
            pose { body(pitch = 88f); spine(pitch = -35f); arms(pitch = 55f, elbow = 15f); head(-24f) }),

        Exercise("downward_dog", "DOWNWARD DOG", 5f, 90f, Db.NONE, t(0f, 0.5f),
            pose { body(pitch = 118f); legs(hip = 55f, knee = 8f); arms(pitch = 168f, elbow = 5f); head(-20f) },
            pose { body(pitch = 116f); legs(hip = 53f, knee = 12f); arms(pitch = 168f, elbow = 5f); head(-20f) }),

        Exercise("childs_pose", "CHILDS POSE", 5f, 90f, Db.NONE, t(0f, 0.5f),
            pose { body(pitch = 65f); legs(hip = 135f, knee = 145f); spine(pitch = 25f); arms(pitch = 168f); head(25f) },
            pose { body(pitch = 66f); legs(hip = 135f, knee = 145f); spine(pitch = 28f); arms(pitch = 168f); head(26f) }),
    )
}
