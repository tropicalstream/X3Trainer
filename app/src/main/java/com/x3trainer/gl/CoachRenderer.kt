package com.x3trainer.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.x3trainer.engine.Trainer
import com.x3trainer.workout.Db
import com.x3trainer.workout.Exercises
import com.x3trainer.workout.Pose
import com.x3trainer.workout.Rig
import com.x3trainer.workout.WorkoutSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OpenGL ES 3.0 renderer for the mat-coach: a glowing full-body vector
 * skeleton performing the current exercise on a neon mat, with a stroke-font
 * HUD. The engine ticks on the main thread; this renderer only *reads* the
 * workout through a synchronized snapshot, then solves the pose FK locally —
 * nothing here mutates game state and nothing allocates per frame.
 * On the X3 the frame renders once per eye into side-by-side viewports.
 */
class CoachRenderer(private val engine: Trainer) : GLSurfaceView.Renderer {

    private var program = 0
    private var aPos = 0; private var aColor = 0
    private var uMVP = 0; private var uPointSize = 0; private var uPoint = 0; private var uAlpha = 0
    private var width = 1; private var height = 1
    private var lastNanos = 0L
    private var t = 0f

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val ortho = FloatArray(16)

    private val scene = Batch(9000)
    private val glow = Batch(2000)     // joint points
    private val hud = Batch(9000)

    private val rig = Rig()
    private val chans = FloatArray(Pose.N)
    private val snap = WorkoutSession.Snap()

    private var repPulse = 0f
    private var lastReps = -1

    // Cached HUD strings, rebuilt only when the value behind them changes.
    private var clockStr = "0:00"; private var lastClock = -1
    private var stepStr = ""; private var lastStep = -1
    private var counterStr = ""; private var lastCounterKey = Int.MIN_VALUE
    private var hrStr = "--BPM"; private var lastHr = -1; private var lastZone = -1
    private val sb = StringBuilder(24)

