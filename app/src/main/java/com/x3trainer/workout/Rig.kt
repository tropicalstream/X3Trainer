package com.x3trainer.workout

import android.opengl.Matrix

/**
 * Forward-kinematics solver: pose channels -> 20 world-space joint positions.
 * The skeleton is auto-grounded — after solving, everything is shifted so its
 * lowest point touches the floor (y = 0), which is what makes squats sink,
 * push-ups lower and glute bridges lift without hand-tuning a root height per
 * keyframe. HOP is added after grounding for jumps.
 *
 * Everything is preallocated: solve() is called on the GL thread every frame
 * and allocates nothing (per-frame allocation = GC stutter on the X3).
 */
class Rig {

    companion object {
        const val PELVIS = 0; const val CHEST = 1; const val NECK = 2; const val HEAD = 3
        const val HIP_L = 4; const val KNEE_L = 5; const val ANKLE_L = 6; const val TOE_L = 7
        const val HIP_R = 8; const val KNEE_R = 9; const val ANKLE_R = 10; const val TOE_R = 11
        const val SH_L = 12; const val ELB_L = 13; const val WRI_L = 14; const val HAND_L = 15
        const val SH_R = 16; const val ELB_R = 17; const val WRI_R = 18; const val HAND_R = 19
        const val JOINTS = 20

        // Proportions (meters-ish, ~1.75 m coach).
        private const val THIGH = 0.42f
        private const val SHIN = 0.42f
        private const val SPINE = 0.36f
        private const val UARM = 0.30f
        private const val FARM = 0.27f
        const val HEAD_R = 0.10f

        // Contact radius per joint — the body's thickness for grounding.
        // Without this the line skeleton grounds on the head sphere alone and
        // every lying pose tilts (head touches, torso floats).
        private val CONTACT = floatArrayOf(
            0.09f, 0.09f, 0.08f, HEAD_R,          // pelvis chest neck head
            0.09f, 0.045f, 0.045f, 0.02f,          // left leg
            0.09f, 0.045f, 0.045f, 0.02f,          // right leg
            0.09f, 0.045f, 0.045f, 0.02f,          // left arm
            0.09f, 0.045f, 0.045f, 0.02f,          // right arm
        )
    }

    /** World-space joint positions, xyz per joint, valid after solve(). */
    val pos = FloatArray(JOINTS * 3)

    /** Dumbbell bar axes (forearm-frame X), valid after solve(). */
    val barAxisL = FloatArray(3)
    val barAxisR = FloatArray(3)

    private val mRoot = FloatArray(16)
    private val mChest = FloatArray(16)
    private val mA = FloatArray(16)
    private val v4 = FloatArray(4)
    private val v4b = FloatArray(4)

    private fun put(j: Int, m: FloatArray, x: Float, y: Float, z: Float) {
        v4[0] = x; v4[1] = y; v4[2] = z; v4[3] = 1f
        Matrix.multiplyMV(v4b, 0, m, 0, v4, 0)
        pos[j * 3] = v4b[0]; pos[j * 3 + 1] = v4b[1]; pos[j * 3 + 2] = v4b[2]
    }

