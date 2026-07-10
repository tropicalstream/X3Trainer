package com.x3trainer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Synthesized UI/timer cues (no audio binaries ship). Voice lives in CoachVoice. */
class Sfx(private val context: Context) {

    companion object {
        const val TICK = 0        // menu step
        const val SELECT = 1      // menu activate / accept
        const val START = 2       // timer start
        const val PAUSE = 3       // timer pause
        const val RESET = 4       // timer reset
        const val BEEP = 5        // 3-2-1 phase countdown
        const val GO = 6          // phase go
        const val DING = 7        // round complete
        const val FANFARE = 8     // workout complete / celebration
        const val WARN = 9        // device warning klaxon
        const val CONFIRM = 10    // mode-switch confirm prompt
        const val CANCEL = 11     // confirm timed out / cancelled
        private const val COUNT = 12
        private const val RATE = 22050
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val ids = IntArray(COUNT)
    @Volatile private var loaded = false
    @Volatile var volume = 0.7f
    private val rng = Random(7)

    fun loadAsync() {
        Thread {
            runCatching {
                val dir = File(context.cacheDir, "snd").apply { mkdirs() }
                ids[TICK] = load(dir, "tick", buf(30) { t -> sine(1050f, t) * exp(-t * 70f) * 0.5f })
                ids[SELECT] = load(dir, "sel", buf(90) { t -> sine(740f + t * 600f, t) * exp(-t * 14f) * 0.5f })
                ids[START] = load(dir, "start", arpeggio(intArrayOf(523, 784), 70, 0.6f))
                ids[PAUSE] = load(dir, "pause", arpeggio(intArrayOf(784, 523), 70, 0.5f))
                ids[RESET] = load(dir, "reset", buf(160) { t -> sine(600f - 320f * t, t) * exp(-t * 10f) * 0.5f })
                ids[BEEP] = load(dir, "beep", buf(110) { t -> sine(880f, t) * exp(-t * 12f) * 0.6f })
                ids[GO] = load(dir, "go", buf(260) { t -> sine(1046f, t) * exp(-t * 6f) * 0.6f })
                ids[DING] = load(dir, "ding", buf(300) { t -> (sine(1318f, t) + 0.4f * sine(2637f, t)) * exp(-t * 7f) * 0.45f })
                ids[FANFARE] = load(dir, "fan", arpeggio(intArrayOf(523, 659, 784, 1046, 1318), 90, 0.7f))
                ids[WARN] = load(dir, "warn", buf(420) { t ->
                    val f = if ((t * 6f).toInt() % 2 == 0) 620f else 470f
                    sq(f, t) * exp(-t * 3f) * 0.4f
                })
                ids[CONFIRM] = load(dir, "cfm", arpeggio(intArrayOf(660, 880), 80, 0.55f))
                ids[CANCEL] = load(dir, "cxl", buf(180) { t -> (sine(300f, t) + 0.4f * sine(306f, t)) * exp(-t * 9f) * 0.5f })
                loaded = true
            }
        }.start()
    }

    fun play(id: Int, pitch: Float = 1f, vol: Float = 1f) {
        if (!loaded || id < 0 || id >= COUNT) return
        val s = ids[id]; if (s == 0) return
        val v = (volume * vol).coerceIn(0f, 1f); if (v <= 0f) return
        pool.play(s, v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
    }

    fun release() { runCatching { pool.release() } }

    // ------------------------------------------------------------ synth

    private fun buf(ms: Int, gen: (Float) -> Float): ShortArray {
        val n = RATE * ms / 1000
        return ShortArray(n) { i -> (gen(i.toFloat() / RATE).coerceIn(-1f, 1f) * 30000f).toInt().toShort() }
    }

    private fun sine(f: Float, t: Float) = sin(2.0 * PI * f * t).toFloat()
    private fun sq(f: Float, t: Float) = if ((f * t) % 1f < 0.5f) 1f else -1f

    private fun arpeggio(freqs: IntArray, noteMs: Int, amp: Float): ShortArray {
        val total = noteMs * freqs.size + 220
        return buf(total) { t ->
            var v = 0f
            for ((i, f) in freqs.withIndex()) {
                val start = i * noteMs / 1000f
                if (t >= start) {
                    val lt = t - start
                    v += (sine(f.toFloat(), lt) + 0.3f * sine(f * 2f, lt)) * exp(-lt * 5.5f) * amp * 0.4f
                }
            }
            v
        }
    }

    // ------------------------------------------------------------- wav

    private fun DataOutputStream.wInt(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF) }
    private fun DataOutputStream.wShort(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }

    private fun load(dir: File, name: String, pcm: ShortArray): Int {
        val f = File(dir, "$name.wav")
        val dataLen = pcm.size * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(f))).use { o ->
            o.writeBytes("RIFF"); o.wInt(36 + dataLen); o.writeBytes("WAVE")
            o.writeBytes("fmt "); o.wInt(16); o.wShort(1); o.wShort(1)
            o.wInt(RATE); o.wInt(RATE * 2); o.wShort(2); o.wShort(16)
            o.writeBytes("data"); o.wInt(dataLen)
            for (s in pcm) o.wShort(s.toInt())
        }
        return pool.load(f.absolutePath, 1)
    }
}