    // ------------------------------------------------------------ lifecycle

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram(VERT, FRAG)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aColor = GLES30.glGetAttribLocation(program, "aColor")
        uMVP = GLES30.glGetUniformLocation(program, "uMVP")
        uPointSize = GLES30.glGetUniformLocation(program, "uPointSize")
        uPoint = GLES30.glGetUniformLocation(program, "uPoint")
        uAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        Matrix.orthoM(ortho, 0, 0f, 640f, 480f, 0f, -1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now
        t += dt

        engine.workout.snapshot(snap)
        if (snap.reps != lastReps) { if (lastReps in 0 until snap.reps) repPulse = 1f; lastReps = snap.reps }
        repPulse = (repPulse - dt * 1.8f).coerceAtLeast(0f)

        buildScene()
        buildHud()

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        val sbs = engine.store.sbs
        val eyes = if (sbs) 2 else 1
        val vw = if (sbs) width / 2 else width
        val aspect = vw.toFloat() / height.toFloat()

        // Fixed studio camera with a slow, subtle orbit for 3D pop.
        val orbit = sin(t * 0.31f) * 0.45f
        Matrix.setLookAtM(view, 0, orbit, 1.35f, 3.4f, 0f, 0.72f, 0f, 0f, 1f, 0f)
        Matrix.perspectiveM(proj, 0, 42f, aspect, 0.1f, 50f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        for (e in 0 until eyes) {
            GLES30.glViewport(e * vw, 0, vw, height)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
            GLES30.glUniform1f(uPoint, 0f)
            // Two passes over the same geometry: a wide dim stroke then a
            // fine bright core — the vector-glow look without extra verts.
            GLES30.glLineWidth(5f)
            GLES30.glUniform1f(uAlpha, 0.30f)
            scene.draw(GLES30.GL_LINES)
            GLES30.glLineWidth(1.8f)
            GLES30.glUniform1f(uAlpha, 1f)
            scene.draw(GLES30.GL_LINES)
            GLES30.glUniform1f(uPoint, 1f)
            GLES30.glUniform1f(uPointSize, 9f)
            glow.draw(GLES30.GL_POINTS)

            GLES30.glUniformMatrix4fv(uMVP, 1, false, ortho, 0)
            GLES30.glUniform1f(uPoint, 0f)
            GLES30.glUniform1f(uAlpha, 1f)
            GLES30.glLineWidth(1.8f)
            hud.draw(GLES30.GL_LINES)
        }
    }

    // ---------------------------------------------------------------- scene

    private fun buildScene() {
        scene.reset(); glow.reset()
        buildFloor()
        buildMat()

        val ex = Exercises.ALL[snap.exId]
        ex.poseAt(snap.u, chans)
        rig.solve(chans, ex.viewYaw)

        // Rep pulse: a ring that blooms out across the floor on each rep.
        if (repPulse > 0.01f) {
            val r = 0.6f + (1f - repPulse) * 1.1f
            ring(0f, 0.004f, 0f, r, 20, 0.3f, 1f, 0.9f, 0.5f * repPulse)
        }

        // Soft ground anchor under the coach.
        ring(0f, 0.003f, 0f, 0.42f, 16, 0.25f, 0.5f, 0.9f, 0.10f)

        drawSkeleton()
        when (ex.db) {
            Db.PAIR -> { dumbbellAt(Rig.WRI_L, Rig.HAND_L, rig.barAxisL); dumbbellAt(Rig.WRI_R, Rig.HAND_R, rig.barAxisR) }
            Db.SINGLE -> singleDumbbell()
        }
    }

    private fun buildFloor() {
        val r = 0.30f; val g = 0.42f; val b = 0.85f; val a = 0.09f
        var x = -4f
        while (x <= 4f) { scene.line(x, 0f, -3f, x, 0f, 3f, r, g, b, a); x += 0.5f }
        var z = -3f
        while (z <= 3f) { scene.line(-4f, 0f, z, 4f, 0f, z, r, g, b, a); z += 0.5f }
    }

    private fun buildMat() {
        // The mat: a rounded neon rectangle, long axis across the view.
        val hw = 1.15f; val hd = 0.52f; val y = 0.006f; val c = 0.06f
        val r = 0.20f; val g = 0.95f; val b = 0.62f; val a = 0.55f
        // edges with chamfered corners
        scene.line(-hw + c, y, -hd, hw - c, y, -hd, r, g, b, a)
        scene.line(-hw + c, y, hd, hw - c, y, hd, r, g, b, a)
        scene.line(-hw, y, -hd + c, -hw, y, hd - c, r, g, b, a)
        scene.line(hw, y, -hd + c, hw, y, hd - c, r, g, b, a)
        scene.line(-hw + c, y, -hd, -hw, y, -hd + c, r, g, b, a)
        scene.line(hw - c, y, -hd, hw, y, -hd + c, r, g, b, a)
        scene.line(-hw + c, y, hd, -hw, y, hd - c, r, g, b, a)
        scene.line(hw - c, y, hd, hw, y, hd - c, r, g, b, a)
        // inner border
        val i = 0.07f
        scene.line(-hw + i, y, -hd + i, hw - i, y, -hd + i, r, g, b, 0.18f)
        scene.line(-hw + i, y, hd - i, hw - i, y, hd - i, r, g, b, 0.18f)
        scene.line(-hw + i, y, -hd + i, -hw + i, y, hd - i, r, g, b, 0.18f)
        scene.line(hw - i, y, -hd + i, hw - i, y, hd - i, r, g, b, 0.18f)
    }

    private val BONES = intArrayOf(
        Rig.PELVIS, Rig.CHEST, Rig.CHEST, Rig.NECK,
        Rig.HIP_L, Rig.HIP_R, Rig.SH_L, Rig.SH_R,
        Rig.HIP_L, Rig.KNEE_L, Rig.KNEE_L, Rig.ANKLE_L, Rig.ANKLE_L, Rig.TOE_L,
        Rig.HIP_R, Rig.KNEE_R, Rig.KNEE_R, Rig.ANKLE_R, Rig.ANKLE_R, Rig.TOE_R,
        Rig.SH_L, Rig.ELB_L, Rig.ELB_L, Rig.WRI_L, Rig.WRI_L, Rig.HAND_L,
        Rig.SH_R, Rig.ELB_R, Rig.ELB_R, Rig.WRI_R, Rig.WRI_R, Rig.HAND_R,
    )

    private val JOINT_DOTS = intArrayOf(
        Rig.PELVIS, Rig.CHEST, Rig.KNEE_L, Rig.KNEE_R, Rig.ANKLE_L, Rig.ANKLE_R,
        Rig.SH_L, Rig.SH_R, Rig.ELB_L, Rig.ELB_R, Rig.WRI_L, Rig.WRI_R,
    )

    private fun drawSkeleton() {
        val p = rig.pos
        val r = 0.35f; val g = 0.95f; val b = 1f
        var i = 0
        while (i < BONES.size) {
            val a0 = BONES[i] * 3; val b0 = BONES[i + 1] * 3
            scene.line(p[a0], p[a0 + 1], p[a0 + 2], p[b0], p[b0 + 1], p[b0 + 2], r, g, b, 0.95f)
            i += 2
        }
        // Head: a glowing circle above the neck.
        val hx = p[Rig.HEAD * 3]; val hy = p[Rig.HEAD * 3 + 1]; val hz = p[Rig.HEAD * 3 + 2]
        var prevX = hx + Rig.HEAD_R; var prevY = hy
        for (k in 1..12) {
            val an = k / 12f * 6.2832f
            val nx = hx + cos(an) * Rig.HEAD_R
            val ny = hy + sin(an) * Rig.HEAD_R
            scene.line(prevX, prevY, hz, nx, ny, hz, r, g, b, 0.95f)
            prevX = nx; prevY = ny
        }
        for (j in JOINT_DOTS) glow.v(p[j * 3], p[j * 3 + 1], p[j * 3 + 2], 0.8f, 1f, 1f, 0.75f)
    }

    /** A dumbbell in one hand: bar through the grip, plate crosses at the ends. */
    private fun dumbbellAt(wrist: Int, hand: Int, axis: FloatArray) {
        val p = rig.pos
        val cx = p[wrist * 3] * 0.4f + p[hand * 3] * 0.6f
        val cy = p[wrist * 3 + 1] * 0.4f + p[hand * 3 + 1] * 0.6f
        val cz = p[wrist * 3 + 2] * 0.4f + p[hand * 3 + 2] * 0.6f
        dumbbell(cx, cy, cz, axis[0], axis[1], axis[2], 0.11f)
    }

    /** One dumbbell held in both hands (goblet squats, weighted twists). */
    private fun singleDumbbell() {
        val p = rig.pos
        val lx = p[Rig.HAND_L * 3]; val ly = p[Rig.HAND_L * 3 + 1]; val lz = p[Rig.HAND_L * 3 + 2]
        val rx = p[Rig.HAND_R * 3]; val ry = p[Rig.HAND_R * 3 + 1]; val rz = p[Rig.HAND_R * 3 + 2]
        var ax = rx - lx; var ay = ry - ly; var az = rz - lz
        val len = sqrt(ax * ax + ay * ay + az * az)
        if (len < 0.15f) { ax = 0f; ay = 1f; az = 0f } else { ax /= len; ay /= len; az /= len }
        dumbbell((lx + rx) / 2f, (ly + ry) / 2f, (lz + rz) / 2f, ax, ay, az, 0.15f)
    }

    private fun dumbbell(cx: Float, cy: Float, cz: Float, ax: Float, ay: Float, az: Float, half: Float) {
        val r = 1f; val g = 0.25f; val b = 0.75f
        // perpendicular frame for the plates
        var px = ay * 0f - az * 1f; var py = az * 0f - ax * 0f; var pz = ax * 1f - ay * 0f  // axis x up
        var pl = sqrt(px * px + py * py + pz * pz)
        if (pl < 0.01f) { px = 1f; py = 0f; pz = 0f; pl = 1f }
        px /= pl; py /= pl; pz /= pl
        val qx = ay * pz - az * py; val qy = az * px - ax * pz; val qz = ax * py - ay * px
        val s = 0.05f
        scene.line(cx - ax * half, cy - ay * half, cz - az * half, cx + ax * half, cy + ay * half, cz + az * half, r, g, b, 1f)
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + ax * half * side; val ey = cy + ay * half * side; val ez = cz + az * half * side
            scene.line(ex - px * s, ey - py * s, ez - pz * s, ex + px * s, ey + py * s, ez + pz * s, r, g, b, 1f)
            scene.line(ex - qx * s, ey - qy * s, ez - qz * s, ex + qx * s, ey + qy * s, ez + qz * s, r, g, b, 1f)
            glow.v(ex, ey, ez, 1f, 0.4f, 0.85f, 0.9f)
        }
    }

