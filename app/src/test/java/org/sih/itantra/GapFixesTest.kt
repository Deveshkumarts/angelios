package org.sih.itantra

import org.junit.Assert.*
import org.junit.Test
import org.sih.itantra.domain.model.ChatMessage
import org.sih.itantra.domain.model.DeliveryStatus
import org.sih.itantra.domain.model.Language
import org.sih.itantra.domain.model.LinkQuality
import org.sih.itantra.domain.model.Packet
import org.sih.itantra.domain.model.PacketType
import org.sih.itantra.domain.model.Priority
import org.sih.itantra.domain.model.SemanticIntent
import org.sih.itantra.domain.model.SemanticMessage
import org.sih.itantra.network.compression.AdaptiveCompressor
import org.sih.itantra.network.mesh.MeshRouter
import org.sih.itantra.network.monitor.NetworkQualityMonitor
import org.sih.itantra.network.transport.LoraTransport
import org.sih.itantra.security.MeshCrypto
import org.sih.itantra.storage.MessageCodec
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch

class GapFixesTest {

    // ------------------------------------------------------------ mesh encryption

    private fun textPacket(text: String = "Medical team needed at bridge") = Packet(
        senderId = "N_1111",
        receiverId = "BRDCAST_",
        packetType = PacketType.NORMAL_TEXT,
        payload = text.toByteArray()
    )

    @Test
    fun sealedPacketHidesPlaintextAndRoundTripsOverTheWire() {
        MeshCrypto.setPassphrase("team-alpha-secret")
        val original = textPacket()
        val sealed = MeshCrypto.seal(original)
        assertEquals(MeshCrypto.VERSION_ENCRYPTED, sealed.version)
        assertFalse(String(sealed.payload, Charsets.ISO_8859_1).contains("Medical"))

        val wire = Packet.fromByteArray(sealed.toByteArray())!!
        val opened = MeshCrypto.open(wire)!!
        assertEquals("Medical team needed at bridge", String(opened.payload))
    }

    @Test
    fun wrongTeamKeyOrTamperingIsRejected() {
        MeshCrypto.setPassphrase("team-alpha-secret")
        val sealed = MeshCrypto.seal(textPacket())

        MeshCrypto.setPassphrase("team-bravo-secret")
        assertNull("other team's key must not decrypt", MeshCrypto.open(sealed))

        MeshCrypto.setPassphrase("team-alpha-secret")
        assertNull("spoofed sender must fail authentication", MeshCrypto.open(sealed.copy(senderId = "N_6666")))
        // A relay decrementing TTL is legitimate and must still verify
        assertNotNull(MeshCrypto.open(sealed.copy(ttl = 2)))
    }

    @Test
    fun plaintextIsOnlyAcceptedForSos() {
        MeshCrypto.setPassphrase(MeshCrypto.DEFAULT_PASSPHRASE)
        assertTrue(MeshCrypto.isUsingDefaultPassphrase)
        assertNull(MeshCrypto.open(textPacket()))
        val sos = Packet(senderId = "N_2222", packetType = PacketType.SOS, priority = Priority.EMERGENCY, payload = ByteArray(12))
        assertSame(sos, MeshCrypto.seal(sos))
        assertNotNull(MeshCrypto.open(sos))
    }

    // ------------------------------------------------------------ measured link quality

    @Test
    fun beaconLossAndRoundTripDriveLinkQuality() {
        var now = 1_000_000L
        val monitor = NetworkQualityMonitor(beaconIntervalMs = 1_000, windowMs = 10_000, clock = { now })

        // Peer beacons every second for 10 s: no loss
        repeat(10) { monitor.recordBeacon("N_1", rssi = -55); now += 1_000 }
        repeat(5) { monitor.recordRoundTrip(40) }
        monitor.recompute()
        val good = monitor.metrics.value
        assertEquals(1, good.peerCount)
        assertTrue("loss was ${good.packetLossPct}", good.packetLossPct < 15f)
        assertEquals(40L, good.latencyMs)
        assertEquals(LinkQuality.GOOD, good.linkQuality)

        // Next 10 s only 3 beacons get through and RTT climbs: link must degrade
        repeat(10) { i -> if (i % 3 == 0) monitor.recordBeacon("N_1", rssi = -90); now += 1_000 }
        repeat(20) { monitor.recordRoundTrip(700) }
        monitor.recompute()
        val bad = monitor.metrics.value
        assertTrue("loss was ${bad.packetLossPct}", bad.packetLossPct > 50f)
        assertTrue(bad.linkQualityScore < good.linkQualityScore)

        // Peer goes silent for a whole window: forgotten
        now += 20_000
        monitor.recompute()
        assertEquals(0, monitor.metrics.value.peerCount)
    }

