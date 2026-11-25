package org.sih.itantra.domain.repository

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.sih.itantra.battery.BatteryAdaptiveManager
import org.sih.itantra.diagnostics.PerformanceMonitor
import org.sih.itantra.domain.model.ChatMessage
import org.sih.itantra.domain.model.DeliveryStatus
import org.sih.itantra.domain.model.Language
import org.sih.itantra.domain.model.LinkMetrics
import org.sih.itantra.domain.model.NetworkNode
import org.sih.itantra.domain.model.Packet
import org.sih.itantra.domain.model.PacketType
import org.sih.itantra.domain.model.Priority
import org.sih.itantra.domain.model.SemanticMessage
import org.sih.itantra.domain.model.TechnicalTelemetry
import org.sih.itantra.domain.model.TransportType
import org.sih.itantra.emergency.EmergencyManager
import org.sih.itantra.ml.audio.AudioCaptureManager
import org.sih.itantra.ml.semantic.SemanticDecoder
import org.sih.itantra.ml.semantic.SemanticEncoder
import org.sih.itantra.ml.stt.OfflineMultilingualSttEngine
import org.sih.itantra.speech.OnDeviceSpeechRecognizer
import org.sih.itantra.speech.OnDeviceTts
import org.sih.itantra.speech.SpeechModelPacks
import org.sih.itantra.network.compression.AdaptiveCompressor
import org.sih.itantra.network.mesh.MeshRouter
import org.sih.itantra.network.monitor.NetworkQualityMonitor
import org.sih.itantra.network.transport.TransportManager
import org.sih.itantra.storage.LocalEncryptedDatabase
import org.sih.itantra.emergency.LocationProvider
import org.sih.itantra.network.mesh.needsAck
import org.sih.itantra.network.transport.BluetoothTransport
import org.sih.itantra.network.transport.LoraTransport
import org.sih.itantra.network.transport.ReceivedPacket
import org.sih.itantra.security.MeshCrypto
import java.util.concurrent.ConcurrentHashMap

/** Fired for every incoming SOS/EMERGENCY packet from another mesh node, so a foreground service
 *  (or any other listener) can raise a system notification even while the app has no visible UI. */
