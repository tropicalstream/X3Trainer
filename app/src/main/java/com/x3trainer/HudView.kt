package com.x3trainer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.Choreographer
import android.view.View
import com.x3trainer.engine.Trainer

/**
 * Choreographer render loop at 30 fps draw (vendor thermal guidance: the HUD
 * is mostly static text; the engine still ticks at full callback rate).
 */
class HudView(
    context: Context,
    private val engine: Trainer,
    private val renderer: Renderer,
) : View(context), Choreographer.FrameCallback {

    private var running = false
    private var lastNanos = 0L
    private var drawAccum = 0f

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.BLACK)
    }

    fun start() {
        if (!running) {
            running = true
            lastNanos = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastNanos == 0L) 0.016f
        else ((frameTimeNanos - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = frameTimeNanos
        engine.update(dt)
        drawAccum += dt
        if (drawAccum >= 1f / 31f) {
            drawAccum = 0f
            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, width, height)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
