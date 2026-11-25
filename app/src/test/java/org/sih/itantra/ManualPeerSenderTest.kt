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
 * Optional: ANGELIOS_PEER_SOS=1, ANGELIOS_PEER_NAME=Priya, ANGELIOS_PEER_PASSPHRASE=<team key>.
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

        val senderId = "N_9999"
        val hello = MeshCrypto.seal(Packet(
            senderId = senderId,
            receiverId = "BRDCAST_",
            packetType = PacketType.HELLO,
            ttl = 1,
            payload = "iTANTRA_PEER|$name|80".toByteArray(Charsets.UTF_8)
        ))

        val priority = if (sos) Priority.EMERGENCY else Priority.NORMAL
        val semantic = SemanticEncoder().encode(text, Language.ENGLISH, Language.ENGLISH)
        val compressed = AdaptiveCompressor().compress(text, semantic, LinkQuality.GOOD, priority)
        val message = MeshCrypto.seal(Packet(
            senderId = senderId,
            receiverId = "BRDCAST_",
            languageId = Language.ENGLISH.id,
            priority = priority,
            packetType = if (sos) PacketType.SOS else compressed.packetType,
            payload = compressed.payload
        ))

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
