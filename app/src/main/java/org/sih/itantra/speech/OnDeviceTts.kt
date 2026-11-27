package org.sih.itantra.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.sih.itantra.domain.model.Language
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingDeque
import kotlin.math.PI
import kotlin.math.sin

interface TextToSpeechEngine {
    /**
     * Speak [text]. [alert] = distress/SOS: played on the alarm channel at full volume, ahead of
     * anything queued, and not interruptible. [onMetrics]: startLatencyMs = call -> first audio,
     * audioDurationMs = spoken length, success = real speech (false = fallback tone).
     */
    fun speak(
        text: String,
        language: Language,
        onDone: () -> Unit = {},
        onMetrics: (startLatencyMs: Long, audioDurationMs: Long, success: Boolean) -> Unit = { _, _, _ -> },
        alert: Boolean = false
    )
    fun stop()
    fun shutdown()
}

/**
 * Fully offline, open-source text-to-speech: Meta MMS-TTS (VITS) voices run with sherpa-onnx.
 *
 * - The voice is picked from the text's script, so Tamil text is spoken by the Tamil voice even
 *   if the UI language is different.
 * - Every utterance is kept as a WAV voice note, so replaying a message is instant.
 * - Alerts preempt normal speech, use USAGE_ALARM at maximum alarm volume with exclusive focus,
 *   and are never cut off by later messages.
 */