    private fun ring(cx: Float, cy: Float, cz: Float, radius: Float, segs: Int, r: Float, g: Float, b: Float, a: Float) {
        var prevX = cx + radius; var prevZ = cz
        for (k in 1..segs) {
            val an = k.toFloat() / segs * 6.2832f
            val nx = cx + cos(an) * radius
            val nz = cz + sin(an) * radius
            scene.line(prevX, cy, prevZ, nx, cy, nz, r, g, b, a)
            prevX = nx; prevZ = nz
        }
    }

    // ------------------------------------------------------------------ HUD

    private var tr = 1f; private var tg = 1f; private var tb = 1f; private var ta = 1f
    private val sink = object : StrokeFont.LineSink {
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
            hud.line(x0, y0, 0f, x1, y1, 0f, tr, tg, tb, ta)
        }
    }

    private fun text(s: String, x: Float, y: Float, sc: Float, r: Float, g: Float, b: Float, a: Float = 1f) {
        tr = r; tg = g; tb = b; ta = a
        StrokeFont.draw(s, x, y, sc, sink)
    }

    private fun textC(s: String, cx: Float, y: Float, sc: Float, r: Float, g: Float, b: Float, a: Float = 1f) =
        text(s, cx - StrokeFont.width(s, sc) / 2f, y, sc, r, g, b, a)

    private fun textR(s: String, right: Float, y: Float, sc: Float, r: Float, g: Float, b: Float, a: Float = 1f) =
        text(s, right - StrokeFont.width(s, sc), y, sc, r, g, b, a)

    private fun clock(sec: Int): String {
        sb.setLength(0)
        sb.append(sec / 60).append(':')
        val s = sec % 60
        if (s < 10) sb.append('0')
        sb.append(s)
        return sb.toString()
    }

    private fun buildHud() {
        hud.reset()
        if (!snap.active) return

        // Top strip: program + level, total clock, step progress.
        text(snap.programName, 16f, 28f, 1.5f, 0.6f, 0.85f, 1f)
        text(snap.levelName, 16f, 50f, 1.1f, 0.55f, 0.6f, 0.75f)
        if (snap.totalSec != lastClock) { lastClock = snap.totalSec; clockStr = clock(snap.totalSec) }
        textR(clockStr, 624f, 28f, 1.5f, 1f, 1f, 1f)
        if (snap.stepIdx != lastStep) { lastStep = snap.stepIdx; stepStr = "STEP " + (snap.stepIdx + 1) + "/" + snap.stepCount }
        textR(stepStr, 624f, 50f, 1.1f, 0.55f, 0.6f, 0.75f)

        // Overall progress bar.
        val frac = ((snap.stepIdx + if (snap.phase > WorkoutSession.GETSET) 0.5f else 0f) / snap.stepCount).coerceIn(0f, 1f)
        hud.line(16f, 60f, 0f, 624f, 60f, 0f, 0.3f, 0.4f, 0.6f, 0.35f)
        if (frac > 0f) hud.line(16f, 60f, 0f, 16f + 608f * frac, 60f, 0f, 0.35f, 0.95f, 1f, 1f)

        when (snap.phase) {
            WorkoutSession.DONE -> {
                textC("WORKOUT COMPLETE!", 320f, 200f, 2.4f, 0.45f, 1f, 0.6f)
                val key = snap.totalSec * 1000 + snap.totalReps
                if (key != lastCounterKey) { lastCounterKey = key; counterStr = "TIME " + clock(snap.totalSec) + " - REPS " + snap.totalReps }
                textC(counterStr, 320f, 240f, 1.5f, 0.9f, 0.95f, 1f)
                val blink = 0.55f + 0.45f * sin(t * 5f)
                textC("TAP TO FINISH", 320f, 430f, 1.4f, 0.45f, 1f, 0.6f, blink)
            }
            else -> {
                // Exercise name + live counter beneath the coach.
                textC(snap.curName, 320f, 415f, 2.4f, 1f, 0.85f, 0.35f)
                val counterKey = snap.phase * 1000000 + snap.reps * 10000 + snap.secsLeft * 100 + snap.restLeft + snap.countdown
                if (counterKey != lastCounterKey) {
                    lastCounterKey = counterKey
                    counterStr = when {
                        snap.phase == WorkoutSession.GETSET -> "GET READY " + snap.countdown
                        snap.phase == WorkoutSession.REST -> "REST " + snap.restLeft
                        snap.timed -> clock(snap.secsLeft)
                        else -> "REP " + snap.reps + "/" + snap.repTarget
                    }
                }
                val cr = if (snap.phase == WorkoutSession.WORK) 1f else 0.45f
                val cg = if (snap.phase == WorkoutSession.WORK) 1f else 1f
                val cb = if (snap.phase == WorkoutSession.WORK) 1f else 0.6f
                textC(counterStr, 320f, 452f, 2f, cr, cg, cb)

                if (snap.paused) {
                    textC("PAUSED", 320f, 225f, 3f, 1f, 0.8f, 0.3f)
                    textC("TAP RESUME - DOUBLE TAP END", 320f, 260f, 1.2f, 0.8f, 0.8f, 0.9f)
                }
            }
        }

        // Bottom-left: live heart rate in its zone color (same data as the HUD).
        val hr = engine.hr; val zone = engine.zone
        if (hr != lastHr || zone != lastZone) {
            lastHr = hr; lastZone = zone
            hrStr = if (hr > 0) hr.toString() + "BPM Z" + zone else "--BPM"
        }
        if (hr > 0) {
            val zr: Float; val zg: Float; val zb: Float
            when (zone) {
                1 -> { zr = 0.5f; zg = 0.8f; zb = 1f }
                2 -> { zr = 0.4f; zg = 1f; zb = 0.6f }
                3 -> { zr = 1f; zg = 0.9f; zb = 0.4f }
                4 -> { zr = 1f; zg = 0.6f; zb = 0.3f }
                else -> { zr = 1f; zg = 0.35f; zb = 0.3f }
            }
            text(hrStr, 16f, 474f, 1.1f, zr, zg, zb)
        }
    }

    // -------------------------------------------------------------- plumbing

    private fun buildProgram(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, sh(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, sh(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        return p
    }

    inner class Batch(maxVerts: Int) {
        private val fb: FloatBuffer =
            ByteBuffer.allocateDirect(maxVerts * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val cap = maxVerts
        var count = 0; private set
        fun reset() { fb.position(0); count = 0 }
        fun v(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
            if (count >= cap) return
            fb.put(x); fb.put(y); fb.put(z); fb.put(r); fb.put(g); fb.put(b); fb.put(a); count++
        }
        fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, g: Float, b: Float, a: Float) {
            v(x0, y0, z0, r, g, b, a); v(x1, y1, z1, r, g, b, a)
        }
        fun draw(mode: Int) {
            if (count == 0) return
            fb.position(0); GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 28, fb); GLES30.glEnableVertexAttribArray(aPos)
            fb.position(3); GLES30.glVertexAttribPointer(aColor, 4, GLES30.GL_FLOAT, false, 28, fb); GLES30.glEnableVertexAttribArray(aColor)
            GLES30.glDrawArrays(mode, 0, count)
        }
    }

    companion object {
        private const val VERT = """#version 300 es
        in vec3 aPos; in vec4 aColor; uniform mat4 uMVP; uniform float uPointSize; out vec4 vColor;
        void main() { gl_Position = uMVP * vec4(aPos, 1.0); gl_PointSize = uPointSize; vColor = aColor; }"""
        private const val FRAG = """#version 300 es
        precision mediump float; in vec4 vColor; uniform float uPoint; uniform float uAlpha; out vec4 fragColor;
        void main() {
            if (uPoint > 0.5) { vec2 d = gl_PointCoord - vec2(0.5); float r2 = dot(d, d); if (r2 > 0.25) discard; fragColor = vec4(vColor.rgb, vColor.a * uAlpha * (1.0 - r2 * 4.0)); }
            else { fragColor = vec4(vColor.rgb, vColor.a * uAlpha); }
        }"""
    }
}
