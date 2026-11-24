package org.sih.itantra.ml.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.sih.itantra.domain.model.Language
import java.util.Locale
import kotlin.math.sin

interface TextToSpeechEngine {
    /** [onMetrics] reports real, timed synthesis numbers for this utterance: [startLatencyMs] from the
     *  call to first audio frame, [audioDurationMs] the spoken length, and [success] whether it played
     *  cleanly (vs. falling back to the tactical tone on engine error). Default no-op keeps every
     *  existing call site source-compatible. */
    fun speak(
        text: String,
        language: Language,
        onDone: () -> Unit = {},
        onMetrics: (startLatencyMs: Long, audioDurationMs: Long, success: Boolean) -> Unit = { _, _, _ -> }
    )
    fun stop()
    fun shutdown()
}

/**
 * Tactical Offline TTS Engine.
 * Integrates Android TextToSpeech engine with fallback to tactical audio tone generator.
 */
class OfflineTtsEngine(private val context: Context) : TextToSpeechEngine, TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    @Volatile private var isInitialized = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Language>>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val speechAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    init {
        mainHandler.post {
            try {
                tts = TextToSpeech(context, this)
            } catch (t: Throwable) {
                android.util.Log.w("OfflineTts", "TTS init failed: ${t.message}")
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try { tts?.setAudioAttributes(speechAttrs) } catch (_: Throwable) {}
            isInitialized = true
            // Play anything that arrived before the engine finished starting
            while (true) {
                val (text, lang) = pending.poll() ?: break
                speak(text, lang)
            }
        } else {
            android.util.Log.w("OfflineTts", "TTS engine init status=$status")
        }
    }

    @Suppress("DEPRECATION")
    private fun ensureAudible() {
        try {
            audioManager?.let { am ->
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                // Raise a near-muted media volume so an incoming message can actually be heard
                if (cur < max / 3) am.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, 0)
                if (am.mode != AudioManager.MODE_NORMAL) am.mode = AudioManager.MODE_NORMAL
                am.requestAudioFocus(
                    null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        } catch (_: Throwable) {}
    }

    override fun speak(
        text: String,
        language: Language,
        onDone: () -> Unit,
        onMetrics: (startLatencyMs: Long, audioDurationMs: Long, success: Boolean) -> Unit
    ) {
        if (text.isBlank()) { onDone(); return }
        val engine = tts
        if (!isInitialized || engine == null) {
            // Engine still starting: queue instead of dropping the message
            pending.add(text to language)
            onDone()
            return
        }
        try {
            ensureAudible()
            val locale = getLocaleForLanguage(language)
            val available = engine.isLanguageAvailable(locale)
            engine.language = if (available >= TextToSpeech.LANG_AVAILABLE) locale else Locale.ENGLISH

            val utteranceId = "msg_${System.currentTimeMillis()}"
            val callTime = System.currentTimeMillis()
            var audioStartTime = 0L
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    audioStartTime = System.currentTimeMillis()
                }
                override fun onDone(id: String?) {
                    val startLatency = (audioStartTime - callTime).coerceAtLeast(0)
                    val audioDuration = if (audioStartTime > 0L) (System.currentTimeMillis() - audioStartTime).coerceAtLeast(1) else 0L
                    onMetrics(startLatency, audioDuration, true)
                    onDone()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    onMetrics((System.currentTimeMillis() - callTime).coerceAtLeast(0), 0L, false)
                    playTacticalTone(440, 200)
                    onDone()
                }
            })

            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            val result = engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                onMetrics((System.currentTimeMillis() - callTime).coerceAtLeast(0), 0L, false)
                playTacticalTone(600, 250)
                onDone()
            }
        } catch (t: Throwable) {
            android.util.Log.w("OfflineTts", "speak failed: ${t.message}")
            onMetrics(0L, 0L, false)
            playTacticalTone(600, 250)
            onDone()
        }
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    /**
     * Synthesizes tactical audio beep using AudioTrack when voice engine is unavailable.
     */
    private fun playTacticalTone(freqHz: Int, durationMs: Int) {
        try {
            val sampleRate = 8000
            val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
            val sample = ShortArray(numSamples)
            for (i in 0 until numSamples) {
                val angle = 2.0 * Math.PI * i / (sampleRate.toDouble() / freqHz)
                sample[i] = (sin(angle) * 32767).toInt().toShort()
            }

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(speechAttrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(numSamples * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(sample, 0, numSamples)
            audioTrack.play()
        } catch (ignored: Exception) {}
    }

    private fun getLocaleForLanguage(lang: Language): Locale = when (lang) {
        Language.HINDI -> Locale("hi", "IN")
        Language.TAMIL -> Locale("ta", "IN")
        Language.TELUGU -> Locale("te", "IN")
        Language.KANNADA -> Locale("kn", "IN")
        Language.MALAYALAM -> Locale("ml", "IN")
        Language.BENGALI -> Locale("bn", "IN")
        Language.MARATHI -> Locale("mr", "IN")
        Language.GUJARATI -> Locale("gu", "IN")
        Language.PUNJABI -> Locale("pa", "IN")
        Language.ODIA -> Locale("or", "IN")
        Language.ENGLISH -> Locale.ENGLISH
    }
}
