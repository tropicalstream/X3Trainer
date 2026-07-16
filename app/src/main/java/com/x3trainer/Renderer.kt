package com.x3trainer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.x3trainer.engine.AppState
import com.x3trainer.engine.HrZones
import com.x3trainer.engine.Trainer
import com.x3trainer.workout.Levels
import com.x3trainer.workout.Programs
import kotlin.math.sin

/**
 * The HUD, drawn on a 640×480 logical canvas (black = transparent waveguide):
 *
 *   top 10%    (y 0..48)    — sports timer strip
 *   center 80% (y 48..432)  — EMPTY. The athlete's sightline. Only transient
 *                             celebrations, warnings, and the mode-switch
 *                             confirm prompt may appear here, briefly.
 *   bottom 10% (y 432..480) — normalized telemetry string, tinted by HR zone
 *
 * The disclaimer and settings screens are full-screen states, shown only
 * when not actively training.
 */
class Renderer(private val engine: Trainer, private val store: SettingsStore) {

    companion object {
        const val W = 640f
        const val H = 480f
        const val TOP_H = 48f
        const val BOT_Y = 432f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun draw(canvas: Canvas, viewW: Int, viewH: Int) {
        canvas.drawColor(Color.BLACK)
        if (viewW <= 0 || viewH <= 0) return
        canvas.save()
        canvas.scale(viewW / W, viewH / H)
        when (engine.state) {
            AppState.DISCLAIMER -> drawDisclaimer(canvas)
            AppState.SETTINGS -> drawSettings(canvas)
            AppState.HUD -> drawHud(canvas)
            AppState.WORKOUT_MENU -> drawWorkoutMenu(canvas)
            AppState.WORKOUT -> { /* GL surface owns the screen */ }
        }
        canvas.restore()
    }

    // ------------------------------------------------------------------ HUD

    private fun drawHud(c: Canvas) {
        drawTimerStrip(c)
        drawTelemetryStrip(c)
        drawCenterEvents(c)
    }

    private fun drawTimerStrip(c: Canvas) {
        val t = engine.timer
        val pending = engine.pendingMode

        // Mode label, left.
        paint.typeface = mono
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 20f
        paint.color = Color.rgb(160, 200, 255)
        c.drawText(t.mode.label, 12f, 32f, paint)

        // Clock, center — the headline number.
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 34f
        paint.color = if (t.running) Color.WHITE else Color.rgb(190, 190, 200)
        c.drawText(t.clockText(), W / 2f, 38f, paint)

        // Phase/round + run state, right.
        paint.textAlign = Paint.Align.RIGHT
        paint.textSize = 18f
        paint.color = when {
            t.finished -> Color.rgb(120, 255, 150)
            t.running -> Color.rgb(120, 255, 150)
            else -> Color.rgb(255, 200, 90)
        }
        val stateTxt = when {
            t.finished -> "DONE"
            t.running -> t.phaseText()
            else -> "PAUSED  " + t.phaseText()
        }
        c.drawText(stateTxt, W - 12f, 32f, paint)

        // Swipe+confirm prompt replaces the strip content's meaning: show it
        // just under the strip so the timer stays readable.
        if (pending != null) {
            val blink = 0.6f + 0.4f * sin(engine.time * 6f)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 20f
            paint.color = Color.argb((255 * blink).toInt(), 255, 220, 90)
            c.drawText("SWITCH TO ${pending.label}?  TAP TO CONFIRM", W / 2f, 74f, paint)
        }
    }

    private fun drawTelemetryStrip(c: Canvas) {
        val zone = engine.zone
        val zoneColor = if (engine.hr > 0) HrZones.color(zone) else Color.rgb(150, 160, 175)

        paint.typeface = mono
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 22f
        paint.color = zoneColor

        val hrTxt = if (engine.hr > 0) "${engine.hr}bpm Z$zone" else "--bpm"
        val cadTxt = if (engine.cadence > 0) "${engine.cadence}spm>${store.targetCadence}" else "--spm"
        val dv = engine.deltaV
        val dvTxt = if (dv > -90f) "%+.1fm/s".format(dv) else "--m/s"
        c.drawText("$hrTxt   $cadTxt   $dvTxt", W / 2f, BOT_Y + 30f, paint)

        // Source status, tiny, bottom-left; never louder than the data.
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 12f
        paint.color = Color.rgb(110, 120, 135)
        c.drawText(engine.sourceStatus, 8f, H - 8f, paint)

        // Mat-coach hint, equally tiny, bottom-right.
        paint.textAlign = Paint.Align.RIGHT
        c.drawText("swipe up: mat coach", W - 8f, H - 8f, paint)
    }

    /** Transient center-view content only: celebrations, warnings, red frame. */
    private fun drawCenterEvents(c: Canvas) {
        // Red flashing frame on device warnings.
        if (engine.warningFlash > 0.01f) {
            val pulse = (0.5f + 0.5f * sin(engine.time * 10f)) * engine.warningFlash
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 14f
            paint.color = Color.argb((230 * pulse).toInt(), 255, 40, 30)
            c.drawRect(7f, 7f, W - 7f, H - 7f, paint)
            paint.style = Paint.Style.FILL
        }
        engine.warningText?.let {
            paint.typeface = mono
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 26f
            paint.color = Color.rgb(255, 80, 60)
            c.drawText(it, W / 2f, H / 2f - 10f, paint)
        }
        engine.coach.celebration?.let {
            paint.typeface = mono
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 34f
            paint.color = Color.rgb(120, 255, 150)
            c.drawText(it, W / 2f, H / 2f + 40f, paint)
        }
    }

    // ----------------------------------------------------------- disclaimer

    private val disclaimerLines = listOf(
        "X3TRAINER - READ BEFORE USE" to 24f,
        "" to 10f,
        "HEALTH: This app provides general fitness" to 16f,
        "information, not medical advice. Consult a" to 16f,
        "physician before starting any exercise program." to 16f,
        "STOP if you feel pain, dizziness, chest pressure" to 16f,
        "or shortness of breath, and seek medical help." to 16f,
        "In an emergency call your local emergency number." to 16f,
        "" to 10f,
        "MOISTURE: These glasses are NOT waterproof." to 16f,
        "Heavy sweat or rain can damage the waveguides" to 16f,
        "and electronics - see the RayNeo glasses guide." to 16f,
        "" to 10f,
        "AWARENESS: Keep your eyes on the road. The" to 16f,
        "center of view stays clear by design - stay" to 16f,
        "alert to traffic, terrain and people around you." to 16f,
        "" to 10f,
        "TAP TO ACCEPT AND CONTINUE" to 20f,
    )

    private fun drawDisclaimer(c: Canvas) {
        paint.typeface = mono
        paint.textAlign = Paint.Align.CENTER
        var y = 60f
        for ((line, size) in disclaimerLines) {
            paint.textSize = size
            paint.color = when {
                line.startsWith("X3TRAINER") -> Color.rgb(120, 220, 255)
                line.startsWith("TAP TO") -> {
                    val blink = 0.6f + 0.4f * sin(engine.time * 4f)
                    Color.argb((255 * blink).toInt(), 120, 255, 150)
                }
                line.startsWith("HEALTH") || line.startsWith("MOISTURE") || line.startsWith("AWARENESS") ->
                    Color.rgb(255, 200, 90)
                else -> Color.rgb(210, 215, 225)
            }
            if (line.isNotEmpty()) c.drawText(line, W / 2f, y, paint)
            y += size * 1.35f
        }
    }

    // --------------------------------------------------------- workout menu

    private fun drawWorkoutMenu(c: Canvas) {
        paint.typeface = mono
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 26f
        paint.color = Color.rgb(120, 220, 255)
        c.drawText("MAT COACH", W / 2f, 44f, paint)
        paint.textSize = 12f
        paint.color = Color.rgb(140, 150, 165)
        c.drawText("swipe up/down = program · left/right = level · tap = start · double-tap = back", W / 2f, 66f, paint)

        // Level selector.
        paint.textSize = 20f
        paint.color = Color.rgb(255, 230, 120)
        c.drawText("< ${Levels.NAMES[engine.wkLevel]} >", W / 2f, 100f, paint)

        // Program list with the selection expanded.
        var y = 140f
        for ((i, p) in Programs.ALL.withIndex()) {
            val sel = i == engine.wkProgram
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = if (sel) 22f else 17f
            paint.color = if (sel) Color.WHITE else Color.rgb(170, 178, 190)
            c.drawText((if (sel) "> " else "  ") + p.name, 70f, y, paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = 15f
            paint.color = if (sel) Color.rgb(120, 255, 150) else Color.rgb(120, 128, 140)
            val mins = p.estimateMin(engine.wkLevel)
            c.drawText("${p.steps.size} moves · ~$mins min", W - 70f, y, paint)
            if (sel) {
                y += 24f
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = 14f
                paint.color = Color.rgb(160, 200, 255)
                c.drawText(p.tagline + if (p.weights) "  —  DUMBBELLS NEEDED" else "", 88f, y, paint)
            }
            y += 34f
        }

        val blink = 0.6f + 0.4f * sin(engine.time * 4f)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 18f
        paint.color = Color.argb((255 * blink).toInt(), 120, 255, 150)
        c.drawText("TAP TO START", W / 2f, H - 24f, paint)
    }

    // ------------------------------------------------------------- settings

    private fun drawSettings(c: Canvas) {
        paint.typeface = mono
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 24f
        paint.color = Color.rgb(120, 220, 255)
        c.drawText("SETTINGS", W / 2f, 40f, paint)
        paint.textSize = 12f
        paint.color = Color.rgb(140, 150, 165)
        c.drawText("swipe = navigate/adjust · tap = select · double-tap = back", W / 2f, 62f, paint)

        val menu = engine.menu
        val visible = 11
        val first = (menu.selected - visible / 2).coerceIn(0, maxOf(0, menu.items.size - visible))
        var y = 96f
        for (i in first until minOf(menu.items.size, first + visible)) {
            val item = menu.items[i]
            val sel = i == menu.selected
            paint.textSize = if (sel) 20f else 17f
            paint.textAlign = Paint.Align.LEFT
            paint.color = if (sel) Color.rgb(255, 230, 120) else Color.rgb(190, 195, 205)
            c.drawText((if (sel) "> " else "  ") + item.label, 90f, y, paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.color = if (sel) Color.WHITE else Color.rgb(160, 165, 175)
            c.drawText(item.value(), W - 90f, y, paint)
            y += 32f
        }
    }
}
