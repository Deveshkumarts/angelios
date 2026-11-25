package org.sih.itantra.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.sih.itantra.domain.model.Language
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Fully offline, open-source speech-to-text.
 *
 *  mic (16 kHz PCM) -> Silero VAD (pause detection) -> AI4Bharat IndicConformer CTC (sherpa-onnx)
 *
 * The VAD closes a sentence as soon as the speaker pauses ([PAUSE_SECONDS]); that sentence is
 * decoded right away and handed to [onSentence], so text streams out sentence by sentence while
 * the speaker is still talking instead of only after the PTT button is released.
 *
 * Public state mirrors the old platform-recognizer wrapper so the UI binds to it unchanged.
 */
class OnDeviceSpeechRecognizer(private val packs: SpeechModelPacks) {

    companion object {
        private const val TAG = "OnDeviceStt"
        const val SAMPLE_RATE = 16_000
        private const val VAD_WINDOW = 512            // Silero VAD frame (32 ms)
        private const val PAUSE_SECONDS = 0.6f        // silence that ends a sentence
        private const val MAX_SENTENCE_SECONDS = 12f  // force a cut in very long run-on speech
    }

    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    private val _audioRms = MutableStateFlow(0f)
    val audioRms: StateFlow<Float> = _audioRms.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _waveformSamples = MutableStateFlow<List<Float>>(List(32) { 0.05f })
    val waveformSamples: StateFlow<List<Float>> = _waveformSamples.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    /** Human-readable reason recognition can't run (e.g. language pack missing), else null. */
    private val _unavailableReason = MutableStateFlow<String?>(null)
    val unavailableReason: StateFlow<String?> = _unavailableReason.asStateFlow()

    /** latencyMs = end of the spoken sentence -> its transcript ready; rtf = decode time / audio time. */
    var onSttCompleted: ((latencyMs: Long, rtf: Float) -> Unit)? = null

    /** Called (on the decode thread) for every sentence closed by a pause. */
    var onSentence: ((text: String, language: Language) -> Unit)? = null

