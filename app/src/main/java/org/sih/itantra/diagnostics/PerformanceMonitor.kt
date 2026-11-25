package org.sih.itantra.diagnostics

import android.content.Context
import android.os.Debug
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Every field here is either an exact live measurement or an honestly-labeled proxy - never a
 * constant. See [PerformanceMonitor] for exactly how each one is produced and what it does/doesn't
 * claim to measure (there is no ground-truth transcript on-device, so there is no true WER; there
 * is no human listener, so there is no true MOS - both are called out below rather than faked).
 */
data class PerformanceMetrics(
    // ---- Efficiency ----
    val bundledAssetBytes: Long = 0L,          // our own offline phrase packs, measured from assets/
    val apkSizeBytes: Long = 0L,               // installed APK file size, measured
    val downloadedNeuralModels: Int = 0,       // languages with both open-source STT + TTS packs installed
    val totalNeuralModels: Int = 0,
    val heapUsedMb: Float = 0f,                // live JVM heap in use
    val heapMaxMb: Float = 0f,
    val nativePssMb: Float = 0f,               // live total process PSS (RAM) via Debug.getMemoryInfo
    val cpuUsagePct: Float = 0f,               // live, sampled every ~2s from /proc/self/stat deltas
    val cpuSampleIsIdle: Boolean = true,       // whether the app was idle (no active PTT/STT/TTS) when sampled
    val deviceStorageUsedPct: Float = 0f,      // live device storage usage via StatFs, not a constant

    // ---- Accuracy (no on-device ground truth exists for true WER/MOS - see class doc) ----
    val avgSttConfidencePct: Float? = null,    // rolling avg of the recognizer's own per-result confidence
    val sttSampleCount: Int = 0,
    val ttsSuccessRatePct: Float? = null,      // rolling synth success rate (engine onDone vs onError)
    val ttsSampleCount: Int = 0,

    // ---- Latency ----
    val lastSttLatencyMs: Long? = null,        // end-of-speech -> transcript ready
    val avgSttLatencyMs: Long? = null,
    val lastSttRtf: Float? = null,             // STT decode time / spoken audio time (on-device IndicConformer)
    val avgSttRtf: Float? = null,
    val lastTtsStartLatencyMs: Long? = null,   // speak() called -> first audio frame
    val avgTtsStartLatencyMs: Long? = null,
    val lastTtsRtf: Float? = null,             // ttsStartLatency / spokenAudioDuration
    val avgTtsRtf: Float? = null,
    val lastTtsPacketToAudioMs: Long? = null,  // packet received -> local TTS audio start
    val avgTtsPacketToAudioMs: Long? = null,
    val lastCrossDeviceLatencyMs: Long? = null, // sender packet built -> THIS device's audio start
    val avgCrossDeviceLatencyMs: Long? = null,
    val crossDeviceSampleCount: Int = 0
)

/** Fixed-size rolling window; keeps the process cheap and the average meaningful (recent behaviour,
 *  not a lifetime average that a single bad sample from hours ago would still be dragging down). */
private class RollingWindow(private val maxSize: Int = 20) {
    private val values = ConcurrentLinkedDeque<Double>()

    fun add(v: Double) {
        values.addLast(v)
        while (values.size > maxSize) values.pollFirst()
    }

    fun average(): Double? = values.toList().let { if (it.isEmpty()) null else it.average() }
    fun last(): Double? = values.peekLast()
    val count: Int get() = values.size
}

/**
 * Real-time replacement for the hardcoded benchmark numbers that used to sit in DiagnosticsScreen.
 * Every metric is either measured directly from the OS/JVM, or timed around an actual pipeline event
 * (STT/TTS calls, packet receipt). Nothing here is a constant.
 *
 * What this deliberately does NOT claim:
 * - STT "accuracy" is the Android recognizer's own confidence score, not Word Error Rate - WER needs
 *   a ground-truth transcript, which does not exist for live field speech.
 * - TTS "quality" is a synthesis success rate, not a Mean Opinion Score - MOS needs a human listener.
 * Both are real, live signals; neither is relabeled as something it isn't.
 */
class PerformanceMonitor(private val context: Context) {

    private val _metrics = MutableStateFlow(PerformanceMetrics())
    val metrics: StateFlow<PerformanceMetrics> = _metrics.asStateFlow()

