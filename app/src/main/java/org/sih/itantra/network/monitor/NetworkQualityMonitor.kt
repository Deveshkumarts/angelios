package org.sih.itantra.network.monitor

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.sih.itantra.domain.model.LinkMetrics
import org.sih.itantra.domain.model.LinkQuality
import org.sih.itantra.domain.model.TransportType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.abs

/**
 * Computes the Link Quality Score (LQS) from *measured* link behaviour:
 *
 *  - **Packet loss** - every peer broadcasts a HELLO beacon once per [beaconIntervalMs]; the share of
 *    expected beacons that actually arrived over the last [windowMs] is the delivery ratio.
 *  - **Latency / jitter** - round-trip time of HEARTBEAT pings and message ACKs (clock-independent).
 *  - **RSSI** - radio signal strength reported by the transport (Wi-Fi association RSSI, BLE scan
 *    RSSI); falls back to the strongest peer seen.
 *
 * Pure Kotlin (no Android types) so it is unit-testable on the JVM.
 */
class NetworkQualityMonitor(
    private val beaconIntervalMs: Long = 1_000L,
    private val windowMs: Long = 10_000L,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val _metrics = MutableStateFlow(LinkMetrics())
    val metrics: StateFlow<LinkMetrics> = _metrics.asStateFlow()

    private var monitoringJob: Job? = null
    @Volatile private var isSimulating = false
    @Volatile private var rssiProvider: () -> Int? = { null }

    private val beaconTimes = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()
    private val firstSeen = ConcurrentHashMap<String, Long>()
    private val peerRssi = ConcurrentHashMap<String, Int>()
    private val rttSamples = ConcurrentLinkedDeque<Long>()

    fun startMonitoring(scope: CoroutineScope, rssiSource: () -> Int? = { null }) {
        rssiProvider = rssiSource
        if (monitoringJob != null) return
        monitoringJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                if (!isSimulating) recompute()
                delay(1500)
            }
        }
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
    }

    /** A discovery beacon from [peerId] arrived (optionally with the radio RSSI it arrived at). */
    fun recordBeacon(peerId: String, rssi: Int? = null) {
        val now = clock()
        firstSeen.putIfAbsent(peerId, now)
        beaconTimes.getOrPut(peerId) { ConcurrentLinkedDeque() }.addLast(now)
        if (rssi != null) peerRssi[peerId] = rssi
    }

    /** A measured round trip (ping -> reply, or message -> ACK). */
    fun recordRoundTrip(rttMs: Long) {
        if (rttMs < 0 || rttMs > 60_000) return
        rttSamples.addLast(rttMs)
        while (rttSamples.size > 20) rttSamples.pollFirst()
    }

    /** Transport over which the most recent peer traffic arrived. */
    fun setTransport(transportType: TransportType) {
        _metrics.value = _metrics.value.copy(activeTransport = transportType)
    }

    fun updateBattery(pct: Int) {
        _metrics.value = _metrics.value.copy(batteryPct = pct.coerceIn(0, 100))
    }

    /** Kept for callers that only know whether a send succeeded; a failed local send counts as loss. */
    fun recordPacketTransmission(success: Boolean, @Suppress("UNUSED_PARAMETER") durationMs: Long) {
        if (!success) {
            val current = _metrics.value
            _metrics.value = current.copy(packetLossPct = (current.packetLossPct * 0.85f + 15f).coerceIn(0f, 100f))
        }
    }

    /** Recomputes metrics from the samples collected so far. Public for tests. */
    fun recompute() {
        val now = clock()
        val cutoff = now - windowMs

        var expectedTotal = 0.0
        var receivedTotal = 0
        val activePeers = mutableListOf<String>()
        for ((peer, times) in beaconTimes) {
            while (times.peekFirst()?.let { it < cutoff } == true) times.pollFirst()
            if (times.isEmpty()) {
                // Peer silent for a whole window: forget it (it left, or was never in range).
                beaconTimes.remove(peer); firstSeen.remove(peer); peerRssi.remove(peer)
                continue
            }
            activePeers += peer
            val observedSpan = (now - maxOf(cutoff, firstSeen[peer] ?: now)).coerceAtLeast(beaconIntervalMs)
            expectedTotal += observedSpan.toDouble() / beaconIntervalMs
            receivedTotal += times.size
        }
        val lossPct = if (expectedTotal > 0) {
            ((1.0 - receivedTotal / expectedTotal) * 100.0).coerceIn(0.0, 100.0).toFloat()
        } else 0f

        val rtts = rttSamples.toList()
        val latency = if (rtts.isNotEmpty()) rtts.sorted()[rtts.size / 2] else _metrics.value.latencyMs
        val jitter = if (rtts.size >= 2) rtts.zipWithNext { a, b -> abs(b - a) }.average().toLong() else _metrics.value.jitterMs

        val rssi = rssiProvider()?.takeIf { it in -127..0 && it != -127 }
            ?: activePeers.mapNotNull { peerRssi[it] }.maxOrNull()
            ?: _metrics.value.rssi

        _metrics.value = _metrics.value.copy(
            rssi = rssi,
            packetLossPct = lossPct,
            latencyMs = latency,
            jitterMs = jitter,
            peerCount = activePeers.size
        )
    }

    /**
     * For the judge demo screen only: force a link condition so the adaptive compression tiers can be
     * shown on demand. Real measurement resumes after [clearSimulation].
     */
    fun setSimulatedCondition(condition: LinkQuality) {
        isSimulating = true
        val battery = _metrics.value.batteryPct
        _metrics.value = when (condition) {
            LinkQuality.GOOD -> LinkMetrics(-52, 0.5f, 38L, 4L, battery, TransportType.WIFI_DIRECT)
            LinkQuality.MEDIUM -> LinkMetrics(-72, 4.2f, 120L, 18L, battery, TransportType.BLE)
            LinkQuality.POOR -> LinkMetrics(-88, 18.5f, 380L, 65L, battery, TransportType.LORA)
            LinkQuality.EMERGENCY -> LinkMetrics(-96, 42.0f, 850L, 120L, battery, TransportType.LORA)
        }
    }

    fun clearSimulation() {
        isSimulating = false
    }
}
