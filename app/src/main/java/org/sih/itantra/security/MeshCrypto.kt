package org.sih.itantra.security

import org.sih.itantra.domain.model.Packet
import org.sih.itantra.domain.model.PacketType
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Over-the-air packet encryption (AES-256-GCM).
 *
 * Every device in a team shares one *mesh passphrase*; the 256-bit mesh key is derived from it with
 * PBKDF2-HMAC-SHA256. The passphrase itself is stored on-device encrypted by [KeystoreVault]
 * (hardware-backed Android Keystore), never in source code.
 *
 * Wire format of an encrypted packet: header `VERSION = 2`, payload = `[12B IV][ciphertext][16B tag]`.
 * The immutable header fields (message id, sender, receiver, language, priority, type) are bound as
 * GCM associated data, so a relay can decrement TTL but cannot alter who sent a message or to whom.
 *
 * SOS beacons are deliberately left in plaintext (VERSION = 1): a person in distress must be heard
 * by *any* iTANTRA device in range, including rescuers outside the team.
 */
object MeshCrypto {
    const val VERSION_PLAINTEXT: Byte = 1
    const val VERSION_ENCRYPTED: Byte = 2

    /** Out-of-box passphrase so two fresh phones can talk at all. The UI warns while it is in use. */
    const val DEFAULT_PASSPHRASE = "itantra-open-mesh"

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 20_000
    private val SALT = "iTANTRA-mesh-key-v1".toByteArray(Charsets.US_ASCII)

    private val random = SecureRandom()

    @Volatile
    private var meshKey: SecretKey = deriveKey(DEFAULT_PASSPHRASE)

    @Volatile
    var isUsingDefaultPassphrase: Boolean = true
        private set

    fun setPassphrase(passphrase: String) {
        val p = passphrase.ifBlank { DEFAULT_PASSPHRASE }
        meshKey = deriveKey(p)
        isUsingDefaultPassphrase = p == DEFAULT_PASSPHRASE
    }

    fun deriveKey(passphrase: String): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), SALT, PBKDF2_ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    /** Packet types that are allowed to travel unencrypted. */
    fun isPlaintextAllowed(type: PacketType): Boolean = type == PacketType.SOS

    /** Encrypts the payload of [packet] unless it is already sealed or is a plaintext-allowed type. */
    fun seal(packet: Packet): Packet {
        if (packet.version == VERSION_ENCRYPTED || isPlaintextAllowed(packet.packetType)) return packet
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, meshKey, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(associatedData(packet))
        val ct = cipher.doFinal(packet.payload)
        return packet.copy(version = VERSION_ENCRYPTED, payload = iv + ct)
    }

    /**
     * Returns the packet with its plaintext payload, or null if it fails authentication (wrong team
     * key / tampered) or is an unencrypted packet of a type that must be encrypted.
     */
    fun open(packet: Packet): Packet? {
        if (packet.version != VERSION_ENCRYPTED) {
            return if (isPlaintextAllowed(packet.packetType)) packet else null
        }
        if (packet.payload.size < IV_BYTES + TAG_BITS / 8) return null
        return try {
            val iv = packet.payload.copyOfRange(0, IV_BYTES)
            val ct = packet.payload.copyOfRange(IV_BYTES, packet.payload.size)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, meshKey, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(associatedData(packet))
            packet.copy(version = VERSION_PLAINTEXT, payload = cipher.doFinal(ct))
        } catch (_: Exception) {
            null
        }
    }

    private fun associatedData(p: Packet): ByteArray {
        fun id8(s: String) = s.trim().padEnd(8, ' ').take(8).toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(8 + 8 + 8 + 3)
            .putLong(p.messageId)
            .put(id8(p.senderId))
            .put(id8(p.receiverId))
            .put(p.languageId)
            .put(p.priority.code)
            .put(p.packetType.code)
            .array()
    }
}
