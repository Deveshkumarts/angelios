package org.sih.itantra

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.sih.itantra.domain.model.Language
import org.sih.itantra.domain.model.LinkQuality
import org.sih.itantra.domain.model.Packet
import org.sih.itantra.domain.model.PacketType
import org.sih.itantra.domain.model.Priority
import org.sih.itantra.ml.semantic.SemanticEncoder
import org.sih.itantra.network.compression.AdaptiveCompressor
import org.sih.itantra.security.MeshCrypto
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Manual tool, not a regular test: acts as a second phone and sends a real, encrypted mesh message
 * (HELLO beacon + chat/SOS packet built with the app's own encoder, compressor and MeshCrypto)
 * over UDP to a device or emulator, to exercise the receive side end to end.
 *
 * Skipped unless ANGELIOS_PEER_TARGET is set, e.g. for an emulator:
 *   adb emu redir add udp:8988:8988
 *   ANGELIOS_PEER_TARGET=127.0.0.1:8988 ANGELIOS_PEER_TEXT="We are safe" \
 *     gradlew :app:testDebugUnitTest --tests "*ManualPeerSender*" --rerun
 * Optional: ANGELIOS_PEER_SOS=1, ANGELIOS_PEER_NAME=Priya, ANGELIOS_PEER_PASSPHRASE=<team key>,
 * ANGELIOS_PEER_LAT / ANGELIOS_PEER_LON (an SOS then carries GPS and the beacon a live position).
 */
class ManualPeerSenderTest {

    @Test
    fun sendMessageToDevice() {
        val target = System.getenv("ANGELIOS_PEER_TARGET")
        assumeTrue("ANGELIOS_PEER_TARGET not set; manual tool skipped", !target.isNullOrBlank())
        val (host, port) = target!!.split(":").let { it[0] to it[1].toInt() }
        val text = System.getenv("ANGELIOS_PEER_TEXT") ?: "We are safe near the bridge"
        val name = System.getenv("ANGELIOS_PEER_NAME") ?: "Priya"
        val sos = System.getenv("ANGELIOS_PEER_SOS") == "1"
        MeshCrypto.setPassphrase(System.getenv("ANGELIOS_PEER_PASSPHRASE") ?: MeshCrypto.DEFAULT_PASSPHRASE)
        val lat = System.getenv("ANGELIOS_PEER_LAT")?.toDoubleOrNull()
        val lon = System.getenv("ANGELIOS_PEER_LON")?.toDoubleOrNull()
        val position = if (sos && lat != null && lon != null) String.format(java.util.Locale.ROOT, "|%.6f,%.6f", lat, lon) else ""

        val senderId = "N_9999"
        val hello = MeshCrypto.seal(Packet(
            senderId = senderId,
            receiverId = "BRDCAST_",
            packetType = PacketType.HELLO,
            ttl = 1,
            payload = "iTANTRA_PEER|$name|80$position".toByteArray(Charsets.UTF_8)
        ))

        val priority = if (sos) Priority.EMERGENCY else Priority.NORMAL
        val semantic = SemanticEncoder().encode(text, Language.ENGLISH, Language.ENGLISH)
        val compressor = AdaptiveCompressor()
        val compressed = if (sos) compressor.compress(text, semantic, LinkQuality.EMERGENCY, priority)
                         else compressor.compress(text, semantic, LinkQuality.GOOD, priority)
        // A real SOS is [beacon][optional GPS block][sender name], sent unencrypted so any rescuer hears it
        val sosPayload = if (sos) compressed.payload +
            (if (lat != null && lon != null) compressor.encodeSosLocation(lat, lon) else ByteArray(0)) +
            name.toByteArray(Charsets.UTF_8) else null
        val raw = Packet(
            senderId = senderId,
            receiverId = "BRDCAST_",
            languageId = Language.ENGLISH.id,
            priority = priority,
            packetType = if (sos) PacketType.SOS else compressed.packetType,
            payload = sosPayload ?: compressed.payload
        )
        val message = if (sos) raw else MeshCrypto.seal(raw)

        DatagramSocket().use { socket ->
            val addr = InetSocketAddress(host, port)
            listOf(hello, message).forEach { p ->
                val bytes = p.toByteArray()
                socket.send(DatagramPacket(bytes, bytes.size, addr))
                Thread.sleep(400)
            }
        }
        println("Sent HELLO + ${message.packetType} (${message.payloadLength} B) from '$name' to $target")
    }
}
