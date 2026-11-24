package org.sih.itantra.network.transport

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.sih.itantra.domain.model.Packet
import org.sih.itantra.domain.model.TransportType

interface NetworkTransport {
    val transportType: TransportType
    val isAvailable: Boolean
    suspend fun start()
    suspend fun stop()
    suspend fun sendPacket(packet: Packet, destinationAddress: String? = null): Boolean
}

/** A packet as it came off a radio, with the bearer it used and (if the radio reports it) its RSSI. */
data class ReceivedPacket(
    val packet: Packet,
    val transport: TransportType,
    val rssi: Int? = null
)

/**
 * TransportManager owns every bearer (Wi-Fi LAN/Direct, BLE, LoRa, Loopback).
 *
 * Outgoing packets are sent over **every available wireless bearer at once**: the mesh router's
 * duplicate suppression makes this safe, and it means a message still gets through when one radio is
 * blocked or out of range. A specific bearer can still be forced (tests, demo).
 */
class TransportManager(
    private val context: Context,
    private val bluetoothTransport: BluetoothTransport = BluetoothTransport(context),
    private val wifiDirectTransport: WifiDirectTransport = WifiDirectTransport(context),
    private val loraTransport: LoraTransport = LoraTransport(context),
    private val loopbackTransport: LoopbackTransport = LoopbackTransport()
) {
    private val _incomingPackets = MutableSharedFlow<ReceivedPacket>(extraBufferCapacity = 128)
    val incomingPackets: SharedFlow<ReceivedPacket> = _incomingPackets.asSharedFlow()

    init {
        loopbackTransport.onPacketReceived = { _incomingPackets.tryEmit(ReceivedPacket(it, TransportType.LOOPBACK)) }
        wifiDirectTransport.onPacketReceived = { _incomingPackets.tryEmit(ReceivedPacket(it, TransportType.WIFI_DIRECT)) }
        bluetoothTransport.onPacketReceived = { packet, rssi -> _incomingPackets.tryEmit(ReceivedPacket(packet, TransportType.BLE, rssi)) }
        loraTransport.onPacketReceived = { packet, rssi -> _incomingPackets.tryEmit(ReceivedPacket(packet, TransportType.LORA, rssi)) }
    }

    private val wireless: List<NetworkTransport>
        get() = listOf(wifiDirectTransport, bluetoothTransport, loraTransport)

    /** Bearers that are currently up and able to carry traffic. */
    fun availableTransports(): List<TransportType> = wireless.filter { it.isAvailable }.map { it.transportType }

    suspend fun startAll() {
        // Each transport is isolated so a failure in one can never prevent the others from starting.
        val starters: List<Pair<String, suspend () -> Unit>> = listOf(
            "wifi" to { wifiDirectTransport.start() },
            "bluetooth" to { bluetoothTransport.start() },
            "lora" to { loraTransport.start() },
            "loopback" to { loopbackTransport.start() }
        )
        for ((name, start) in starters) {
            try {
                start()
            } catch (t: Throwable) {
                android.util.Log.e("TransportManager", "Transport '$name' failed to start: ${t.message}", t)
            }
        }
    }

    suspend fun stopAll() {
        for (t in wireless + loopbackTransport) {
            try { t.stop() } catch (_: Throwable) {}
        }
    }

    /**
     * Sends [packet]. With no [preferredTransport] it goes out on every available wireless bearer and
     * succeeds if at least one bearer accepted it.
     */
    suspend fun transmitPacket(packet: Packet, preferredTransport: TransportType? = null): Boolean {
        if (preferredTransport != null) {
            val t = getTransport(preferredTransport)
            return try { t.sendPacket(packet) } catch (_: Throwable) { false }
        }
        val targets = wireless.filter { it.isAvailable }
        if (targets.isEmpty()) return false
        return coroutineScope {
            targets.map { t ->
                async {
                    try { t.sendPacket(packet) } catch (e: Throwable) {
                        android.util.Log.w("TransportManager", "${t.transportType} send failed: ${e.message}")
                        false
                    }
                }
            }.awaitAll().any { it }
        }
    }

    fun getTransport(type: TransportType): NetworkTransport = when (type) {
        TransportType.WIFI_DIRECT -> wifiDirectTransport
        TransportType.BLE -> bluetoothTransport
        TransportType.LORA -> loraTransport
        TransportType.LOOPBACK -> loopbackTransport
    }
}