    // Model loading and decoding happen on one background thread; capture has its own thread.
    private val decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "stt-decode").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private var recognizer: OfflineRecognizer? = null
    private var recognizerLang: Language? = null

    @Volatile private var capturing = false
    @Volatile private var inputPaused = false
    private var captureThread: Thread? = null
    private var record: AudioRecord? = null
    private var vad: Vad? = null
    private var sessionLang: Language = Language.ENGLISH
    private var sessionStart = 0L
    private val sessionText = StringBuilder()

    fun isAvailable(language: Language): Boolean = packs.hasStt(language)

    /** Load the recognizer for [language] in the background so the first PTT press is instant. */
    fun prepare(language: Language) {
        decoder.execute { ensureRecognizer(language) }
    }

    /**
     * Start capturing. [handsFree] uses the voice-call input path (echo cancellation and noise
     * suppression) for phone mode, where incoming speech plays while the mic stays open.
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by MainActivity before PTT is usable
    fun startListening(language: Language, handsFree: Boolean = false) {
        if (capturing) return
        sessionLang = language
        sessionText.clear()
        _liveTranscript.value = ""
        if (!packs.hasStt(language)) {
            _unavailableReason.value = "${language.displayName} speech pack not installed"
            Log.w(TAG, "No STT pack for ${language.code}")
            return
        }
        _unavailableReason.value = null
        prepare(language)

        val vadFile = packs.vadModel() ?: return
        vad = Vad(
            null,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadFile.absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = PAUSE_SECONDS,
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW,
                    maxSpeechDuration = MAX_SENTENCE_SECONDS
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1
            )
        )

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val source = if (handsFree) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val rec = try {
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, VAD_WINDOW * 8))
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord init failed: ${t.message}")
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            _unavailableReason.value = "Microphone unavailable"
            rec?.release()
            return
        }
        if (handsFree) {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
        }
        record = rec
        capturing = true
        inputPaused = false
        sessionStart = System.currentTimeMillis()
        _isListening.value = true
        rec.startRecording()
        captureThread = Thread({ captureLoop(rec) }, "stt-capture").apply { start() }
    }

    /** Half-duplex guard for phone mode: ignore the mic while our own TTS is playing. */
    fun setInputPaused(paused: Boolean) {
        inputPaused = paused
    }

    /**
     * Stop capturing, close the last sentence and wait until every sentence has been decoded.
     * Returns the full transcript of this session (each sentence was already sent to [onSentence]).
     */
    suspend fun stopListening(): String {
        if (!capturing) return sessionText.toString().trim()
        capturing = false
        captureThread?.join(500)
        captureThread = null
        try { record?.stop() } catch (_: Throwable) {}
        record?.release()
        record = null
        _isListening.value = false
        _audioRms.value = 0f

        val v = vad
        vad = null
        if (v != null) {
            v.flush()
            drainSegments(v)
            decoder.execute { v.release() }
        }
        // Wait for queued decodes to finish so the caller sees the complete transcript.
        suspendCancellableCoroutine { cont -> decoder.execute { cont.resume(Unit) } }
        return sessionText.toString().trim()
    }

    fun setManualText(text: String) {
        _liveTranscript.value = text
    }

    fun clear() {
        sessionText.clear()
        _liveTranscript.value = ""
    }

    fun release() {
        capturing = false
        decoder.execute {
            recognizer?.release()
            recognizer = null
        }
        decoder.shutdown()
    }

    // ------------------------------------------------------------------ internals

    private fun captureLoop(rec: AudioRecord) {
        val pcm = ShortArray(VAD_WINDOW)
        val frame = FloatArray(VAD_WINDOW)
        while (capturing) {
            val n = rec.read(pcm, 0, pcm.size)
            if (n <= 0) continue
            var sum = 0.0
            for (i in 0 until n) {
                val f = pcm[i] / 32768f
                frame[i] = f
                sum += f * f
            }
            publishLevel(sqrt(sum / n).toFloat())
            val v = vad ?: break
            if (inputPaused) continue
            v.acceptWaveform(if (n == VAD_WINDOW) frame else frame.copyOf(n))
            drainSegments(v)
        }
    }

    private fun drainSegments(v: Vad) {
        while (!v.empty()) {
            val samples = v.front().samples
            v.pop()
            val endedAt = System.currentTimeMillis()
            val lang = sessionLang
            decoder.execute { decodeSentence(samples, lang, endedAt) }
        }
    }

    private fun decodeSentence(samples: FloatArray, lang: Language, endedAt: Long) {
        val rec = ensureRecognizer(lang) ?: return
        val t0 = System.currentTimeMillis()
        val text = try {
            val stream = rec.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val out = rec.getResult(stream).text.trim()
            stream.release()
            out
        } catch (t: Throwable) {
            Log.w(TAG, "decode failed: ${t.message}")
            ""
        }
        val decodeMs = System.currentTimeMillis() - t0
        val audioMs = samples.size * 1000L / SAMPLE_RATE
        if (text.isBlank()) return
        onSttCompleted?.invoke(System.currentTimeMillis() - endedAt, if (audioMs > 0) decodeMs.toFloat() / audioMs else 0f)
        if (sessionText.isNotEmpty()) sessionText.append(' ')
        sessionText.append(text)
        _liveTranscript.value = sessionText.toString()
        onSentence?.invoke(text, lang)
    }

    /** Runs on the decode thread only. Keeps one language loaded at a time to bound RAM. */
    private fun ensureRecognizer(lang: Language): OfflineRecognizer? {
        if (recognizerLang == lang && recognizer != null) return recognizer
        val (model, tokens) = packs.sttFiles(lang) ?: return null
        recognizer?.release()
        recognizer = null
        val t0 = System.currentTimeMillis()
        return try {
            OfflineRecognizer(
                null,
                OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = model.absolutePath),
                        tokens = tokens.absolutePath,
                        numThreads = maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors() / 2)),
                        provider = "cpu"
                    ),
                    decodingMethod = "greedy_search"
                )
            ).also {
                recognizer = it
                recognizerLang = lang
                Log.i(TAG, "Loaded ${lang.code} recognizer in ${System.currentTimeMillis() - t0} ms")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not load ${lang.code} recognizer: ${t.message}")
            _unavailableReason.value = "${lang.displayName} speech model failed to load"
            null
        }
    }

    private fun publishLevel(rms: Float) {
        val level = (rms * 8f).coerceIn(0.04f, 1f)
        _audioRms.value = level
        val wave = _waveformSamples.value.toMutableList()
        if (wave.size >= 32) wave.removeAt(0)
        wave.add(level)
        _waveformSamples.value = wave
        _recordingDurationMs.value = System.currentTimeMillis() - sessionStart
    }
}