class OnDeviceTts(
    private val context: Context,
    private val packs: SpeechModelPacks
) : TextToSpeechEngine {

    companion object {
        private const val TAG = "OnDeviceTts"
        private const val MAX_VOICE_NOTES = 200
    }

    private data class Job(
        val text: String,
        val language: Language,
        val alert: Boolean,
        val onDone: () -> Unit,
        val onMetrics: (Long, Long, Boolean) -> Unit,
        val requestedAt: Long = System.currentTimeMillis()
    )

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val queue = LinkedBlockingDeque<Job>()
    private val notesDir = File(context.filesDir, "voice_notes").apply { mkdirs() }

    private var tts: OfflineTts? = null
    private var ttsLang: Language? = null
    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var currentIsAlert = false
    @Volatile private var running = true

    private val _isSpeaking = MutableStateFlow(false)
    /** True while audio is playing; phone mode uses it to mute the mic (half-duplex echo guard). */
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val worker = Thread({ workLoop() }, "tts-worker").apply { start() }

    override fun speak(
        text: String,
        language: Language,
        onDone: () -> Unit,
        onMetrics: (startLatencyMs: Long, audioDurationMs: Long, success: Boolean) -> Unit,
        alert: Boolean
    ) {
        if (text.isBlank()) { onDone(); return }
        val job = Job(text.trim(), language, alert, onDone, onMetrics)
        if (alert) {
            queue.addFirst(job)
            // An alert cuts off ordinary speech that is playing right now (never another alert).
            if (!currentIsAlert) currentTrack?.let { runCatching { it.pause(); it.flush() } }
        } else {
            queue.addLast(job)
        }
    }

    /** Warm up the voice for [language] in the background so the first message isn't delayed. */
    fun prepare(language: Language) {
        Thread({ synchronized(this) { ensureTts(language) } }, "tts-prepare").start()
    }

    override fun stop() {
        queue.removeIf { !it.alert }
        if (!currentIsAlert) currentTrack?.let { runCatching { it.pause(); it.flush() } }
    }

    override fun shutdown() {
        running = false
        queue.clear()
        worker.interrupt()
        synchronized(this) {
            tts?.release()
            tts = null
        }
    }

    // ------------------------------------------------------------------ worker

    private fun workLoop() {
        while (running) {
            val job = try { queue.take() } catch (_: InterruptedException) { return }
            try {
                process(job)
            } catch (t: Throwable) {
                Log.w(TAG, "speak failed: ${t.message}")
                job.onMetrics(0L, 0L, false)
                playTone(job.alert)
            } finally {
                job.onDone()
            }
        }
    }

    private fun process(job: Job) {
        val voice = pickVoice(job.text, job.language)
        if (voice == null) {
            Log.w(TAG, "No voice pack installed for '${job.language.code}' (or the text's script)")
            job.onMetrics(System.currentTimeMillis() - job.requestedAt, 0L, false)
            playTone(job.alert)
            return
        }
        // Numbers, %, & spelled out in the voice's language (MMS voices only know letters)
        val normalized = TtsTextNormalizer.normalize(job.text, voice)
        val spoken = if (voice == Language.ENGLISH) normalized.lowercase() else normalized
        val note = noteFile(voice, spoken)

        // Replay of a stored voice note: no synthesis at all
        readWav(note)?.let { (samples, rate) ->
            val start = System.currentTimeMillis() - job.requestedAt
            playStreamed(rate, job.alert) { emit -> emit(samples) }
            job.onMetrics(start, samples.size * 1000L / rate, true)
            return
        }

        val engine = synchronized(this) { ensureTts(voice) } ?: run {
            job.onMetrics(System.currentTimeMillis() - job.requestedAt, 0L, false)
            playTone(job.alert)
            return
        }
        // Pipeline: clause 1 starts playing while clause 2 is being synthesised, so time-to-first-
        // audio is the synthesis time of the first clause instead of the whole message.
        val pieces = ArrayList<FloatArray>()
        var firstAudioAt = 0L
        val rate = engine.sampleRate()
        val completed = playStreamed(rate, job.alert) { emit ->
            for (clause in splitForSpeech(spoken)) {
                val samples = synchronized(this) { engine.generate(clause, 0, 1.0f).samples }
                if (samples.isEmpty()) continue
                if (firstAudioAt == 0L) firstAudioAt = System.currentTimeMillis()
                pieces += samples
                if (!emit(samples)) break
            }
        }
        val total = pieces.sumOf { it.size }
        if (total == 0) {
            job.onMetrics(System.currentTimeMillis() - job.requestedAt, 0L, false)
            playTone(job.alert)
            return
        }
        if (completed) {
            val all = FloatArray(total)
            var off = 0
            for (p in pieces) { p.copyInto(all, off); off += p.size }
            writeWav(note, all, rate)
            pruneNotes()
        }
        job.onMetrics(firstAudioAt - job.requestedAt, total * 1000L / rate, true)
    }

    /**
     * Clauses for pipelined synthesis: split after sentence/clause punctuation, then merge pieces so
     * each has at least 4 words (the first only 2, to start speaking sooner). Short messages stay whole.
     */
    internal fun splitForSpeech(text: String): List<String> {
        val raw = text.split(Regex("""(?<=[.!?।॥;:,])\s+|\n+""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (raw.size <= 1) return listOf(text.trim())
        val out = ArrayList<String>()
        var cur = StringBuilder()
        for (part in raw) {
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(part)
            val minWords = if (out.isEmpty()) 2 else 4
            if (cur.split(' ').size >= minWords) { out += cur.toString(); cur = StringBuilder() }
        }
        if (cur.isNotEmpty()) { if (out.isEmpty()) out += cur.toString() else out[out.size - 1] = out.last() + " " + cur }
        return out
    }

    /** Keeps one voice in memory at a time to bound RAM on low-end phones. */
    private fun ensureTts(lang: Language): OfflineTts? {
        if (ttsLang == lang && tts != null) return tts
        val (model, tokens) = packs.ttsFiles(lang) ?: return null
        tts?.release()
        tts = null
        val t0 = System.currentTimeMillis()
        return try {
            OfflineTts(
                null,
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = model.absolutePath,
                            tokens = tokens.absolutePath,
                            noiseScale = 0.667f,
                            noiseScaleW = 0.8f,
                            lengthScale = 1.0f
                        ),
                        numThreads = maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors() / 2)),
                        provider = "cpu"
                    ),
                    maxNumSentences = 1
                )
            ).also {
                tts = it
                ttsLang = lang
                Log.i(TAG, "Loaded ${lang.code} voice in ${System.currentTimeMillis() - t0} ms")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Could not load ${lang.code} voice: ${t.message}")
            null
        }
    }

    /** Voice for the text's script; prefers the requested language when it shares the script. */
    private fun pickVoice(text: String, requested: Language): Language? {
        val candidates = when (dominantScript(text)) {
            Script.DEVANAGARI -> listOf(requested, Language.HINDI, Language.MARATHI).filter { it == Language.HINDI || it == Language.MARATHI }
            Script.BENGALI -> listOf(Language.BENGALI)
            Script.TAMIL -> listOf(Language.TAMIL)
            Script.TELUGU -> listOf(Language.TELUGU)
            Script.KANNADA -> listOf(Language.KANNADA)
            Script.MALAYALAM -> listOf(Language.MALAYALAM)
            Script.GUJARATI -> listOf(Language.GUJARATI)
            Script.ODIA -> listOf(Language.ODIA)
            Script.GURMUKHI -> listOf(Language.PUNJABI)
            Script.LATIN -> listOf(Language.ENGLISH)
            Script.UNKNOWN -> listOf(requested)
        }
        return candidates.distinct().firstOrNull { packs.hasTts(it) }
    }

    private enum class Script { DEVANAGARI, BENGALI, GURMUKHI, GUJARATI, ODIA, TAMIL, TELUGU, KANNADA, MALAYALAM, LATIN, UNKNOWN }

    private fun dominantScript(text: String): Script {
        val counts = IntArray(Script.entries.size)
        for (c in text) {
            val s = when (c.code) {
                in 0x0900..0x097F -> Script.DEVANAGARI
                in 0x0980..0x09FF -> Script.BENGALI
                in 0x0A00..0x0A7F -> Script.GURMUKHI
                in 0x0A80..0x0AFF -> Script.GUJARATI
                in 0x0B00..0x0B7F -> Script.ODIA
                in 0x0B80..0x0BFF -> Script.TAMIL
                in 0x0C00..0x0C7F -> Script.TELUGU
                in 0x0C80..0x0CFF -> Script.KANNADA
                in 0x0D00..0x0D7F -> Script.MALAYALAM
                in 0x41..0x5A, in 0x61..0x7A -> Script.LATIN
                else -> null
            } ?: continue
            counts[s.ordinal]++
        }
        val best = counts.indices.maxByOrNull { counts[it] } ?: return Script.UNKNOWN
        return if (counts[best] == 0) Script.UNKNOWN else Script.entries[best]
    }

    // ------------------------------------------------------------------ playback

    /**
     * Plays audio that [produce] hands over chunk by chunk via `emit`. Chunks are written to the
     * AudioTrack on a separate player thread, so producing the next chunk overlaps playback.
     * `emit` returns false once playback was pre-empted (an alert arrived); returns true if all
     * audio was played.
     */
    private fun playStreamed(rate: Int, alert: Boolean, produce: ((FloatArray) -> Boolean) -> Unit): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(if (alert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = requestFocus(attrs, alert)
        if (alert) {
            // Highest volume for distress announcements
            runCatching {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            }
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        currentTrack = track
        currentIsAlert = alert
        _isSpeaking.value = true
        val chunks = java.util.concurrent.LinkedBlockingQueue<FloatArray>()
        val end = FloatArray(0)
        val preempted = java.util.concurrent.atomic.AtomicBoolean(false)
        var written = 0L
        val player = Thread({
            while (true) {
                val chunk = try { chunks.take() } catch (_: InterruptedException) { break }
                if (chunk === end) break
                var offset = 0
                while (offset < chunk.size && running) {
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) { preempted.set(true); break }
                    val n = track.write(chunk, offset, minOf(4096, chunk.size - offset), AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) { preempted.set(true); break }
                    offset += n
                }
                written += offset
                if (preempted.get()) break
            }
        }, "tts-player")
        try {
            track.setVolume(AudioTrack.getMaxVolume())
            track.play()
            player.start()
            produce { chunk -> if (preempted.get()) false else { chunks.put(chunk); true } }
            chunks.put(end)
            player.join()
            // Let the tail of the buffer finish before releasing.
            if (!preempted.get() && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val deadline = System.currentTimeMillis() + 2000
                while (track.playbackHeadPosition < written && System.currentTimeMillis() < deadline &&
                    track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    Thread.sleep(20)
                }
            }
        } finally {
            player.interrupt()
            runCatching { track.stop() }
            track.release()
            currentTrack = null
            currentIsAlert = false
            _isSpeaking.value = false
            abandonFocus(focus)
        }
        return !preempted.get()
    }

    private fun requestFocus(attrs: AudioAttributes, alert: Boolean): Any? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(
                if (alert) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
                .setAudioAttributes(attrs)
                // Alerts ignore focus loss entirely: they are not interruptible.
                .setOnAudioFocusChangeListener { }
                .setWillPauseWhenDucked(false)
                .build()
            audioManager.requestAudioFocus(req)
            req
        } else null
    }

    private fun abandonFocus(req: Any?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && req is AudioFocusRequest) {
            audioManager.abandonAudioFocusRequest(req)
        }
    }

    /** Short beep used when no voice pack is installed, so the user still knows a message arrived. */
    private fun playTone(alert: Boolean) {
        val rate = 16_000
        val ms = if (alert) 900 else 250
        val freq = if (alert) 880.0 else 600.0
        val samples = FloatArray(rate * ms / 1000) { i -> (0.6 * sin(2 * PI * freq * i / rate)).toFloat() }
        runCatching { playStreamed(rate, alert) { emit -> emit(samples) } }
    }

    // ------------------------------------------------------------------ voice notes (16-bit WAV)

    private fun noteFile(voice: Language, text: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest("${voice.code}|$text".toByteArray())
        return File(notesDir, digest.joinToString("") { "%02x".format(it) } + ".wav")
    }

    private fun pruneNotes() {
        val files = notesDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        files.take((files.size - MAX_VOICE_NOTES).coerceAtLeast(0)).forEach { it.delete() }
    }

    private fun writeWav(file: File, samples: FloatArray, rate: Int) {
        runCatching {
            val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate)
                putInt(rate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(data.capacity())
            }
            FileOutputStream(file).use { it.write(header.array()); it.write(data.array()) }
        }.onFailure { Log.w(TAG, "voice note write failed: ${it.message}") }
    }

    private fun readWav(file: File): Pair<FloatArray, Int>? {
        if (!file.isFile || file.length() <= 44) return null
        return runCatching {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                val header = ByteArray(44).also { input.readFully(it) }
                val rate = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val bytes = ByteArray((file.length() - 44).toInt()).also { input.readFully(it) }
                val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                FloatArray(shorts.remaining()) { shorts.get(it) / 32768f } to rate
            }
        }.getOrNull()?.also { file.setLastModified(System.currentTimeMillis()) }
    }
}