    fun solve(ch: FloatArray, extraYaw: Float) {
        Matrix.setIdentityM(mRoot, 0)
        Matrix.rotateM(mRoot, 0, ch[Pose.BYAW] + extraYaw, 0f, 1f, 0f)
        Matrix.rotateM(mRoot, 0, ch[Pose.BPITCH], 1f, 0f, 0f)
        Matrix.rotateM(mRoot, 0, ch[Pose.BROLL], 0f, 0f, 1f)
        put(PELVIS, mRoot, 0f, 0f, 0f)

        // Legs. Hip flexion is negative X rotation (thigh swings toward +Z).
        leg(HIP_L, 0.09f, ch[Pose.HIPPL], ch[Pose.HABDL], ch[Pose.KNEEL], ch[Pose.ANKL])
        leg(HIP_R, -0.09f, ch[Pose.HIPPR], -ch[Pose.HABDR], ch[Pose.KNEER], ch[Pose.ANKR])

        // Spine and head.
        System.arraycopy(mRoot, 0, mChest, 0, 16)
        Matrix.rotateM(mChest, 0, ch[Pose.SROLL], 0f, 0f, 1f)
        Matrix.rotateM(mChest, 0, ch[Pose.SYAW], 0f, 1f, 0f)
        Matrix.rotateM(mChest, 0, ch[Pose.SPITCH], 1f, 0f, 0f)
        put(CHEST, mChest, 0f, SPINE, 0f)
        Matrix.translateM(mChest, 0, 0f, SPINE, 0f)
        System.arraycopy(mChest, 0, mA, 0, 16)
        Matrix.rotateM(mA, 0, ch[Pose.HPITCH], 1f, 0f, 0f)
        put(NECK, mA, 0f, 0.10f, 0f)
        put(HEAD, mA, 0f, 0.27f, 0f)

        // Arms.
        arm(SH_L, 0.20f, ch[Pose.SHPL], ch[Pose.SHABDL], ch[Pose.ELBL], barAxisL)
        arm(SH_R, -0.20f, ch[Pose.SHPR], -ch[Pose.SHABDR], ch[Pose.ELBR], barAxisR)

        // Ground: shift so the lowest contact point (joint minus its body
        // thickness) rests on the floor, then apply the hop.
        var minY = Float.MAX_VALUE
        for (j in 0 until JOINTS) {
            val y = pos[j * 3 + 1] - CONTACT[j]
            if (y < minY) minY = y
        }
        val lift = -minY + ch[Pose.HOP]
        for (j in 0 until JOINTS) pos[j * 3 + 1] += lift
    }

    private fun leg(hipJoint: Int, side: Float, hip: Float, abd: Float, knee: Float, ankle: Float) {
        System.arraycopy(mRoot, 0, mA, 0, 16)
        Matrix.translateM(mA, 0, side, -0.03f, 0f)
        put(hipJoint, mA, 0f, 0f, 0f)
        Matrix.rotateM(mA, 0, abd, 0f, 0f, 1f)
        Matrix.rotateM(mA, 0, -hip, 1f, 0f, 0f)
        put(hipJoint + 1, mA, 0f, -THIGH, 0f)                    // knee
        Matrix.translateM(mA, 0, 0f, -THIGH, 0f)
        Matrix.rotateM(mA, 0, knee, 1f, 0f, 0f)
        put(hipJoint + 2, mA, 0f, -SHIN, 0f)                     // ankle
        Matrix.translateM(mA, 0, 0f, -SHIN, 0f)
        Matrix.rotateM(mA, 0, ankle, 1f, 0f, 0f)
        put(hipJoint + 3, mA, 0f, -0.04f, 0.20f)                 // toe
    }

    private fun arm(shJoint: Int, side: Float, pitch: Float, abd: Float, elbow: Float, barAxis: FloatArray) {
        System.arraycopy(mChest, 0, mA, 0, 16)
        Matrix.translateM(mA, 0, side, 0.10f, 0f)
        put(shJoint, mA, 0f, 0f, 0f)
        Matrix.rotateM(mA, 0, abd, 0f, 0f, 1f)
        Matrix.rotateM(mA, 0, -pitch, 1f, 0f, 0f)
        put(shJoint + 1, mA, 0f, -UARM, 0f)                      // elbow
        Matrix.translateM(mA, 0, 0f, -UARM, 0f)
        Matrix.rotateM(mA, 0, -elbow, 1f, 0f, 0f)
        put(shJoint + 2, mA, 0f, -FARM, 0f)                      // wrist
        barAxis[0] = mA[0]; barAxis[1] = mA[1]; barAxis[2] = mA[2]
        Matrix.translateM(mA, 0, 0f, -FARM, 0f)
        put(shJoint + 3, mA, 0f, -0.09f, 0f)                     // hand tip
    }
}
