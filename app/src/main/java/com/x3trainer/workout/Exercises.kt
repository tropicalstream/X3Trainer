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

    /**
     * A small, explicit visual relationship for holds that a stick figure
     * cannot communicate reliably on its own.  The renderer draws these in
     * amber, which makes the "hand to elbow/ankle" or "foot to knee" target
     * unambiguous without turning the coach into a labelled diagram.
     */
    data class VisualCue(val from: Int, val to: Int, val start: Float = 0f, val end: Float = 1f)

    private val NO_VISUAL_CUES = emptyArray<VisualCue>()
    private val VISUAL_CUES: Map<String, Array<VisualCue>> = mapOf(
        "quad_stretch" to arrayOf(
            VisualCue(Rig.HAND_R, Rig.ANKLE_R, 0f, 0.5f),
            VisualCue(Rig.HAND_L, Rig.ANKLE_L, 0.5f, 1f),
        ),
        "tree" to arrayOf(
            VisualCue(Rig.ANKLE_R, Rig.KNEE_L, 0f, 0.5f),
            VisualCue(Rig.ANKLE_L, Rig.KNEE_R, 0.5f, 1f),
        ),
        "figure_four" to arrayOf(
            VisualCue(Rig.ANKLE_R, Rig.KNEE_L, 0f, 0.5f),
            VisualCue(Rig.ANKLE_L, Rig.KNEE_R, 0.5f, 1f),
        ),
    )

    fun visualCues(key: String): Array<VisualCue> = VISUAL_CUES[key] ?: NO_VISUAL_CUES

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
    const val MOUNTAIN_REACH = 34
    const val FORWARD_FOLD = 35
    const val CHAIR = 36
    const val WARRIOR = 37
    const val TRIANGLE = 38
    const val TREE = 39
    const val NECK_ROLLS = 40
    const val SHOULDER_CROSS = 41
    const val TRICEP_OVERHEAD = 42
    const val QUAD_STRETCH = 43
    const val SEATED_FOLD = 44
    const val BUTTERFLY = 45
    const val FIGURE_FOUR = 46
    const val HIP_FLEXOR_LUNGE = 47

    private fun t(vararg v: Float) = v

    /**
     * Standing exercises whose feet must stay planted while the legs bend or
     * hinge — the rig anchors the planted foot so the body moves over stationary
     * feet. Cardio (running-in-place) and floor/prone work are deliberately
     * excluded: there the feet legitimately travel or aren't the ground support.
     */
    private val PLANTED = setOf(
        "squat", "squat_jump", "goblet_squat", "chair", "lunge", "rdl", "row_bent",
        "forward_fold", "warrior", "triangle", "tree", "quad_stretch",
        "hip_flexor_lunge", "mountain_reach", "jumping_jacks",
    )

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

        Exercise("squat", "SQUATS", 3.2f, 52f, Db.NONE, t(0f, 0.42f, 0.58f),
            pose { arms(pitch = 8f) },
            pose { legs(hip = 95f, knee = 105f); spine(pitch = 18f); arms(pitch = 75f) },
            pose { legs(hip = 95f, knee = 105f); spine(pitch = 18f); arms(pitch = 75f) }),

        Exercise("squat_jump", "SQUAT JUMPS", 1.5f, 52f, Db.NONE, t(0f, 0.35f, 0.55f),
            pose { legs(hip = 92f, knee = 102f); spine(pitch = 20f); arms(pitch = -28f) },
            pose { hop(0.28f); legs(ankle = 30f); arms(pitch = -12f, abd = 10f); spine(pitch = -4f) },
            pose { legs(hip = 45f, knee = 55f); spine(pitch = 10f); arms(pitch = 15f) }),

        // ------------------------------------------------ push / plank family
        // Contact-solved: hands AND toes/knees rest on the floor at both ends
        // of the rep (grounding translates but can't rotate, so each key's
        // body pitch must put every support on the mat itself).
        Exercise("pushup", "PUSH-UPS", 2.4f, 90f, Db.NONE, t(0f, 0.45f, 0.6f),
            pose { body(pitch = 72f); arms(pitch = 90f, elbow = 3f); head(-14f) },
            pose { body(pitch = 84f); arms(pitch = 75f, elbow = 95f); head(-14f) },
            pose { body(pitch = 84f); arms(pitch = 75f, elbow = 95f); head(-14f) }),

        Exercise("knee_pushup", "KNEE PUSH-UPS", 2.4f, 90f, Db.NONE, t(0f, 0.45f, 0.6f),
            pose { body(pitch = 62f); legs(hip = 28f, knee = 54f, ankle = 85f); arms(pitch = 66f, elbow = 3f); head(-14f) },
            pose { body(pitch = 74f); legs(hip = 16f, knee = 40f, ankle = 85f); arms(pitch = 58f, elbow = 95f); head(-14f) },
            pose { body(pitch = 74f); legs(hip = 16f, knee = 40f, ankle = 85f); arms(pitch = 58f, elbow = 95f); head(-14f) }),

        Exercise("plank", "PLANK", 4f, 90f, Db.NONE, t(0f, 0.5f),
            pose { body(pitch = 85f); arms(pitch = 92f, elbow = 90f); head(-16f) },
            pose { body(pitch = 84f); spine(pitch = 3f); arms(pitch = 92f, elbow = 90f); head(-13f) }),

        Exercise("side_plank", "SIDE PLANK", 4f, 0f, Db.NONE, t(0f, 0.5f),
            // At -75° the support-side foot hovered above the mat.  This
            // angle keeps the forearm and lower foot visibly grounded.
            pose { body(roll = -72f); armL(abd = 80f, elbow = 88f); armR(abd = 88f); head(4f) },
            pose { body(roll = -73f); armL(abd = 84f, elbow = 88f); armR(abd = 92f); head(4f) }),

        Exercise("plank_jack", "PLANK JACKS", 0.9f, 90f, Db.NONE, t(0f, 0.25f, 0.5f, 0.75f),
            pose { body(pitch = 72f); arms(pitch = 90f, elbow = 3f); head(-14f) },
            pose { body(pitch = 72f); hop(0.05f); legs(abd = 12f); arms(pitch = 90f, elbow = 3f); head(-14f) },
            pose { body(pitch = 72f); legs(abd = 24f); arms(pitch = 90f, elbow = 3f); head(-14f) },
            pose { body(pitch = 72f); hop(0.05f); legs(abd = 12f); arms(pitch = 90f, elbow = 3f); head(-14f) }),

        Exercise("mountain_climber", "MOUNTAIN CLIMBERS", 0.8f, 90f, Db.NONE, t(0f, 0.25f, 0.5f, 0.75f),
            pose { body(pitch = 85f); legL(hip = 95f, knee = 100f); legR(hip = 5f); arms(pitch = 90f, elbow = 3f); head(-12f) },
            pose { body(pitch = 85f); legs(hip = 45f, knee = 50f); arms(pitch = 90f, elbow = 3f); head(-12f) },
            pose { body(pitch = 85f); legR(hip = 95f, knee = 100f); legL(hip = 5f); arms(pitch = 90f, elbow = 3f); head(-12f) },
            pose { body(pitch = 85f); legs(hip = 45f, knee = 50f); arms(pitch = 90f, elbow = 3f); head(-12f) }),

        Exercise("burpee", "BURPEES", 3.6f, 30f, Db.NONE, t(0f, 0.18f, 0.36f, 0.55f, 0.72f, 0.85f),
            pose { arms(abd = 12f) },
            pose { legs(hip = 115f, knee = 125f); spine(pitch = 40f); arms(pitch = 70f); head(10f) },
            pose { body(pitch = 72f); legs(hip = 5f, knee = 5f); spine(pitch = 4f); arms(pitch = 90f, elbow = 4f); head(-14f) },
            pose { legs(hip = 115f, knee = 125f); spine(pitch = 40f); arms(pitch = 70f); head(10f) },
            pose { hop(0.30f); arms(abd = 168f, elbow = 5f); legs(ankle = 25f); head(-8f) },
            pose { legs(hip = 25f, knee = 30f); arms(abd = 20f) }),

        // ------------------------------------------------ lower body
        Exercise("lunge", "LUNGES", 3.4f, 60f, Db.NONE, t(0f, 0.3f, 0.5f, 0.8f),
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
            pose { body(pitch = 90f); legs(hip = 90f, knee = 90f, ankle = 85f); arms(pitch = 112f, elbow = 42f); head(-18f) },
            pose { body(pitch = 90f); legL(hip = -8f, knee = 4f, ankle = 40f); legR(hip = 90f, knee = 90f, ankle = 85f); armR(pitch = 178f); armL(pitch = 112f, elbow = 42f); head(-18f) },
            pose { body(pitch = 90f); legL(hip = -8f, knee = 4f, ankle = 40f); legR(hip = 90f, knee = 90f, ankle = 85f); armR(pitch = 178f); armL(pitch = 112f, elbow = 42f); head(-18f) },
            pose { body(pitch = 90f); legs(hip = 90f, knee = 90f, ankle = 85f); arms(pitch = 112f, elbow = 42f); head(-18f) },
            pose { body(pitch = 90f); legR(hip = -8f, knee = 4f, ankle = 40f); legL(hip = 90f, knee = 90f, ankle = 85f); armL(pitch = 178f); armR(pitch = 112f, elbow = 42f); head(-18f) },
            pose { body(pitch = 90f); legR(hip = -8f, knee = 4f, ankle = 40f); legL(hip = 90f, knee = 90f, ankle = 85f); armL(pitch = 178f); armR(pitch = 112f, elbow = 42f); head(-18f) }),

        Exercise("leg_raise", "LEG RAISES", 2.6f, 90f, Db.NONE, t(0f, 0.4f, 0.55f),
            pose { body(pitch = -88f); legs(hip = 8f, ankle = 35f); head(6f) },
            pose { body(pitch = -88f); legs(hip = 88f, ankle = 35f); head(6f) },
            pose { body(pitch = -88f); legs(hip = 88f, ankle = 35f); head(6f) }),

        Exercise("superman", "SUPERMAN", 3f, 90f, Db.NONE, t(0f, 0.4f, 0.6f),
            pose { body(pitch = 88f); arms(pitch = 172f); head(-8f) },
            pose { body(pitch = 88f); spine(pitch = -22f); legs(hip = -18f); arms(pitch = 168f); head(-22f) },
            pose { body(pitch = 88f); spine(pitch = -22f); legs(hip = -18f); arms(pitch = 168f); head(-22f) }),

        Exercise("russian_twist", "RUSSIAN TWISTS", 1.6f, 35f, Db.NONE, t(0f, 0.5f),
            pose { body(pitch = -42f); spine(pitch = 12f, yaw = 38f); legs(hip = 65f, knee = 70f, ankle = 35f); arms(pitch = 85f, elbow = 25f); head(28f) },
            pose { body(pitch = -42f); spine(pitch = 12f, yaw = -38f); legs(hip = 65f, knee = 70f, ankle = 35f); arms(pitch = 85f, elbow = 25f); head(28f) }),

        Exercise("russian_twist_db", "WEIGHTED TWISTS", 1.7f, 35f, Db.SINGLE, t(0f, 0.5f),
            pose { body(pitch = -42f); spine(pitch = 12f, yaw = 38f); legs(hip = 65f, knee = 70f, ankle = 35f); arms(pitch = 85f, elbow = 25f); head(28f) },
            pose { body(pitch = -42f); spine(pitch = 12f, yaw = -38f); legs(hip = 65f, knee = 70f, ankle = 35f); arms(pitch = 85f, elbow = 25f); head(28f) }),

        // ------------------------------------------------ dumbbell strength
        Exercise("goblet_squat", "GOBLET SQUATS", 3.2f, 52f, Db.SINGLE, t(0f, 0.42f, 0.58f),
            pose { arms(pitch = 35f, elbow = 118f) },
            pose { legs(hip = 95f, knee = 105f); spine(pitch = 15f); arms(pitch = 35f, elbow = 118f) },
            pose { legs(hip = 95f, knee = 105f); spine(pitch = 15f); arms(pitch = 35f, elbow = 118f) }),

        Exercise("row_bent", "BENT-OVER ROWS", 2.2f, 55f, Db.PAIR, t(0f, 0.4f, 0.55f),
            pose { body(pitch = 42f); legs(hip = 42f, knee = 25f); arms(pitch = 48f); head(-20f) },
            pose { body(pitch = 42f); legs(hip = 42f, knee = 25f); arms(pitch = 25f, elbow = 95f); head(-20f) },
            pose { body(pitch = 42f); legs(hip = 42f, knee = 25f); arms(pitch = 25f, elbow = 95f); head(-20f) }),

        Exercise("floor_press", "FLOOR PRESS", 2.4f, 90f, Db.PAIR, t(0f, 0.4f, 0.55f),
            pose { body(pitch = -88f); legs(hip = 50f, knee = 105f); arms(pitch = 12f, abd = 65f, elbow = 95f); head(6f) },
            pose { body(pitch = -88f); legs(hip = 50f, knee = 105f); arms(pitch = 85f, abd = 25f, elbow = 8f); head(6f) },
            pose { body(pitch = -88f); legs(hip = 50f, knee = 105f); arms(pitch = 85f, abd = 25f, elbow = 8f); head(6f) }),

        Exercise("rdl", "ROMANIAN DEADLIFT", 3f, 55f, Db.PAIR, t(0f, 0.45f, 0.6f),
            pose { arms(pitch = 12f) },
            pose { body(pitch = 55f); legs(hip = 50f, knee = 18f); arms(pitch = 55f); head(-18f) },
            pose { body(pitch = 55f); legs(hip = 50f, knee = 18f); arms(pitch = 55f); head(-18f) }),

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
            pose { body(pitch = 90f); legs(hip = 90f, knee = 90f, ankle = 85f); arms(pitch = 64f, elbow = 42f); spine(pitch = -22f); head(-30f) },
            pose { body(pitch = 90f); legs(hip = 90f, knee = 90f, ankle = 85f); arms(pitch = 124f, elbow = 85f); spine(pitch = 24f); head(25f) }),

        // Prone with hips and legs flat on the floor (feet plantar-flexed back),
        // chest arched up on planted hands — contact-solved so the belly stays
        // low (was propped up like an upward dog).
        Exercise("cobra", "COBRA STRETCH", 5f, 90f, Db.NONE, t(0f, 0.45f, 0.65f),
            pose { body(pitch = 90f); legs(ankle = -62f); spine(pitch = -12f); arms(pitch = 90f, elbow = 78f); head(-12f) },
            pose { body(pitch = 90f); legs(ankle = -68f); spine(pitch = -28f); arms(pitch = 80f, elbow = 60f); head(-24f) },
            pose { body(pitch = 90f); legs(ankle = -68f); spine(pitch = -28f); arms(pitch = 80f, elbow = 60f); head(-24f) }),

        Exercise("downward_dog", "DOWNWARD DOG", 5f, 90f, Db.NONE, t(0f, 0.5f),
            // Hips are set so hands *and* toes meet the mat.  The previous
            // version grounded the hands but left both feet visibly floating.
            pose { body(pitch = 118f); legs(hip = 63f, knee = 8f); arms(pitch = 168f, elbow = 5f); head(-20f) },
            pose { body(pitch = 116f); legs(hip = 60f, knee = 12f); arms(pitch = 168f, elbow = 5f); head(-20f) }),

        Exercise("childs_pose", "CHILDS POSE", 5f, 90f, Db.NONE, t(0f, 0.5f),
            pose { body(pitch = 72f); legs(hip = 132f, knee = 150f, ankle = 55f); spine(pitch = 18f); arms(pitch = 155f); head(18f) },
            pose { body(pitch = 73f); legs(hip = 132f, knee = 150f, ankle = 55f); spine(pitch = 20f); arms(pitch = 155f); head(19f) }),

        // ------------------------------------------------ yoga
        // One-sided poses hold each side for half the loop, so timed holds
        // switch sides automatically mid-hold.
        Exercise("mountain_reach", "MOUNTAIN REACH", 5f, 15f, Db.NONE, t(0f, 0.5f),
            pose { arms(abd = 168f, elbow = 4f); spine(pitch = -6f); head(-10f) },
            pose { arms(abd = 172f, elbow = 2f); spine(pitch = -9f); head(-12f) }),

        // Deep hinge (legs stay vertical via body-pitch/hip cancellation) with
        // the arms hanging to the mat — hands reach the floor by the feet.
        Exercise("forward_fold", "FORWARD FOLD", 6f, 75f, Db.NONE, t(0f, 0.5f),
            pose { body(pitch = 118f); legs(hip = 114f, knee = 12f); arms(pitch = 103f); head(-15f) },
            pose { body(pitch = 121f); legs(hip = 117f, knee = 12f); arms(pitch = 107f); head(-15f) }),

        Exercise("chair", "CHAIR POSE", 5f, 55f, Db.NONE, t(0f, 0.5f),
            pose { legs(hip = 70f, knee = 78f); spine(pitch = 12f); arms(pitch = 148f, elbow = 5f); head(-8f) },
            pose { legs(hip = 73f, knee = 81f); spine(pitch = 13f); arms(pitch = 151f, elbow = 5f); head(-8f) }),

        Exercise("warrior", "WARRIOR", 16f, 90f, Db.NONE, t(0f, 0.42f, 0.5f, 0.92f),
            pose { legL(hip = 58f, knee = 78f); legR(hip = -30f, knee = 6f, ankle = 25f); armL(pitch = 88f); armR(pitch = -55f); spine(pitch = -4f) },
            pose { legL(hip = 60f, knee = 80f); legR(hip = -30f, knee = 6f, ankle = 25f); armL(pitch = 90f); armR(pitch = -58f); spine(pitch = -4f) },
            pose { legR(hip = 58f, knee = 78f); legL(hip = -30f, knee = 6f, ankle = 25f); armR(pitch = 88f); armL(pitch = -55f); spine(pitch = -4f) },
            pose { legR(hip = 60f, knee = 80f); legL(hip = -30f, knee = 6f, ankle = 25f); armR(pitch = 90f); armL(pitch = -58f); spine(pitch = -4f) }),

        Exercise("triangle", "TRIANGLE", 16f, 0f, Db.NONE, t(0f, 0.42f, 0.5f, 0.92f),
            pose { legs(abd = 26f); spine(roll = -34f); armL(abd = 168f); armR(abd = 30f); head(-4f) },
            pose { legs(abd = 26f); spine(roll = -37f); armL(abd = 170f); armR(abd = 28f); head(-4f) },
            pose { legs(abd = 26f); spine(roll = 34f); armR(abd = 168f); armL(abd = 30f); head(-4f) },
            pose { legs(abd = 26f); spine(roll = 37f); armR(abd = 170f); armL(abd = 28f); head(-4f) }),

        // The lifted foot rests against the standing knee (contact-solved to
        // ~0.05 m): hip flexed with slight adduction brings the sole in to the
        // knee, knee bent and lifted. Sides switch at the mid-point of the hold.
        Exercise("tree", "TREE POSE", 16f, 0f, Db.NONE, t(0f, 0.42f, 0.5f, 0.92f),
            pose { legR(hip = 50f, abd = -20f, knee = 110f, ankle = 20f); arms(abd = 150f, elbow = 24f); head(-4f) },
            pose { legR(hip = 52f, abd = -20f, knee = 112f, ankle = 20f); arms(abd = 156f, elbow = 20f); head(-4f) },
            pose { legL(hip = 50f, abd = -20f, knee = 110f, ankle = 20f); arms(abd = 150f, elbow = 24f); head(-4f) },
            pose { legL(hip = 52f, abd = -20f, knee = 112f, ankle = 20f); arms(abd = 156f, elbow = 20f); head(-4f) }),

        // ------------------------------------------------ general stretching
        Exercise("neck_rolls", "NECK ROLLS", 7f, 20f, Db.NONE, t(0f, 0.25f, 0.5f, 0.75f),
            pose { head(32f); arms(abd = 5f) },
            pose { head(6f); spine(roll = 10f); arms(abd = 5f) },
            pose { head(-26f); arms(abd = 5f) },
            pose { head(6f); spine(roll = -10f); arms(abd = 5f) }),

        // Cross-body shoulder stretch.  The supporting hand-to-elbow cue
        // makes the hold legible at glasses scale; sides switch halfway.
        Exercise("shoulder_cross", "SHOULDER STRETCH", 16f, 8f, Db.NONE, t(0f, 0.42f, 0.5f, 0.92f),
            pose { armL(abd = -82f); armR(abd = 52f, pitch = 26f, elbow = 95f); spine(pitch = 3f); head(3f) },
            pose { armL(abd = -86f); armR(abd = 55f, pitch = 28f, elbow = 98f); spine(pitch = 4f); head(3f) },
            pose { armR(abd = -82f); armL(abd = 52f, pitch = 26f, elbow = 95f); spine(pitch = 3f); head(3f) },
            pose { armR(abd = -86f); armL(abd = 55f, pitch = 28f, elbow = 98f); spine(pitch = 4f); head(3f) }),

        Exercise("tricep_overhead", "TRICEP STRETCH", 14f, 25f, Db.NONE, t(0f, 0.42f, 0.5f, 0.92f),
            pose { armL(abd = 168f, elbow = 148f); armR(abd = 120f, elbow = 95f); head(-6f) },
            pose { armL(abd = 170f, elbow = 152f); armR(abd = 122f, elbow = 97f); head(-6f) },
            pose { armR(abd = 168f, elbow = 148f); armL(abd = 120f, elbow = 95f); head(-6f) },
            pose { armR(abd = 170f, elbow = 152f); armL(abd = 122f, elbow = 97f); head(-6f) }),

        // Standing quad stretch: knee bent back, the same-side hand reaches back
        // and holds the ankle (contact-solved, hand-to-ankle ~0.14 m).
        Exercise("quad_stretch", "QUAD STRETCH", 16f, 78f, Db.NONE, t(0f, 0.42f, 0.5f, 0.92f),
            pose { legR(hip = -14f, knee = 140f); armR(pitch = -70f, elbow = 80f); armL(pitch = 25f, abd = 40f); spine(pitch = -4f) },
            pose { legR(hip = -15f, knee = 142f); armR(pitch = -72f, elbow = 82f); armL(pitch = 27f, abd = 42f); spine(pitch = -4f) },
            pose { legL(hip = -14f, knee = 140f); armL(pitch = -70f, elbow = 80f); armR(pitch = 25f, abd = 40f); spine(pitch = -4f) },
            pose { legL(hip = -15f, knee = 142f); armL(pitch = -72f, elbow = 82f); armR(pitch = 27f, abd = 42f); spine(pitch = -4f) }),

        // Sit on the mat (butt grounded), legs extended forward and feet flexed,
        // then fold the torso over the legs reaching for the feet.
        Exercise("seated_fold", "SEATED FOLD", 8f, 90f, Db.NONE, t(0f, 0.45f, 0.65f),
            pose { body(pitch = 0f); legs(hip = 90f, knee = 5f, ankle = -15f); spine(pitch = 12f); arms(pitch = 70f, elbow = 20f); head(8f) },
            pose { body(pitch = 0f); legs(hip = 90f, knee = 5f, ankle = -15f); spine(pitch = 48f); arms(pitch = 92f, elbow = 6f); head(14f) },
            pose { body(pitch = 0f); legs(hip = 90f, knee = 5f, ankle = -15f); spine(pitch = 48f); arms(pitch = 92f, elbow = 6f); head(14f) }),

        // Sit on the mat, soles of the feet drawn together at the midline with
        // knees dropped out to the sides; hands cradle the feet.
        Exercise("butterfly", "BUTTERFLY", 7f, 20f, Db.NONE, t(0f, 0.5f),
            pose { legs(hip = 40f, abd = 80f, knee = 165f, ankle = 15f); spine(pitch = 12f); arms(pitch = 55f, elbow = 70f); head(6f) },
            pose { legs(hip = 42f, abd = 80f, knee = 166f, ankle = 15f); spine(pitch = 16f); arms(pitch = 57f, elbow = 72f); head(8f) }),

        Exercise("figure_four", "FIGURE FOUR", 16f, 90f, Db.NONE, t(0f, 0.42f, 0.5f, 0.92f),
            // The crossed ankle now reaches the opposite knee; the old pose
            // left the two landmarks more than half a metre apart.
            pose { body(pitch = -88f); legL(hip = 72f, knee = 88f); legR(hip = 120f, abd = -55f, knee = 115f); arms(pitch = 62f, elbow = 42f); head(10f) },
            pose { body(pitch = -88f); legL(hip = 75f, knee = 90f); legR(hip = 122f, abd = -55f, knee = 117f); arms(pitch = 64f, elbow = 44f); head(10f) },
            pose { body(pitch = -88f); legR(hip = 72f, knee = 88f); legL(hip = 120f, abd = -55f, knee = 115f); arms(pitch = 62f, elbow = 42f); head(10f) },
            pose { body(pitch = -88f); legR(hip = 75f, knee = 90f); legL(hip = 122f, abd = -55f, knee = 117f); arms(pitch = 64f, elbow = 44f); head(10f) }),

        Exercise("hip_flexor_lunge", "HIP FLEXOR", 16f, 85f, Db.NONE, t(0f, 0.42f, 0.5f, 0.92f),
            pose { legL(hip = 72f, knee = 92f); legR(hip = -22f, knee = 88f, ankle = 50f); spine(pitch = -8f); arms(pitch = 145f, elbow = 6f); head(-10f) },
            pose { legL(hip = 74f, knee = 94f); legR(hip = -24f, knee = 88f, ankle = 50f); spine(pitch = -10f); arms(pitch = 148f, elbow = 5f); head(-11f) },
            pose { legR(hip = 72f, knee = 92f); legL(hip = -22f, knee = 88f, ankle = 50f); spine(pitch = -8f); arms(pitch = 145f, elbow = 6f); head(-10f) },
            pose { legR(hip = 74f, knee = 94f); legL(hip = -24f, knee = 88f, ankle = 50f); spine(pitch = -10f); arms(pitch = 148f, elbow = 5f); head(-11f) }),
    ).also { arr -> for (e in arr) e.plant = e.key in PLANTED }
}
