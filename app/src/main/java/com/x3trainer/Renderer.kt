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
        // Timer type, then the activity: the numbers below mean different
        // things in each, so which one is live has to be visible.
        c.drawText(t.mode.label + "  " + store.exerciseMode.label.uppercase(), 12f, 32f, paint)

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
            // Below the telemetry line, which now occupies the space this
            // prompt used to have to itself.
            c.drawText("SWITCH TO ${pending.label}?  TAP TO CONFIRM", W / 2f, 104f, paint)
        }
    }

    private fun drawTelemetryStrip(c: Canvas) {
        val zone = engine.zone
        val zoneColor = if (engine.hr > 0) HrZones.color(zone) else Color.rgb(150, 160, 175)

        paint.typeface = mono
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 22f
        paint.color = zoneColor

        val mode = store.exerciseMode
        val hrTxt = if (engine.hr > 0) "${engine.hr}bpm Z$zone" else "--bpm"
        // A bike has no footfalls to report. Showing "--spm" there would imply
        // a reading that is merely missing, when in fact it does not apply —
        // so the field is dropped from the line entirely.
        val cadTxt = when {
            !mode.stepCadence -> ""
            engine.cadence > 0 -> "${engine.cadence}spm>${store.targetCadence}"
            else -> "--spm"
        }
        // ACTUAL SPEED, NOT THE DIFFERENCE. This showed deltaV — how far off
        // target pace the wearer was — which is a signed number that reads as
        // a negative SPEED to anyone glancing at "m/s", and walking honestly
        // produced "-1.4m/s". Show the measured speed against its target, the
        // way cadence already does, and let the coach keep the difference to
        // itself: the wearer can see the gap without doing the subtraction.
        val spd = engine.speedMps
        val spdUnit = if (store.imperial) "mph" else "m/s"
        val spdTxt = if (spd >= 0f)
            store.speedValue(spd) + spdUnit + ">" + store.speedValue(store.targetVelocityMps)
        else "--$spdUnit"
        val kcalTxt = "${engine.kcal.toInt()}>${store.targetKcal}kcal"
        val line = listOf(hrTxt, cadTxt, spdTxt, kcalTxt)
            .filter { it.isNotEmpty() }
            .joinToString("   ")
        c.drawText(line, W / 2f, telemetryBaseline(), paint)

        // Source status, tiny, bottom-left; never louder than the data.
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 12f
        paint.color = Color.rgb(110, 120, 135)
        c.drawText(engine.sourceStatus, 8f, H - 8f, paint)

        // Mat-coach hint, equally tiny, bottom-right.
        paint.textAlign = Paint.Align.RIGHT
        c.drawText("swipe up: mat coach", W - 8f, H - 8f, paint)
    }

    /**
     * Where the telemetry line sits.
     *
     * It used to live in the bottom band, which respected the layout contract
     * but not the wearer's eyes: on a waveguide the bottom edge sits at a
     * different focal distance from the timer, so reading a pulse meant
     * refocusing away from the clock and back again mid-exercise. Tucked
     * directly beneath the timer strip, both are one glance at one depth.
     *
     * The centre stays empty either way — that is the part of the contract
     * that matters, and this moves the line UP into the band the timer
     * already occupies rather than down into the sightline.
     */
    private fun telemetryBaseline(): Float = TOP_H + 24f

    /** Transient center-view content only: celebrations, warnings, red frame. */
    private fun drawCenterEvents(c: Canvas) {
        // A lost link is reported along the BOTTOM, pulsing, and nowhere
        // else. The red full-screen frame and the centre-of-vision text that
        // used to announce it were an emergency's worth of alarm for a phone
        // in the wrong pocket — and the centre is the one region the layout
        // contract exists to keep empty.
        engine.warningText?.let {
            val pulse = 0.45f + 0.55f * (0.5f + 0.5f * sin(engine.time * 5f))
            paint.typeface = mono
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 16f
            paint.color = Color.argb((255 * pulse).toInt(), 255, 70, 55)
            c.drawText(it, W / 2f, H - 30f, paint)
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
        var y = 128f
        for ((i, p) in Programs.ALL.withIndex()) {
            val sel = i == engine.wkProgram
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = if (sel) 20f else 16f
            paint.color = if (sel) Color.WHITE else Color.rgb(170, 178, 190)
            c.drawText((if (sel) "> " else "  ") + p.name, 70f, y, paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = 14f
            paint.color = if (sel) Color.rgb(120, 255, 150) else Color.rgb(120, 128, 140)
            val mins = p.estimateMin(engine.wkLevel)
            c.drawText("${p.steps.size} moves · ~$mins min", W - 70f, y, paint)
            if (sel) {
                y += 21f
                paint.textAlign = Paint.Align.LEFT
                paint.textSize = 13f
                paint.color = Color.rgb(160, 200, 255)
                c.drawText(p.tagline + if (p.weights) "  —  DUMBBELLS NEEDED" else "", 88f, y, paint)
            }
            y += 29f
        }

        // History footer — streak and weekly count keep the habit visible.
        val total = engine.log.totalSessions()
        if (total > 0) {
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 14f
            paint.color = Color.rgb(150, 200, 255)
            val streak = engine.log.streakDays()
            val week = engine.log.weekCount()
            val done = engine.log.completions(engine.wkProgram, engine.wkLevel)
            var line = "this week: $week · total: $total" +
                (if (streak >= 2) " · streak: $streak days" else "")
            if (done in 1..2) line += " · this program: $done/3 to level up"
            else if (done >= 3 && engine.wkLevel < 2) line += " · ready for the next level!"
            c.drawText(line, W / 2f, H - 46f, paint)
        }

        val blink = 0.6f + 0.4f * sin(engine.time * 4f)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 18f
        paint.color = Color.argb((255 * blink).toInt(), 120, 255, 150)
        c.drawText("TAP TO START", W / 2f, H - 24f, paint)
    }

    // ------------------------------------------------------------- settings

    private fun menuEditing(): Boolean = engine.menu.editing

    private fun drawSettings(c: Canvas) {
        paint.typeface = mono
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 24f
        paint.color = Color.rgb(120, 220, 255)
        c.drawText("SETTINGS", W / 2f, 40f, paint)
        paint.textSize = 12f
        paint.color = Color.rgb(140, 150, 165)
        // The hint states the CURRENT meaning of a swipe, not a general
        // description of both — a wearer glancing at this needs to know what
        // their next swipe will do, which depends on the mode they are in.
        c.drawText(
            if (menuEditing()) "swipe = change value · tap = done · double-tap = back"
            else "swipe = move · tap = open · double-tap = back",
            W / 2f, 62f, paint
        )

        val menu = engine.menu
        val visible = 11
        val first = (menu.selected - visible / 2).coerceIn(0, maxOf(0, menu.items.size - visible))
        var y = 96f
        for (i in first until minOf(menu.items.size, first + visible)) {
            val item = menu.items[i]
            val sel = i == menu.selected
            val edit = sel && menu.editing
            paint.textSize = if (sel) 20f else 17f
            paint.textAlign = Paint.Align.LEFT
            paint.color = if (sel) Color.rgb(255, 230, 120) else Color.rgb(190, 195, 205)
            c.drawText((if (sel) "> " else "  ") + item.label, 90f, y, paint)
            paint.textAlign = Paint.Align.RIGHT
            // In edit mode the value is bracketed by arrows and turns green:
            // the wearer must be able to tell at a glance that their next
            // swipe changes THIS number rather than moving off it.
            paint.color = when {
                edit -> Color.rgb(120, 255, 150)
                sel -> Color.WHITE
                else -> Color.rgb(160, 165, 175)
            }
            val shown = if (edit) "< " + item.value() + " >" else item.value()
            c.drawText(shown, W - 90f, y, paint)
            y += 32f
        }
    }
}