    private val sttLatency = RollingWindow()
    private val sttConfidence = RollingWindow()
    private val sttRtf = RollingWindow()
    private val ttsStartLatency = RollingWindow()
    private val ttsRtf = RollingWindow()
    private val ttsPacketToAudio = RollingWindow()
    private val crossDeviceLatency = RollingWindow()
    private var ttsSuccesses = 0
    private var ttsAttempts = 0

    @Volatile private var isIdle: Boolean = true

    private var lastCpuSampleTimeNanos: Long = 0L
    private var lastCpuTicks: Long = -1L
    private val clockTicksPerSec: Double = try { Os.sysconf(OsConstants._SC_CLK_TCK).toDouble() } catch (_: Throwable) { 100.0 }
    private val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    // Computed once (cheap to cache; asset/APK size don't change while the process is alive)
    @Volatile private var bundledAssetBytesCache: Long = -1L
    @Volatile private var apkSizeBytesCache: Long = -1L

    fun start(scope: CoroutineScope, totalNeuralModels: Int, downloadedModelsProvider: () -> Int) {
        scope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    sampleAndPublish(totalNeuralModels, downloadedModelsProvider())
                } catch (t: Throwable) {
                    android.util.Log.w("PerformanceMonitor", "Sampling error: ${t.message}")
                }
                kotlinx.coroutines.delay(2000L)
            }
        }
    }

    /** Marks whether the app is currently doing active work (PTT capture/STT/TTS) vs sitting idle,
     *  so the CPU% reading can be labeled honestly instead of always claiming to be "idle listening". */
    fun setActive(active: Boolean) {
        isIdle = !active
    }

    fun recordSttLatency(ms: Long) {
        if (ms in 0..60_000) sttLatency.add(ms.toDouble())
    }

    fun recordSttRtf(rtf: Float) {
        if (rtf in 0f..20f) sttRtf.add(rtf.toDouble())
    }

    fun recordSttConfidence(pct: Float) {
        if (pct in 0f..100f) sttConfidence.add(pct.toDouble())
    }

    fun recordTtsOutcome(success: Boolean) {
        ttsAttempts++
        if (success) ttsSuccesses++
    }

    /** [startLatencyMs] = time from speak() call to first audio frame; [audioDurationMs] = spoken length. */
    fun recordTtsTiming(startLatencyMs: Long, audioDurationMs: Long) {
        if (startLatencyMs in 0..60_000) ttsStartLatency.add(startLatencyMs.toDouble())
        if (audioDurationMs > 0 && startLatencyMs >= 0) {
            ttsRtf.add(startLatencyMs.toDouble() / audioDurationMs.toDouble())
        }
    }

    fun recordTtsPacketToAudioLatency(ms: Long) {
        if (ms in 0..60_000) ttsPacketToAudio.add(ms.toDouble())
    }

    /** Only meaningful if both devices' clocks are reasonably in sync (both normally auto-sync via
     *  carrier/GPS time); a manually-set clock on either phone will skew this reading. */
    fun recordCrossDeviceLatency(ms: Long) {
        if (ms in -5_000..120_000) crossDeviceLatency.add(ms.toDouble())
    }

    private fun sampleAndPublish(totalNeuralModels: Int, downloadedModels: Int) {
        if (bundledAssetBytesCache < 0) bundledAssetBytesCache = measureBundledAssetBytes()
        if (apkSizeBytesCache < 0) apkSizeBytesCache = measureApkSizeBytes()

        val runtime = Runtime.getRuntime()
        val heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576f
        val heapMaxMb = runtime.maxMemory() / 1_048_576f
        val nativePssMb = measureNativePssMb()
        val cpuPct = sampleCpuPct()
        val storagePct = measureStorageUsedPct()

        _metrics.value = PerformanceMetrics(
            bundledAssetBytes = bundledAssetBytesCache,
            apkSizeBytes = apkSizeBytesCache,
            downloadedNeuralModels = downloadedModels,
            totalNeuralModels = totalNeuralModels,
            heapUsedMb = heapUsedMb,
            heapMaxMb = heapMaxMb,
            nativePssMb = nativePssMb,
            cpuUsagePct = cpuPct,
            cpuSampleIsIdle = isIdle,
            deviceStorageUsedPct = storagePct,
            avgSttConfidencePct = sttConfidence.average()?.toFloat(),
            sttSampleCount = sttConfidence.count,
            ttsSuccessRatePct = if (ttsAttempts > 0) (ttsSuccesses.toFloat() / ttsAttempts) * 100f else null,
            ttsSampleCount = ttsAttempts,
            lastSttLatencyMs = sttLatency.last()?.toLong(),
            avgSttLatencyMs = sttLatency.average()?.toLong(),
            lastSttRtf = sttRtf.last()?.toFloat(),
            avgSttRtf = sttRtf.average()?.toFloat(),
            lastTtsStartLatencyMs = ttsStartLatency.last()?.toLong(),
            avgTtsStartLatencyMs = ttsStartLatency.average()?.toLong(),
            lastTtsRtf = ttsRtf.last()?.toFloat(),
            avgTtsRtf = ttsRtf.average()?.toFloat(),
            lastTtsPacketToAudioMs = ttsPacketToAudio.last()?.toLong(),
            avgTtsPacketToAudioMs = ttsPacketToAudio.average()?.toLong(),
            lastCrossDeviceLatencyMs = crossDeviceLatency.last()?.toLong(),
            avgCrossDeviceLatencyMs = crossDeviceLatency.average()?.toLong(),
            crossDeviceSampleCount = crossDeviceLatency.count
        )
    }

    /** Reads this process's own utime+stime from /proc/self/stat and turns the delta since the last
     *  sample into a 0-100% figure normalized across all CPU cores. Self-only reads of /proc/self/stat
     *  are permitted on every Android version this app targets; if a device still refuses, this
     *  degrades to 0% rather than crashing or showing a fake number. */
    private fun sampleCpuPct(): Float {
        return try {
            val statText = File("/proc/self/stat").readText()
            val afterComm = statText.substringAfterLast(") ")
            val fields = afterComm.trim().split(" ")
            // Fields (0-indexed here, starting right after "pid (comm) "): 11=utime, 12=stime (in clock ticks)
            val utime = fields[11].toLong()
            val stime = fields[12].toLong()
            val totalTicks = utime + stime
            val nowNanos = System.nanoTime()

            val result = if (lastCpuTicks >= 0) {
                val deltaTicks = (totalTicks - lastCpuTicks).coerceAtLeast(0)
                val deltaSeconds = (nowNanos - lastCpuSampleTimeNanos) / 1_000_000_000.0
                if (deltaSeconds > 0) {
                    val deltaCpuSeconds = deltaTicks / clockTicksPerSec
                    ((deltaCpuSeconds / deltaSeconds / coreCount) * 100.0).toFloat().coerceIn(0f, 100f)
                } else 0f
            } else 0f

            lastCpuTicks = totalTicks
            lastCpuSampleTimeNanos = nowNanos
            result
        } catch (_: Throwable) {
            0f
        }
    }

    private fun measureStorageUsedPct(): Float = try {
        val stat = StatFs(context.filesDir.path)
        val total = stat.totalBytes
        val available = stat.availableBytes
        if (total > 0) (((total - available).toDouble() / total.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f) else 0f
    } catch (_: Throwable) {
        0f
    }

    private fun measureNativePssMb(): Float = try {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        info.totalPss / 1024f // totalPss is in KB
    } catch (_: Throwable) {
        0f
    }

    /** Sums the real byte size of every bundled offline asset (translation phrase packs + phrasebook) -
     *  this is the app's actual on-device "model"/knowledge-base footprint; there is no separate
     *  bundled STT/TTS neural model file, since STT/TTS use the Android system engines. */
    private fun measureBundledAssetBytes(): Long {
        var total = 0L
        fun walk(path: String) {
            val children = try { context.assets.list(path) } catch (_: Throwable) { null } ?: return
            if (children.isEmpty()) {
                total += try { context.assets.open(path).use { it.readBytes().size.toLong() } } catch (_: Throwable) { 0L }
                return
            }
            for (child in children) {
                val childPath = if (path.isEmpty()) child else "$path/$child"
                walk(childPath)
            }
        }
        try { walk("packs") } catch (_: Throwable) {}
        total += try { context.assets.open("phrasebook.json").use { it.readBytes().size.toLong() } } catch (_: Throwable) { 0L }
        return total
    }

    private fun measureApkSizeBytes(): Long = try {
        val appInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        var size = File(appInfo.sourceDir).length()
        appInfo.splitSourceDirs?.forEach { size += File(it).length() }
        size
    } catch (_: Throwable) {
        0L
    }
}
