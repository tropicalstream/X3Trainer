package com.x3trainer

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.x3trainer.audio.CoachVoice
import com.x3trainer.audio.Sfx
import com.x3trainer.engine.Trainer
import com.x3trainer.engine.TrainerHost
import com.x3trainer.gl.CoachRenderer
import com.x3trainer.telemetry.BleSource
import com.x3trainer.telemetry.DemoSource
import com.x3trainer.telemetry.TelemetrySource
import com.x3trainer.workout.WorkoutLog
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Input (FABLE_X3_STARTER_GUIDE Part II):
 *  - Temple click arrives as a KEY (BUTTON_A / DPAD_CENTER). Taps are
 *    classified by count with a 320 ms gap window: single = timer
 *    start/pause (or accept/confirm), double = timer reset, TRIPLE = settings.
 *  - Right pad swipe -> one discrete direction per gesture on finger-up:
 *    in the HUD, left/right proposes a timer-mode switch (tap to confirm);
 *    in settings, navigates.
 *  - Left pad (cyttsp6) swallowed. Touchscreen (phone) reuses the gestures.
 */
class MainActivity : Activity(), TrainerHost {

    private val TAG = "X3Trainer"
    private val TAP_GAP_MS = 320L

    private lateinit var store: SettingsStore
    private lateinit var sfx: Sfx
    private lateinit var voice: CoachVoice
    private lateinit var engine: Trainer
    private lateinit var renderer: Renderer
    private lateinit var hudView: HudView
    private lateinit var sbsRoot: BinocularSbsLayout
    private lateinit var glView: GLSurfaceView
    private var glActive = false

    private val handler = Handler(Looper.getMainLooper())
    private var telemetry: TelemetrySource? = null

    private var keyDownAt = 0L
    private var tapCount = 0
    private var tapGuard = 0L
    private var pendingTaps: Runnable? = null

    private var touchActive = false
    private var touchStartT = 0L
    private var sumX = 0f
    private var sumY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dropFirst = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        sfx = Sfx(this).also { it.loadAsync() }
        voice = CoachVoice(this).also { it.load() }
        engine = Trainer(store, this, WorkoutLog(this))
        renderer = Renderer(engine, store)
        hudView = HudView(this, engine, renderer)
        sbsRoot = BinocularSbsLayout(this).apply { addView(hudView) }