    // ------------------------------------------------------------ mesh router

    @Test
    fun routerHasNoFakeNeighboursAndReportsDuplicateDeliveries() = runTest {
        val router = MeshRouter(localNodeId = "N_9999")
        assertTrue(router.neighbors.isEmpty())

        val duplicates = mutableListOf<Packet>()
        val job = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            router.duplicateDeliveries.collect { duplicates += it }
        }
        val p = textPacket()
        assertEquals(MeshRouter.RouteAction.ACCEPTED_AND_FORWARDED, router.routeIncomingPacket(p))
        assertEquals(MeshRouter.RouteAction.DROPPED_DUPLICATE, router.routeIncomingPacket(p))
        assertEquals(1, duplicates.size)
        job.cancel()
    }

    // ------------------------------------------------------------ LoRa serial framing

    @Test
    fun loraFramesSurviveSplitReadsAndNoise() {
        val packet = textPacket("hi").toByteArray()
        // radio -> phone frame: header, rssi=-87, snr=6, body
        val body = byteArrayOf((-87).toByte(), 6) + packet
        val frame = byteArrayOf(0xA5.toByte(), 0x5A, (body.size shr 8).toByte(), body.size.toByte()) + body
        val stream = byteArrayOf(0x00, 0x13) + frame

        val (none, rest) = LoraTransport.parseFrames(stream.copyOfRange(0, 10))
        assertTrue(none.isEmpty())
        val (frames, leftover) = LoraTransport.parseFrames(rest + stream.copyOfRange(10, stream.size))
        assertEquals(1, frames.size)
        assertEquals(-87, frames[0].second)
        assertArrayEquals(packet, frames[0].first)
        assertEquals(0, leftover.size)

        val out = LoraTransport.encodeFrame(packet)
        assertEquals(packet.size + 4, out.size)
    }

    // ------------------------------------------------------------ SOS location

    @Test
    fun sosBeaconCarriesGpsAndSenderName() {
        val c = AdaptiveCompressor()
        val semantic = SemanticMessage(
            intent = SemanticIntent.EMERGENCY_MEDICAL,
            priority = Priority.EMERGENCY,
            rawText = "Asha",
            sourceLanguage = Language.HINDI,
            targetLanguage = Language.TAMIL
        )
        val base = c.compress("Asha", semantic, LinkQuality.EMERGENCY, Priority.EMERGENCY).payload
        val payload = base + c.encodeSosLocation(12.97160, 77.59460) + "Asha".toByteArray()

        val result = c.decompress(payload, PacketType.SOS, Language.HINDI)
        assertEquals(12.97160, result.latitude!!, 1e-4)
        assertEquals(77.59460, result.longitude!!, 1e-4)
        assertEquals("Asha", result.semantic!!.rawText)

        val noGps = c.decompress(base + "Asha".toByteArray(), PacketType.SOS, Language.HINDI)
        assertNull(noGps.latitude)
        assertEquals("Asha", noGps.semantic!!.rawText)
    }

    // ------------------------------------------------------------ persistent storage codec

    @Test
    fun messagesSurviveSerialisationAndInFlightSendsBecomeFailed() {
        val m = ChatMessage(
            senderId = "N_1", senderName = "Ravi", receiverId = "BRDCAST_",
            text = "சாலை அடைக்கப்பட்டுள்ளது", language = Language.TAMIL,
            priority = Priority.IMPORTANT, status = DeliveryStatus.TRANSMITTING
        )
        val back = MessageCodec.fromJson(org.json.JSONObject(MessageCodec.toJson(m).toString()))!!
        assertEquals(m.id, back.id)
        assertEquals(m.text, back.text)
        assertEquals(Language.TAMIL, back.language)
        assertEquals(Priority.IMPORTANT, back.priority)
        assertEquals(DeliveryStatus.FAILED, back.status)
    }
}