data class SosAlertEvent(
    val senderId: String,
    val senderName: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Core Orchestrator for iTANTRA.
 * Connects the voice pipeline, ML engines, adaptive compression, CRC16 packet builder,
 * mesh router, multi-transport managers, and receiver reconstruction.
 */
class CommunicationRepository(
    private val context: Context,
    val speechPacks: SpeechModelPacks = SpeechModelPacks(context),
    val audioCaptureManager: AudioCaptureManager = AudioCaptureManager(),
    val sttEngine: OfflineMultilingualSttEngine = OfflineMultilingualSttEngine(),
    val semanticEncoder: SemanticEncoder = SemanticEncoder(),
    val semanticDecoder: SemanticDecoder = SemanticDecoder(),
    val ttsEngine: OnDeviceTts = OnDeviceTts(context, speechPacks),
    val compressor: AdaptiveCompressor = AdaptiveCompressor(),
    val networkMonitor: NetworkQualityMonitor = NetworkQualityMonitor(),
    val batteryManager: BatteryAdaptiveManager = BatteryAdaptiveManager(context),
    val emergencyManager: EmergencyManager = EmergencyManager(context),
    val database: LocalEncryptedDatabase = LocalEncryptedDatabase(context),
    val transportManager: TransportManager = TransportManager(context),
    val speechManager: OnDeviceSpeechRecognizer = OnDeviceSpeechRecognizer(speechPacks),
    val translatorEngine: org.sih.itantra.ml.translation.OfflineTranslatorEngine = org.sih.itantra.ml.translation.OfflineTranslatorEngine(),
    val locationProvider: LocationProvider = LocationProvider(context)
) {
    // SupervisorJob + handler: one failing background coroutine (discovery loop, packet handling,
    // model download...) must not cancel its siblings or take the whole app down.
    private val scope = CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
                android.util.Log.e("CommunicationRepository", "Unhandled background error: ${t.message}", t)
            }
    )

    private companion object {
        const val PEER_TIMEOUT_MS = 12_000L
        const val ACK_TIMEOUT_MS = 2_500L
        const val MAX_SEND_ATTEMPTS = 3
        const val PING_INTERVAL_MS = 5_000L
        const val LORA_BEACON_EVERY_N_TICKS = 10
    }

    /** A message waiting for its ACK. [ackers] lets an SOS count each distinct peer once. */
    private class PendingSend(
        val packet: Packet,
        val chatMessageId: String?,
        val sosLogId: String?,
        val firstSentAt: Long = System.currentTimeMillis()
    ) {
        @Volatile var lastSentAt: Long = firstSentAt
        val ackers: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
    private val pendingAcks = ConcurrentHashMap<Long, PendingSend>()
    private val pendingPings = ConcurrentHashMap<Long, Long>()

    /** How (bearer + RSSI) each peer was last heard - real values for the device list and LQS. */
    private val lastRx = ConcurrentHashMap<String, ReceivedPacket>()
    private var discoveryTick = 0

    // Consumed by MeshForegroundService to raise a system notification for incoming SOS packets
    // even when no Activity/UI is on screen to show them.
    private val _sosAlerts = MutableSharedFlow<SosAlertEvent>(extraBufferCapacity = 16)
    val sosAlerts: SharedFlow<SosAlertEvent> = _sosAlerts.asSharedFlow()

    // Real-time efficiency/accuracy/latency telemetry (see PerformanceMonitor) - replaces the
    // hardcoded benchmark numbers that used to sit directly in DiagnosticsScreen.
    val performanceMonitor = PerformanceMonitor(context)
    val performanceMetrics = performanceMonitor.metrics

    val localNodeId: String = database.getOrCreateNodeId()

    val meshRouter = MeshRouter(localNodeId = localNodeId) { packetToForward ->
        transportManager.transmitPacket(packetToForward)
    }

    // Selected User Preferences
    private val _selectedLanguage = MutableStateFlow(database.getUserProfile().preferredLanguage)
    val selectedLanguage: StateFlow<Language> = _selectedLanguage.asStateFlow()

    private val _receiverLanguage = MutableStateFlow(Language.HINDI)
    val receiverLanguage: StateFlow<Language> = _receiverLanguage.asStateFlow()

    private val _activeTargetNode = MutableStateFlow<NetworkNode?>(null)
    val activeTargetNode: StateFlow<NetworkNode?> = _activeTargetNode.asStateFlow()

    // PTT UI Lifecycle State
    enum class PttState {
        IDLE,
        LISTENING,
        PROCESSING,
        COMPRESSING,
        TRANSMITTING,
        DELIVERED,
        FAILED,
        EMERGENCY
    }

    private val _pttState = MutableStateFlow(PttState.IDLE)
    val pttState: StateFlow<PttState> = _pttState.asStateFlow()

    val micAudioLevel: StateFlow<Float> = combine(
        audioCaptureManager.currentAmplitude,
        speechManager.audioRms
    ) { pcmLevel, speechRms ->
        maxOf(pcmLevel, speechRms)
    }.stateIn(scope, SharingStarted.Eagerly, 0f)

    val linkMetrics: StateFlow<LinkMetrics> = networkMonitor.metrics
    val messages: StateFlow<List<ChatMessage>> = database.messagesFlow
    val nodes: StateFlow<List<NetworkNode>> = database.nodesFlow
    val userProfile: StateFlow<org.sih.itantra.storage.UserProfile> = database.userProfileFlow
    val edgeServerStatus: StateFlow<org.sih.itantra.ml.translation.EdgeServerStatus> = translatorEngine.edgeServerStatus

    private val _screenRefreshRate = MutableStateFlow(120)
    val screenRefreshRate: StateFlow<Int> = _screenRefreshRate.asStateFlow()

    fun setScreenRefreshRate(hz: Int) {
        _screenRefreshRate.value = hz
    }

    fun updateUserProfile(
        username: String,
        gender: String = "Male",
        preferredLanguage: Language = _selectedLanguage.value,
        callsign: String = "AKHET-1",
        role: String = "Tactical Unit"
    ) {
        val updated = org.sih.itantra.storage.UserProfile(
            username = username.trim(),
            gender = gender.trim(),
            preferredLanguage = preferredLanguage,
            callsign = callsign.trim(),
            role = role.trim(),
            isInitialized = true
        )
        database.saveUserProfile(updated)
        setSelectedLanguage(preferredLanguage)
        scanForConnectedDevices()
    }

    fun selectPrivatePeer(node: NetworkNode?) {
        _activeTargetNode.value = node
    }

    fun clearPrivatePeer() {
        _activeTargetNode.value = null
    }

    fun sendPredefinedCommand(commandText: String) {
        stopPttCaptureAndSend(customTextOverride = commandText)
    }

    init {
        // Team mesh key (passphrase kept encrypted by the Android Keystore) before any radio starts
        MeshCrypto.setPassphrase(database.getMeshPassphrase())
        emergencyManager.restoreLogs(database.loadSosLogs())
        emergencyManager.logPersister = { database.saveSosLogs(it) }
        locationProvider.requestFreshFix()

        translatorEngine.initContext(context)
        (transportManager.getTransport(TransportType.WIFI_DIRECT) as? org.sih.itantra.network.transport.WifiDirectTransport)?.localNodeId = localNodeId
        networkMonitor.startMonitoring(scope) { currentRadioRssi() }

        // Real-time performance telemetry: periodic CPU/RAM/model-footprint sampling, plus live
        // STT latency/confidence recorded straight from the device's own speech recognizer.
        performanceMonitor.start(
            scope = scope,
            totalNeuralModels = Language.entries.size,
            downloadedModelsProvider = {
                speechPacks.refresh()
                speechPacks.status.value.count { it.value.stt && it.value.tts }
            }
        )
        speechManager.onSttCompleted = { latencyMs, rtf ->
            performanceMonitor.recordSttLatency(latencyMs)
            performanceMonitor.recordSttRtf(rtf)
        }
        // Phone mode is half-duplex at the input: ignore the mic while our own speaker is talking.
        scope.launch {
            ttsEngine.isSpeaking.collect { speaking -> speechManager.setInputPaused(speaking && _handsFree.value) }
        }

        // 1. Synchronize real hardware battery level directly with network monitor metrics
        networkMonitor.updateBattery(batteryManager.batteryLevel.value)
        scope.launch {
            batteryManager.batteryLevel.collect { level ->
                networkMonitor.updateBattery(level)
            }
        }

        // 2. Periodic background probe to detect if Base Station GPU (app.py) is online
        scope.launch {
            while (isActive) {
                try {
                    translatorEngine.checkEdgeServerConnection()
                } catch (_: Exception) {}
                delay(4000L)
            }
        }

        scope.launch {
            transportManager.startAll()
        }

        // Listen for incoming delivered packets from mesh router
        scope.launch {
            meshRouter.deliveredPackets.collect { packet ->
                handleDeliveredPacket(packet)
            }
        }

        // Connect raw transport feed to mesh router
        scope.launch {
            transportManager.incomingPackets.collect { rx ->
                val sender = rx.packet.senderId.trim()
                if (!sender.equals(localNodeId.trim(), ignoreCase = true)) {
                    lastRx[sender] = rx
                    networkMonitor.setTransport(rx.transport)
                }
                meshRouter.routeIncomingPacket(rx.packet)
            }
        }

        // A peer retransmitted something we already accepted => our ACK was lost; ACK it again.
        scope.launch {
            meshRouter.duplicateDeliveries.collect { dup ->
                MeshCrypto.open(dup)?.let { sendAck(it) }
            }
        }

        // Link latency/jitter probe: broadcast a HEARTBEAT ping; every peer answers with an ACK.
        scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                if (database.nodesFlow.value.isEmpty()) continue
                try {
                    val ping = MeshCrypto.seal(Packet(
                        senderId = localNodeId,
                        receiverId = "BRDCAST_",
                        packetType = PacketType.HEARTBEAT,
                        ttl = 1,
                        payload = ByteArray(0)
                    ))
                    pendingPings[ping.messageId] = System.currentTimeMillis()
                    pendingPings.entries.removeIf { System.currentTimeMillis() - it.value > 30_000 }
                    transportManager.transmitPacket(ping)
                } catch (t: Throwable) {
                    android.util.Log.w("CommunicationRepo", "Ping failed: ${t.message}")
                }
            }
        }

        // Warm up the on-device speech models for the operator's language so the first PTT
        // press and the first incoming message don't wait for a model load.
        speechManager.prepare(_selectedLanguage.value)
        ttsEngine.prepare(_selectedLanguage.value)

        // Ultra-fast 1.0s discovery heartbeat + real-time 3.0s disconnected device pruning
        scope.launch {
            delay(250L) // Initial quick broadcast on startup
            while (isActive) {
                try {
                    scanForConnectedDevices()
                    // Beacons go out every 1s, but Wi-Fi power-save and busy APs drop UDP broadcasts,
                    // so a peer is only considered gone after ~12s of silence (3s made peers flicker/vanish).
                    database.pruneStaleNodes(maxAgeMs = PEER_TIMEOUT_MS)
                } catch (t: Throwable) {
                    android.util.Log.w("CommunicationRepo", "Discovery tick failed: ${t.message}")
                }
                delay(1000L)
            }
        }
    }

    fun setSelectedLanguage(lang: Language) {
        _selectedLanguage.value = lang
        speechManager.prepare(lang)
        ttsEngine.prepare(lang)
    }

    fun setReceiverLanguage(lang: Language) {
        _receiverLanguage.value = lang
    }

    fun setActiveTargetNode(node: NetworkNode?) {
        _activeTargetNode.value = node
    }

    // ------------------------------------------------------------------ voice capture

    private val _handsFree = MutableStateFlow(false)
    /** Phone mode: mic stays open, every sentence is sent when the speaker pauses (no PTT). */
    val handsFree: StateFlow<Boolean> = _handsFree.asStateFlow()

    /** Why speech input can't run right now (e.g. the language pack isn't installed), else null. */
    val speechUnavailableReason: StateFlow<String?> get() = speechManager.unavailableReason

    private val sendSentence: (String, Language) -> Unit = { text, _ -> scope.launch { transmitText(text) } }

    /**
     * Step 1: PTT pressed. Speech is segmented by the on-device VAD; every sentence closed by a
     * pause is recognised and transmitted immediately, while the button is still held.
     */
    fun startPttCapture() {
        if (_handsFree.value) return
        _pttState.value = PttState.LISTENING
        performanceMonitor.setActive(true)
        speechManager.clear()
        speechManager.onSentence = sendSentence
        speechManager.startListening(_selectedLanguage.value)
    }

    /**
     * Step 2: PTT released (closes and sends the last sentence), or a typed message is sent.
     */
    fun stopPttCaptureAndSend(customTextOverride: String? = null) {
        scope.launch {
            try {
                if (!customTextOverride.isNullOrBlank()) {
                    transmitText(customTextOverride.trim())
                    return@launch
                }
                if (_handsFree.value) return@launch
                _pttState.value = PttState.PROCESSING
                val transcript = speechManager.stopListening()
                if (transcript.isBlank()) {
                    android.util.Log.d("CommunicationRepo", "No speech detected during PTT; nothing sent")
                    _pttState.value = PttState.IDLE
                }
            } catch (t: Throwable) {
                android.util.Log.e("CommunicationRepository", "PTT stop failed: ${t.message}", t)
                _pttState.value = PttState.FAILED
            } finally {
                if (!_handsFree.value) performanceMonitor.setActive(false)
            }
        }
    }

    /** Switch between walkie-talkie (PTT) and phone mode (open mic, sentences sent on pause). */
    fun setHandsFreeMode(enabled: Boolean) {
        if (_handsFree.value == enabled) return
        _handsFree.value = enabled
        if (enabled) {
            speechManager.clear()
            speechManager.onSentence = sendSentence
            speechManager.startListening(_selectedLanguage.value, handsFree = true)
            performanceMonitor.setActive(true)
            _pttState.value = PttState.LISTENING
        } else {
            scope.launch {
                speechManager.stopListening()
                speechManager.setInputPaused(false)
                performanceMonitor.setActive(false)
                _pttState.value = PttState.IDLE
            }
        }
    }

    // ------------------------------------------------------------------ speech packs over Wi-Fi

    private val _speechPackDownload = MutableStateFlow<String?>(null)
    /** Progress text while a speech pack is being pulled from the base station, else null. */
    val speechPackDownload: StateFlow<String?> = _speechPackDownload.asStateFlow()

    /**
     * Pull one language's open-source speech models from the laptop base station over the local
     * Wi-Fi/hotspot (no internet). Files are streamed to disk and only renamed into place once
     * complete, so an interrupted transfer never leaves a half-written model behind.
     */
    fun downloadSpeechPackFromBaseStation(lang: Language) {
        if (_speechPackDownload.value != null) return
        _speechPackDownload.value = "Looking for the base station..."
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val port = translatorEngine.edgeServerPort
                val host = listOfNotNull(
                    translatorEngine.edgeServerHost, translatorEngine.getDetectedGatewayHost(),
                    "192.168.137.1", "192.168.43.1", "10.0.2.2"
                ).distinct().firstOrNull { baseStationReachable(it, port) }
                if (host == null) {
                    _speechPackDownload.value = "Base station not reachable on this Wi-Fi/hotspot"
                    delay(4000)
                    return@launch
                }
                val files = buildList {
                    if (speechPacks.vadModel() == null) add("common/vad/silero_vad.onnx" to "common/silero_vad.onnx")
                    listOf("stt/model.int8.onnx", "stt/tokens.txt", "tts/model.onnx", "tts/tokens.txt")
                        .forEach { add("${lang.code}/$it" to "${lang.code}/$it") }
                }
                files.forEachIndexed { i, (remote, local) ->
                    val dest = java.io.File(speechPacks.internalRoot, local)
                    if (dest.isFile && dest.length() > 0) return@forEachIndexed
                    streamToFile("http://$host:$port/api/speechpack/$remote", dest) { pct ->
                        _speechPackDownload.value = "${lang.displayName}: file ${i + 1}/${files.size} - $pct%"
                    }
                }
                speechPacks.refresh()
                if (lang == _selectedLanguage.value) {
                    speechManager.prepare(lang)
                    ttsEngine.prepare(lang)
                }
                _speechPackDownload.value = "${lang.displayName} speech pack installed"
                delay(3000)
            } catch (t: Throwable) {
                android.util.Log.w("CommunicationRepo", "Speech pack download failed: ${t.message}")
                _speechPackDownload.value = "Download failed: ${t.message ?: "connection lost"}"
                delay(4000)
            } finally {
                _speechPackDownload.value = null
            }
        }
    }

    private fun baseStationReachable(host: String, port: Int): Boolean = try {
        val conn = java.net.URL("http://$host:$port/api/status").openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 800
        conn.readTimeout = 1500
        (conn.responseCode == 200).also { conn.disconnect() }
    } catch (_: Exception) {
        false
    }

    private fun streamToFile(url: String, dest: java.io.File, onProgress: (Int) -> Unit) {
        dest.parentFile?.mkdirs()
        val part = java.io.File(dest.parentFile, dest.name + ".part")
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode} for ${dest.name}")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                }
            }
            if (total > 0 && done != total) throw java.io.IOException("incomplete ${dest.name}")
            if (!part.renameTo(dest)) throw java.io.IOException("could not save ${dest.name}")
        } finally {
            conn.disconnect()
            part.delete()
        }
    }

    /** Text -> semantic encoding -> compression -> encrypted packet -> mesh transmit -> local log. */
    private suspend fun transmitText(textToUse: String) {
        if (textToUse.isBlank()) return
        try {
            // 1. Offline STT Transcription
            val sttStart = System.currentTimeMillis()
            val sttResult = sttEngine.transcribeText(textToUse, _selectedLanguage.value)
            val sttDuration = System.currentTimeMillis() - sttStart

            // 2. Semantic Extraction (General Comms is strictly non-emergency mode)
            val semStart = System.currentTimeMillis()
            val semantic = semanticEncoder.encode(
                sttResult.transcript,
                _selectedLanguage.value,
                _receiverLanguage.value
            )
            val semDuration = System.currentTimeMillis() - semStart

            // 3. Adaptive Compression (General Comms priority is always NORMAL)
            _pttState.value = PttState.COMPRESSING
            val compStart = System.currentTimeMillis()
            val currentLq = linkMetrics.value.linkQuality
            val compResult = compressor.compress(
                sttResult.transcript,
                semantic,
                currentLq,
                Priority.NORMAL
            )
            val compDuration = System.currentTimeMillis() - compStart

            // 4. Packetization with CRC16 Checksum
            _pttState.value = PttState.TRANSMITTING
            val targetNodeId = _activeTargetNode.value?.id ?: "BRDCAST_"
            val actualSourceLang = translatorEngine.detectScriptLanguage(textToUse) ?: _selectedLanguage.value
            val packet = MeshCrypto.seal(Packet(
                senderId = meshRouter.localNodeId,
                receiverId = targetNodeId,
                languageId = actualSourceLang.id,
                priority = Priority.NORMAL,
                packetType = compResult.packetType,
                payload = compResult.payload
            ))

            // 5. Mesh Queueing and Transmission
            val txStart = System.currentTimeMillis()
            meshRouter.enqueueOutgoing(packet)
            val txSuccess = transportManager.transmitPacket(packet)
            val txDuration = System.currentTimeMillis() - txStart

            networkMonitor.recordPacketTransmission(txSuccess, txDuration)

            // 6. Log in Encrypted Local Database
            val telemetry = TechnicalTelemetry(
                sttDurationMs = sttDuration,
                semanticDurationMs = semDuration,
                compressionDurationMs = compDuration,
                networkTransitDurationMs = txDuration,
                reassemblyDurationMs = 5L,
                ttsDurationMs = 85L,
                payloadBytes = packet.payloadLength,
                rawEquivalentBytes = 64000
            )

            val chatMessage = ChatMessage(
                senderId = meshRouter.localNodeId,
                senderName = "${userProfile.value.username} (You)",
                receiverId = targetNodeId,
                text = sttResult.transcript,
                reconstructedSpeechText = semanticDecoder.decode(semantic, _selectedLanguage.value),
                priority = semantic.priority,
                packetType = compResult.packetType,
                transport = linkMetrics.value.activeTransport,
                language = _selectedLanguage.value,
                // DELIVERED_ACK is only set once a peer actually acknowledges (see trackDelivery)
                status = if (txSuccess) DeliveryStatus.TRANSMITTING else DeliveryStatus.FAILED,
                isIncoming = false,
                timestamp = System.currentTimeMillis(),
                telemetry = telemetry
            )

            database.saveMessage(chatMessage)

            _pttState.value = if (semantic.priority == Priority.EMERGENCY) {
                PttState.EMERGENCY
            } else if (txSuccess) {
                PttState.TRANSMITTING
            } else {
                PttState.FAILED
            }
            if (txSuccess) trackDelivery(packet, chatMessageId = chatMessage.id, sosLogId = null)
        } catch (t: Throwable) {
            android.util.Log.e("CommunicationRepository", "Transmit failed: ${t.message}", t)
            _pttState.value = PttState.FAILED
        }
    }

    /**
     * Receiver Pipeline:
     * Receives packet -> verifies CRC16 -> decompresses -> reconstructs text in receiver language -> synthesizes TTS
     */
    private fun handleDeliveredPacket(wirePacket: Packet) {
        val packetReceivedAt = System.currentTimeMillis()
        try {
            // Authenticate + decrypt with the team mesh key. Packets from another team (different
            // passphrase) or tampered in transit fail GCM authentication and are dropped here.
            val packet = MeshCrypto.open(wirePacket) ?: run {
                android.util.Log.d("CommunicationRepository", "Dropped packet from ${wirePacket.senderId}: not decryptable with this mesh key")
                return
            }
            // CRITICAL CHECK: Ignore packets sent by ourselves!
            // The sender speaks into their mic; only the opposite recipient should hear the TTS playback!
            // Also ensures local phone is never registered as a peer on its own radar.
            val cleanSender = packet.senderId.trim()
            val cleanLocal = localNodeId.trim()
            if (cleanSender.equals(cleanLocal, ignoreCase = true)) {
                android.util.Log.d("CommunicationRepository", "Suppressing self packet from $cleanSender - will not process or play TTS on sender phone")
                return
            }

            val rx = lastRx[packet.senderId.trim()]

            if (packet.packetType == PacketType.ACK) {
                handleAck(packet)
                return
            }
            if (packet.packetType == PacketType.HEARTBEAT) {
                sendAck(packet) // ping reply
                return
            }

            // Handle discovery beacon packets without producing chat/TTS
            if (packet.packetType == PacketType.HELLO) {
                networkMonitor.recordBeacon(packet.senderId.trim(), rx?.rssi)
                var peerBattery = batteryManager.batteryLevel.value
                val peerUsername = try {
                    val payloadStr = String(packet.payload, Charsets.UTF_8)
                    if (payloadStr.startsWith("iTANTRA_PEER|")) {
                        val parts = payloadStr.split("|")
                        if (parts.size >= 3) {
                            peerBattery = parts[2].trim().toIntOrNull() ?: peerBattery
                        }
                        if (parts.size >= 2 && parts[1].isNotBlank()) parts[1].trim() else "User ${packet.senderId}"
                    } else {
                        "User ${packet.senderId}"
                    }
                } catch (_: Exception) {
                    "User ${packet.senderId}"
                }

                val remoteNode = org.sih.itantra.domain.model.NetworkNode(
                    id = packet.senderId,
                    name = peerUsername,
                    rssi = rx?.rssi ?: currentRadioRssi() ?: -60,
                    batteryPct = peerBattery,
                    transportType = rx?.transport ?: TransportType.WIFI_DIRECT,
                    hopCount = 1,
                    isDirectNeighbor = true
                )
                database.addOrUpdateNode(remoteNode)
                return
            }

            if (packet.packetType.needsAck()) sendAck(packet)

            val decompressed = compressor.decompress(packet.payload, packet.packetType, packet.language)
            val locationSuffix = if (decompressed.latitude != null && decompressed.longitude != null) {
                String.format(java.util.Locale.ROOT, "\nGPS %.5f, %.5f", decompressed.latitude, decompressed.longitude)
            } else ""
            val isSos = packet.packetType == PacketType.SOS || packet.packetType == PacketType.EMERGENCY || packet.priority == Priority.EMERGENCY

            // Extract sender username from payload or known nodes
            val rawDistressUser = if (isSos && decompressed.semantic?.rawText?.isNotBlank() == true &&
                !decompressed.semantic.rawText.startsWith("SOS_BEACON") &&
                !decompressed.semantic.rawText.startsWith("CRITICAL SOS")) {
                decompressed.semantic.rawText
            } else null

            val senderDisplayName = rawDistressUser
                ?: database.nodesFlow.value.find { it.id == packet.senderId }?.name
                ?: "Operator ${packet.senderId.takeLast(4)}"

            // 1. Extract base text from incoming packet
            val baseText = if (isSos) {
                when (_selectedLanguage.value) {
                    Language.TAMIL -> "அவசர எச்சரிக்கை! $senderDisplayName அவசர உதவி கோரியுள்ளார்! உடனடி உதவி தேவை!"
                    Language.HINDI -> "आपातकालीन चेतावनी! $senderDisplayName ने आपातकालीन संकट चेतावनी भेजी है! तत्काल सहायता चाहिए!"
                    Language.TELUGU -> "అత్యవసర హెచ్చరిక! $senderDisplayName అత్యవసర సహాయం కోరారు! తక్షణ సహాయం కావాలి!"
                    Language.KANNADA -> "ತುರ್ತು ಎಚ್ಚರಿಕೆ! $senderDisplayName ತುರ್ತು ಸಹಾಯ ಕೋರಿದ್ದಾರೆ! ತಕ್ಷಣ ನೆರವು ಬೇಕು!"
                    Language.MALAYALAM -> "അടിയന്തര മുന്നറിയിപ്പ്! $senderDisplayName അടിയന്തര സഹായം ആവശ്യപ്പെട്ടു! ഉടൻ സഹായം എത്തിക്കുക!"
                    Language.BENGALI -> "জরুরী সতর্কতা! $senderDisplayName জরুরী সহায়তা চেয়েছেন!"
                    Language.MARATHI -> "तातडीचा इशारा! $senderDisplayName यांनी आणीबाणी मदतीची मागणी केली आहे!"
                    Language.GUJARATI -> "કટોકટી ચેતવણી! $senderDisplayName એ તાત્કાલિક સહાયની વિનંતી કરી છે!"
                    Language.ODIA -> "ଜରୁରୀକାଳୀନ ଚେତାବନୀ! $senderDisplayName ଜରୁରୀ ପରିସ୍ଥିତିରେ ଅଛନ୍ତି! ତୁରନ୍ତ ସହାୟତା ଆବଶ୍ୟକ!"
                    Language.PUNJABI -> "ਐਮਰਜੈਂਸੀ ਅਲਰਟ! $senderDisplayName ਨੇ ਤੁਰੰਤ ਮਦਦ ਦੀ ਬੇਨਤੀ ਕੀਤੀ ਹੈ!"
                    else -> "Emergency SOS! User $senderDisplayName is in emergency! Need immediate assistance!"
                }
            } else if (decompressed.semantic != null && decompressed.semantic.rawText.isNotBlank()) {
                decompressed.semantic.rawText
            } else if (decompressed.reconstructedText.isNotBlank()) {
                decompressed.reconstructedText
            } else if (decompressed.semantic != null) {
                semanticDecoder.decode(decompressed.semantic, _selectedLanguage.value)
            } else {
                "Radio transmission received"
            }

            // 2. Offline Translate into this receiver's selected language (e.g. Tamil, Hindi, or English)
            val actualIncomingLanguage = translatorEngine.detectScriptLanguage(baseText) ?: packet.language
            val localTargetLanguage = _selectedLanguage.value

            val reconstructedText = if (isSos) {
                baseText
            } else if (actualIncomingLanguage == localTargetLanguage) {
                baseText
            } else {
                val translated = try {
                    translatorEngine.translate(baseText, localTargetLanguage, actualIncomingLanguage)
                } catch (t: Throwable) {
                    android.util.Log.w("CommunicationRepository", "Translation failed, using original text: ${t.message}")
                    ""
                }
                if (translated.isNotBlank()) {
                    translated
                } else if (packet.priority == Priority.EMERGENCY && decompressed.semantic != null) {
                    semanticDecoder.decode(decompressed.semantic, localTargetLanguage)
                } else {
                    baseText
                }
            }

            val incomingMessage = ChatMessage(
                id = packet.messageId.toString(),
                senderId = packet.senderId,
                senderName = senderDisplayName,
                receiverId = packet.receiverId,
                text = reconstructedText + locationSuffix,
                reconstructedSpeechText = reconstructedText,
                priority = packet.priority,
                packetType = packet.packetType,
                transport = rx?.transport ?: linkMetrics.value.activeTransport,
                language = _selectedLanguage.value,
                status = DeliveryStatus.RECEIVED,
                isIncoming = true,
                timestamp = System.currentTimeMillis(),
                telemetry = TechnicalTelemetry(
                    payloadBytes = packet.payloadLength,
                    rawEquivalentBytes = 64000
                )
            )

            database.saveMessage(incomingMessage)

            if (isSos) {
                _sosAlerts.tryEmit(SosAlertEvent(senderId = packet.senderId, senderName = senderDisplayName, message = reconstructedText + locationSuffix))
            }

            // Automatically register incoming peer in discovered mesh nodes list using username and real battery
            val existingBattery = database.nodesFlow.value.find { it.id == packet.senderId }?.batteryPct ?: batteryManager.batteryLevel.value
            val remoteNode = org.sih.itantra.domain.model.NetworkNode(
                id = packet.senderId,
                name = senderDisplayName,
                rssi = rx?.rssi ?: currentRadioRssi() ?: -60,
                batteryPct = existingBattery,
                transportType = rx?.transport ?: linkMetrics.value.activeTransport,
                hopCount = 1,
                isDirectNeighbor = true
            )
            database.addOrUpdateNode(remoteNode)

            // Real cross-device latency: sender's packet-build time (embedded in messageId, see
            // Packet.originTimestampMs) -> this device receiving it. Skipped for self-echoed/replayed
            // packets with a clearly bogus (non-positive) delta.
            val crossDeviceMs = packetReceivedAt - packet.originTimestampMs
            if (crossDeviceMs >= 0) performanceMonitor.recordCrossDeviceLatency(crossDeviceMs)

            // Synthesize speech locally if not muted; time packet-arrival -> first audio frame, and
            // the TTS engine's own start-latency/RTF/success, all fed into the live diagnostics screen.
            ttsEngine.speak(
                reconstructedText,
                _selectedLanguage.value,
                alert = isSos,
                onMetrics = { startLatencyMs, audioDurationMs, success ->
                    performanceMonitor.recordTtsOutcome(success)
                    if (success) {
                        performanceMonitor.recordTtsTiming(startLatencyMs, audioDurationMs)
                        // This callback fires when audio finishes (now ~= audioStart + audioDuration),
                        // so subtracting audioDuration back out recovers audioStart - packetReceivedAt:
                        // the real "text received -> audio actually playing" delay.
                        val packetToAudioStartMs = (System.currentTimeMillis() - packetReceivedAt - audioDurationMs).coerceAtLeast(0)
                        performanceMonitor.recordTtsPacketToAudioLatency(packetToAudioStartMs)
                    }
                }
            )
        } catch (t: Throwable) {
            android.util.Log.e("CommunicationRepository", "Error handling delivered packet: ${t.message}", t)
        }
    }

    /**
     * Broadcasts an encrypted HELLO beacon so peers on the same mesh key can discover this node.
     * Goes out over Wi-Fi and BLE every tick; over LoRa only every [LORA_BEACON_EVERY_N_TICKS] ticks
     * to respect the narrow LoRa channel's duty cycle.
     */
    fun scanForConnectedDevices() {
        database.pruneStaleNodes(maxAgeMs = PEER_TIMEOUT_MS)
        val includeLora = (discoveryTick++ % LORA_BEACON_EVERY_N_TICKS) == 0
        scope.launch {
            val myUsername = userProfile.value.username.replace("|", " ")
            val beacon = MeshCrypto.seal(Packet(
                senderId = localNodeId,
                receiverId = "BRDCAST_",
                packetType = PacketType.HELLO,
                priority = Priority.NORMAL,
                ttl = 1,
                payload = "iTANTRA_PEER|$myUsername|${batteryManager.batteryLevel.value}".toByteArray(Charsets.UTF_8)
            ))
            transportManager.transmitPacket(beacon, TransportType.WIFI_DIRECT)
            transportManager.transmitPacket(beacon, TransportType.BLE)
            if (includeLora) transportManager.transmitPacket(beacon, TransportType.LORA)
        }
    }

    // ------------------------------------------------------------------ delivery confirmation

    private fun sendAck(original: Packet) {
        scope.launch {
            val ack = MeshCrypto.seal(Packet(
                senderId = localNodeId,
                receiverId = original.senderId.trim(),
                priority = original.priority,
                packetType = PacketType.ACK,
                payload = java.nio.ByteBuffer.allocate(8).putLong(original.messageId).array()
            ))
            transportManager.transmitPacket(ack)
        }
    }

    private fun handleAck(ack: Packet) {
        if (ack.payload.size < 8) return
        val ackedId = java.nio.ByteBuffer.wrap(ack.payload).long
        val now = System.currentTimeMillis()

        pendingPings[ackedId]?.let { sentAt -> networkMonitor.recordRoundTrip(now - sentAt) }

        val pending = pendingAcks[ackedId] ?: return
        val firstAckFromPeer = pending.ackers.add(ack.senderId.trim())
        if (!firstAckFromPeer) return
        networkMonitor.recordRoundTrip(now - pending.lastSentAt)
        pending.chatMessageId?.let { database.updateMessageStatus(it, DeliveryStatus.DELIVERED_ACK) }
        pending.sosLogId?.let { emergencyManager.recordDelivery(it) }
        if (_pttState.value == PttState.TRANSMITTING) _pttState.value = PttState.DELIVERED
    }

    /**
     * Waits for an ACK and retransmits (same message id, so receivers de-duplicate) up to
     * [MAX_SEND_ATTEMPTS] times. Without any ACK the message is marked FAILED. SOS entries stay
     * registered for a while longer so every peer that answers is counted.
     */
    private fun trackDelivery(packet: Packet, chatMessageId: String?, sosLogId: String?) {
        val pending = PendingSend(packet, chatMessageId, sosLogId)
        pendingAcks[packet.messageId] = pending
        scope.launch {
            var attempt = 1
            while (pending.ackers.isEmpty() && attempt < MAX_SEND_ATTEMPTS) {
                delay(ACK_TIMEOUT_MS)
                if (pending.ackers.isNotEmpty()) break
                attempt++
                chatMessageId?.let { database.updateMessageStatus(it, DeliveryStatus.RETRYING) }
                pending.lastSentAt = System.currentTimeMillis()
                transportManager.transmitPacket(packet)
            }
            if (pending.ackers.isEmpty()) delay(ACK_TIMEOUT_MS)
            if (pending.ackers.isEmpty()) {
                networkMonitor.recordPacketTransmission(false, 0)
                chatMessageId?.let { database.updateMessageStatus(it, DeliveryStatus.FAILED) }
                if (_pttState.value == PttState.TRANSMITTING) _pttState.value = PttState.FAILED
            }
            delay(if (sosLogId != null) 60_000L else 10_000L)
            pendingAcks.remove(packet.messageId)
        }
    }

    /** Signal strength of the radio actually in use: Wi-Fi association RSSI, else BLE, else LoRa. */
    @Suppress("DEPRECATION")
    private fun currentRadioRssi(): Int? {
        val wifi = try {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
                ?.connectionInfo?.rssi?.takeIf { it in -126..-1 }
        } catch (_: Throwable) { null }
        if (wifi != null) return wifi
        val ble = (transportManager.getTransport(TransportType.BLE) as? BluetoothTransport)?.bestPeerRssi()
        if (ble != null) return ble
        return (transportManager.getTransport(TransportType.LORA) as? LoraTransport)?.lastLinkRssi()
    }

    /** Changes the team mesh passphrase. Every device in the team must use the same one. */
    fun setMeshPassphrase(passphrase: String) {
        val p = passphrase.trim().ifBlank { MeshCrypto.DEFAULT_PASSPHRASE }
        database.setMeshPassphrase(p)
        MeshCrypto.setPassphrase(p)
        database.clearNodes()
    }

    val isUsingDefaultMeshKey: Boolean get() = MeshCrypto.isUsingDefaultPassphrase

    fun triggerEmergencySos(customDistressMessage: String = "") {
        val fix = locationProvider.lastKnownLocation()
        locationProvider.requestFreshFix()
        val sosLogId = emergencyManager.triggerSos(
            source = "EMERGENCY_SCREEN",
            coroutineScope = scope,
            nearbyDevices = database.nodesFlow.value.size,
            latitude = fix?.latitude,
            longitude = fix?.longitude
        )
        val myUsername = userProfile.value.username.ifBlank { "Operator_${localNodeId.takeLast(4)}" }
        val msgToSend = if (customDistressMessage.isNotBlank()) customDistressMessage else myUsername

        // Immediately broadcast emergency packet with sender username embedded
        val emergencySemantic = SemanticMessage(
            intent = org.sih.itantra.domain.model.SemanticIntent.EMERGENCY_MEDICAL,
            priority = Priority.EMERGENCY,
            rawText = msgToSend,
            sourceLanguage = _selectedLanguage.value,
            targetLanguage = _receiverLanguage.value
        )
        val compResult = compressor.compress(
            msgToSend,
            emergencySemantic,
            org.sih.itantra.domain.model.LinkQuality.EMERGENCY,
            Priority.EMERGENCY
        )
        // [12B beacon][optional 9B GPS block][sender name] - SOS stays plaintext so any rescuer hears it
        val locationBlock = fix?.let { compressor.encodeSosLocation(it.latitude, it.longitude) } ?: ByteArray(0)
        val sosPayload = compResult.payload + locationBlock + myUsername.toByteArray(Charsets.UTF_8)

        val sosPacket = Packet(
            senderId = meshRouter.localNodeId,
            receiverId = "BRDCAST_",
            languageId = _selectedLanguage.value.id,
            priority = Priority.EMERGENCY,
            packetType = PacketType.SOS,
            payload = sosPayload
        )
        scope.launch {
            meshRouter.enqueueOutgoing(sosPacket)
            if (transportManager.transmitPacket(sosPacket)) {
                trackDelivery(sosPacket, chatMessageId = null, sosLogId = sosLogId)
            }
        }
    }

    fun stopEmergencySos() {
        emergencyManager.stopSos()
    }
}