        // The mat-coach: a GL surface that sits under the HUD view and is
        // revealed only while a workout runs. The engine keeps ticking on the
        // main thread (HudView's Choreographer); the GL renderer only reads.
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(CoachRenderer(engine))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            visibility = View.GONE
        }
        glView.onPause()

        val root = FrameLayout(this)
        root.addView(glView)
        root.addView(sbsRoot)
        setContentView(root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        applySettings()
        engine.boot()
    }

    // ---------------------------------------------------------- TrainerHost

    override fun applySettings() {
        sfx.volume = store.soundVolume / 10f
        voice.volume = store.voiceVolume / 10f
        sbsRoot.sbsEnabled = store.sbs
        Log.i(TAG, "applySettings sbs=${store.sbs} src=${store.dataSource}")
    }

    override fun workoutSurface(active: Boolean) {
        runOnUiThread {
            if (active == glActive) return@runOnUiThread
            glActive = active
            if (active) {
                glView.visibility = View.VISIBLE
                glView.onResume()
                sbsRoot.visibility = View.GONE
            } else {
                glView.onPause()
                glView.visibility = View.GONE
                sbsRoot.visibility = View.VISIBLE
            }
        }
    }

    override fun sound(id: Int, pitch: Float, vol: Float) = sfx.play(id, pitch, vol)

    override fun say(id: String, urgent: Boolean) = voice.say(id, urgent)

    override fun sayLive(text: String) = voice.sayLive(text)

    override fun rebindTelemetry() {
        telemetry?.stop()
        telemetry = when (store.dataSource) {
            1 -> {
                if (ensureBlePermissions()) BleSource(this)
                else DemoSource() // permissions pending; user re-selects after grant
            }
            else -> DemoSource()
        }
        telemetry?.start(engine)
    }

    private fun ensureBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val needed = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) return true
        requestPermissions(needed.toTypedArray(), 71)
        return false
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 71 && res.all { it == PackageManager.PERMISSION_GRANTED }) rebindTelemetry()
    }

    // --------------------------------------------------------------- input

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isTapKey = event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER
        if (isTapKey) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) keyDownAt = SystemClock.uptimeMillis()
                KeyEvent.ACTION_UP -> {
                    val now = SystemClock.uptimeMillis()
                    if (now - keyDownAt <= 400) registerTap(now)
                }
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val dir = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> 0
                KeyEvent.KEYCODE_DPAD_DOWN -> 1
                KeyEvent.KEYCODE_DPAD_LEFT -> 2
                KeyEvent.KEYCODE_DPAD_RIGHT -> 3
                else -> -1
            }
            if (dir >= 0) { engine.swipeDir(dir); return true }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            if (engine.onBack()) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Count taps inside the gap window, dispatch when the chain settles:
     * 1 = tap, 2 = double, 3+ = triple (settings). The wait adds ~320 ms of
     * latency to start/pause — the price of a reliable triple-tap.
     */
    private fun registerTap(now: Long) {
        if (now - tapGuard < 35) return // keycode echo filter
        tapGuard = now
        tapCount++
        pendingTaps?.let { handler.removeCallbacks(it) }
        if (tapCount >= 3) {
            tapCount = 0
            pendingTaps = null
            engine.tripleTap()
            return
        }
        val r = Runnable {
            val n = tapCount
            tapCount = 0
            pendingTaps = null
            when (n) {
                1 -> engine.tap()
                2 -> engine.doubleTap()
            }
        }
        pendingTaps = r
        handler.postDelayed(r, TAP_GAP_MS)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val name = ev.device?.name ?: ""
        if (name.contains("cyttsp6", ignoreCase = true)) return true

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchActive = true; touchStartT = SystemClock.uptimeMillis()
                sumX = 0f; sumY = 0f; lastX = ev.x; lastY = ev.y; dropFirst = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchActive) {
                    touchActive = true; touchStartT = SystemClock.uptimeMillis()
                    sumX = 0f; sumY = 0f; lastX = ev.x; lastY = ev.y; dropFirst = true
                } else {
                    val dx = ev.x - lastX; val dy = ev.y - lastY
                    lastX = ev.x; lastY = ev.y
                    if (dropFirst) dropFirst = false else { sumX += dx; sumY += dy }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (touchActive) resolveGesture(SystemClock.uptimeMillis())
                touchActive = false
            }
            MotionEvent.ACTION_CANCEL -> touchActive = false
        }
        return true
    }

    /** One discrete direction per gesture, classified on finger-up (suite convention). */
    private fun resolveGesture(now: Long) {
        val dist = sqrt(sumX * sumX + sumY * sumY)
        val threshold = max(48f, 0.09f * resources.displayMetrics.widthPixels) / store.swipeSens
        if (dist >= threshold) {
            val dir = if (abs(sumX) >= abs(sumY)) { if (sumX > 0) 3 else 2 } else { if (sumY < 0) 0 else 1 }
            engine.swipeDir(dir)
        } else if (now - touchStartT <= 320) {
            registerTap(now)
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        applySettings()
        hudView.start()
        if (glActive) glView.onResume()
        if (telemetry == null && store.disclaimerAccepted) rebindTelemetry()
    }

    override fun onPause() {
        engine.onAppPause()
        hudView.stop()
        if (glActive) glView.onPause()
        telemetry?.stop()
        telemetry = null
        super.onPause()
    }

    override fun onDestroy() {
        sfx.release()
        voice.release()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}
