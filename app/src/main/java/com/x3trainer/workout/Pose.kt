package com.x3trainer.workout

import kotlin.math.PI
import kotlin.math.cos

/**
 * A pose is a flat channel array of joint angles (degrees) plus a hop height.
 * Exercises are lists of keyframed poses; playback cosine-eases between keys
 * and wraps, so every animation is a seamless loop. All channels default to 0,
 * which is the coach standing upright, arms at the sides, facing +Z.
 */
object Pose {
    const val HOP = 0        // extra height added after floor-grounding (jumps)
    const val BPITCH = 1     // whole body pitch: + falls forward
    const val BYAW = 2
    const val BROLL = 3
    const val SPITCH = 4     // spine flexion: + curls forward
    const val SYAW = 5       // spine twist
    const val SROLL = 6
    const val HPITCH = 7     // head nod: + looks down
    const val HIPPL = 8      // hip flexion: + thigh raises forward
    const val HIPPR = 9
    const val HABDL = 10     // hip abduction: + leg out to the side
    const val HABDR = 11
    const val KNEEL = 12     // knee bend: + heel kicks back
    const val KNEER = 13
    const val ANKL = 14      // ankle: + points the toes
    const val ANKR = 15
    const val SHPL = 16      // shoulder flexion: + arm raises forward (180 = overhead)
    const val SHPR = 17
    const val SHABDL = 18    // shoulder abduction: + arm out to the side
    const val SHABDR = 19
    const val ELBL = 20      // elbow bend: + hand curls forward/up
    const val ELBR = 21
    const val N = 22
}

/** Pose builder: symmetric helpers keep exercise definitions short. */
class P {
    val c = FloatArray(Pose.N)
    fun hop(v: Float) { c[Pose.HOP] = v }
    fun body(pitch: Float = 0f, yaw: Float = 0f, roll: Float = 0f) {
        c[Pose.BPITCH] = pitch; c[Pose.BYAW] = yaw; c[Pose.BROLL] = roll
    }
    fun spine(pitch: Float = 0f, yaw: Float = 0f, roll: Float = 0f) {
        c[Pose.SPITCH] = pitch; c[Pose.SYAW] = yaw; c[Pose.SROLL] = roll
    }
    fun head(pitch: Float) { c[Pose.HPITCH] = pitch }
    fun legL(hip: Float = 0f, abd: Float = 0f, knee: Float = 0f, ankle: Float = 0f) {
        c[Pose.HIPPL] = hip; c[Pose.HABDL] = abd; c[Pose.KNEEL] = knee; c[Pose.ANKL] = ankle
    }
    fun legR(hip: Float = 0f, abd: Float = 0f, knee: Float = 0f, ankle: Float = 0f) {
        c[Pose.HIPPR] = hip; c[Pose.HABDR] = abd; c[Pose.KNEER] = knee; c[Pose.ANKR] = ankle
    }
    fun legs(hip: Float = 0f, abd: Float = 0f, knee: Float = 0f, ankle: Float = 0f) {
        legL(hip, abd, knee, ankle); legR(hip, abd, knee, ankle)
    }
    fun armL(pitch: Float = 0f, abd: Float = 0f, elbow: Float = 0f) {
        c[Pose.SHPL] = pitch; c[Pose.SHABDL] = abd; c[Pose.ELBL] = elbow
    }
    fun armR(pitch: Float = 0f, abd: Float = 0f, elbow: Float = 0f) {
        c[Pose.SHPR] = pitch; c[Pose.SHABDR] = abd; c[Pose.ELBR] = elbow
    }
    fun arms(pitch: Float = 0f, abd: Float = 0f, elbow: Float = 0f) {
        armL(pitch, abd, elbow); armR(pitch, abd, elbow)
    }
}

fun pose(build: P.() -> Unit): FloatArray = P().apply(build).c

/** Dumbbell rendering mode for an exercise. */
object Db { const val NONE = 0; const val PAIR = 1; const val SINGLE = 2 }

/**
 * One exercise: a display name, a looped keyframe animation (times ascending
 * in 0..<1, wrapping back to the first key at 1), the seconds one full cycle
 * (= one rep) takes at normal tempo, the camera-facing yaw that reads best,
 * and whether the coach holds dumbbells.
 */
class Exercise(
    val key: String,          // phrase id suffix: cue_<key>
    val name: String,
    val cycleSec: Float,
    val viewYaw: Float,
    val db: Int,
    val times: FloatArray,
    vararg val keys: FloatArray,
) {
    /** Standing exercise whose feet stay planted — anchor them (set in Exercises). */
    var plant: Boolean = false

    /** Interpolate the loop at phase u (0..1) into out. Allocation-free. */
    fun poseAt(u: Float, out: FloatArray) {
        val uu = ((u % 1f) + 1f) % 1f
        var i = times.size - 1
        for (k in 0 until times.size - 1) if (uu >= times[k] && uu < times[k + 1]) { i = k; break }
        val j = (i + 1) % times.size
        val t0 = times[i]
        val t1 = if (j == 0) 1f else times[j]
        val span = t1 - t0
        val s = if (span <= 1e-4f) 0f else ((uu - t0) / span).coerceIn(0f, 1f)
        val e = 0.5f - 0.5f * cos(PI.toFloat() * s)   // ease in/out
        val a = keys[i]; val b = keys[j]
        for (k in 0 until Pose.N) out[k] = a[k] + (b[k] - a[k]) * e
    }
}
