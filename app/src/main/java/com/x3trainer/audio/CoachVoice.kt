package com.x3trainer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/**
 * The coach's voice. Coaching is AUDIO ONLY — never text on the lens.
 *
 * Primary path: pre-generated fish.audio S2.1-Pro clips (the enthusiastic
 * voice model), generated once by tools/generate_tts.py and stored on the
 * glasses — first found in filesDir/tts/<id>.mp3, else bundled assets
 * (assets/tts/<id>.mp3). No network use at run time.
 *
 * Fallback: if a clip is missing (fish generation not run yet), the phrase is
 * spoken through Android TTS pitched up to stay enthusiastic, so coaching
 * still works out of the box.
 */
class CoachVoice(private val context: Context) {

    private val TAG = "X3TrainerVoice"

    companion object { private const val LIVE = "LIVE:" }

    @Volatile var volume = 0.9f

    private val phrases = HashMap<String, String>()
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val queue = ArrayDeque<String>()
    @Volatile private var speaking = false

    /** True while a clip or TTS line is playing or queued — for phase gating. */
    val isSpeaking: Boolean get() = speaking || queue.isNotEmpty()

    fun load() {
        runCatching {
            val txt = context.assets.open("phrases.json").bufferedReader().use { it.readText() }
            val o = JSONObject(txt)
            for (k in o.keys()) phrases[k] = o.getString(k)
        }.onFailure { Log.e(TAG, "phrases.json", it) }
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.US
                tts?.setPitch(1.15f)          // enthusiastic fallback voice
                tts?.setSpeechRate(1.08f)
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { speaking = false; pump() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { speaking = false; pump() }
                })
            }
        }
    }

    /**
     * Queue a phrase. urgent = warnings: clears the queue and interrupts
     * whatever pep talk is playing.
     */
    fun say(id: String, urgent: Boolean = false) {
        if (volume <= 0.01f) return
        if (!phrases.containsKey(id)) return
        enqueue(id, urgent)
    }

    /**
     * Speak dynamic text (live vitals, personalized stats) through TTS — this
     * content can't be pre-generated because it embeds live numbers. Entries
     * are marked so pump() skips the clip lookup.
     */
    fun sayLive(text: String) {
        if (volume <= 0.01f || text.isBlank()) return
        enqueue(LIVE + text, urgent = false)
    }

    private fun enqueue(item: String, urgent: Boolean) {
        synchronized(queue) {
            if (urgent) {
                queue.clear()
                stopCurrent()
                queue.add(item)
            } else {
                if (speaking || queue.isNotEmpty()) {
                    // Never stack chatter: keep at most one pending phrase.
                    if (queue.size >= 1) return
                }
                queue.add(item)
            }
        }
        pump()
    }

    private fun pump() {
        val id: String
        synchronized(queue) {
            if (speaking) return
            id = queue.pollFirst() ?: return
            speaking = true
        }
        if (id.startsWith(LIVE)) { speakText("live", id.substring(LIVE.length)); return }
        val clip = findClip(id)
        if (clip != null) playClip(clip) else speakFallback(id)
    }

    private fun findClip(id: String): Any? {
        val f = File(File(context.filesDir, "tts"), "$id.mp3")
        if (f.exists()) return f
        return runCatching {
            context.assets.openFd("tts/$id.mp3")
        }.getOrNull()
    }

    private fun playClip(src: Any) {
        runCatching {
            stopPlayer()
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            when (src) {
                is File -> mp.setDataSource(src.absolutePath)
                is android.content.res.AssetFileDescriptor -> {
                    mp.setDataSource(src.fileDescriptor, src.startOffset, src.length)
                    src.close()
                }
            }
            mp.setVolume(volume, volume)
            mp.setOnCompletionListener { speaking = false; stopPlayer(); pump() }
            mp.setOnErrorListener { _, _, _ -> speaking = false; stopPlayer(); pump(); true }
            mp.prepare()
            mp.start()
            player = mp
        }.onFailure { speaking = false; Log.w(TAG, "clip failed", it) }
    }

    private fun speakFallback(id: String) {
        speakText(id, phrases[id])
    }

    private fun speakText(utterId: String, text: String?) {
        if (!ttsReady || text == null) { speaking = false; return }
        val params = android.os.Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utterId)
    }

    private fun stopCurrent() {
        stopPlayer()
        if (ttsReady) runCatching { tts?.stop() }
        speaking = false
    }

    private fun stopPlayer() {
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
    }

    fun release() {
        stopCurrent()
        runCatching { tts?.shutdown() }
    }
}
