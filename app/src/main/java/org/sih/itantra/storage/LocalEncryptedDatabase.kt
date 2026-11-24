package org.sih.itantra.storage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.sih.itantra.domain.model.ChatMessage
import org.sih.itantra.domain.model.DeliveryStatus
import org.sih.itantra.domain.model.Language
import org.sih.itantra.domain.model.NetworkNode
import org.sih.itantra.domain.model.PacketType
import org.sih.itantra.domain.model.Priority
import org.sih.itantra.domain.model.TechnicalTelemetry
import org.sih.itantra.domain.model.TransportType
import java.util.concurrent.CopyOnWriteArrayList
import org.sih.itantra.security.KeystoreVault
import org.sih.itantra.security.MeshCrypto

data class UserProfile(
    val username: String = "Operator",
    val gender: String = "Male",
    val preferredLanguage: Language = Language.ENGLISH,
    val callsign: String = "AKHET-1",
    val role: String = "Tactical Unit",
    val isInitialized: Boolean = false
)

/**
 * Encrypted Local Storage Repository.
 * Guarantees zero reliance on cloud/Firebase databases.
 */
class LocalEncryptedDatabase(private val context: Context) {

    companion object {
        const val MAX_STORED_MESSAGES = 1000
    }

    private val inMemoryMessages = CopyOnWriteArrayList<ChatMessage>()
    private val _messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messagesFlow: StateFlow<List<ChatMessage>> = _messagesFlow.asStateFlow()

    private val inMemoryNodes = CopyOnWriteArrayList<NetworkNode>()
    private val _nodesFlow = MutableStateFlow<List<NetworkNode>>(emptyList())
    val nodesFlow: StateFlow<List<NetworkNode>> = _nodesFlow.asStateFlow()

    private val _userProfileFlow = MutableStateFlow(getUserProfile())
    val userProfileFlow: StateFlow<UserProfile> = _userProfileFlow.asStateFlow()

    fun getUserProfile(): UserProfile {
        val prefs = context.getSharedPreferences("itantra_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("user_name", null)
        val gender = prefs.getString("user_gender", "Male") ?: "Male"
        val langCode = prefs.getString("user_pref_lang", "en") ?: "en"
        val callsign = prefs.getString("user_callsign", "AKHET-1") ?: "AKHET-1"
        val role = prefs.getString("user_role", "Tactical Unit") ?: "Tactical Unit"
        val initialized = prefs.getBoolean("profile_initialized", false)
        val lang = Language.fromCode(langCode)
        return if (name != null) {
            UserProfile(
                username = name,
                gender = gender,
                preferredLanguage = lang,
                callsign = callsign,
                role = role,
                isInitialized = initialized
            )
        } else {
            UserProfile(
                username = "Operator_${getOrCreateNodeId()}",
                gender = "Male",
                preferredLanguage = Language.ENGLISH,
                callsign = "AKHET-1",
                role = "Tactical Unit",
                isInitialized = false
            )
        }
    }

    fun saveUserProfile(profile: UserProfile) {
        val prefs = context.getSharedPreferences("itantra_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("user_name", profile.username)
            .putString("user_gender", profile.gender)
            .putString("user_pref_lang", profile.preferredLanguage.code)
            .putString("user_callsign", profile.callsign)
            .putString("user_role", profile.role)
            .putBoolean("profile_initialized", true)
            .apply()
        _userProfileFlow.value = profile.copy(isInitialized = true)
    }

    fun getOrCreateNodeId(): String {
        val prefs = context.getSharedPreferences("itantra_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("node_id", null)
        if (id == null || id.length > 8) {
            val randomSuffix = (1000..9999).random()
            id = "N_$randomSuffix" // exactly 6 chars, fits in 8-byte packet slot without truncation
            prefs.edit().putString("node_id", id).apply()
        }
        return id
    }

    // ------------------------------------------------------------------ persistence
    // Message history is kept in an AES-256-GCM file whose key lives in the Android Keystore
    // (KeystoreVault); it survives restarts and is unreadable if copied off the device.

    private val messagesFile = java.io.File(context.filesDir, "messages.enc")
    private val sosLogFile = java.io.File(context.filesDir, "sos_log.enc")
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    init {
        loadMessages()
    }

    private fun loadMessages() {
        try {
            if (!messagesFile.exists()) return
            val json = KeystoreVault.decryptString(messagesFile.readBytes()) ?: return
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                MessageCodec.fromJson(arr.getJSONObject(i))?.let { inMemoryMessages.add(it) }
            }
            _messagesFlow.value = inMemoryMessages.toList()
        } catch (t: Throwable) {
            android.util.Log.w("LocalEncryptedDatabase", "Could not load message history: ${t.message}")
        }
    }

    private fun persistMessages() {
        val snapshot = inMemoryMessages.take(MAX_STORED_MESSAGES)
        ioExecutor.execute {
            try {
                val arr = org.json.JSONArray()
                snapshot.forEach { arr.put(MessageCodec.toJson(it)) }
                writeAtomically(messagesFile, KeystoreVault.encryptString(arr.toString()))
            } catch (t: Throwable) {
                android.util.Log.w("LocalEncryptedDatabase", "Could not persist messages: ${t.message}")
            }
        }
    }

    private fun writeAtomically(target: java.io.File, bytes: ByteArray) {
        val tmp = java.io.File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    fun clearMessages() {
        inMemoryMessages.clear()
        _messagesFlow.value = emptyList()
        persistMessages()
    }

    // ------------------------------------------------------------------ SOS audit log

    fun loadSosLogs(): List<org.sih.itantra.emergency.SosLogEvent> = try {
        if (!sosLogFile.exists()) emptyList() else {
            val arr = org.json.JSONArray(KeystoreVault.decryptString(sosLogFile.readBytes()) ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                org.sih.itantra.emergency.SosLogEvent(
                    id = o.getString("id"),
                    timestamp = o.getLong("ts"),
                    triggerSource = o.getString("src"),
                    deliveredCount = o.optInt("delivered", 0),
                    nearbyDevices = o.optInt("nearby", 0),
                    latitude = if (o.has("lat")) o.getDouble("lat") else null,
                    longitude = if (o.has("lon")) o.getDouble("lon") else null
                )
            }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    fun saveSosLogs(logs: List<org.sih.itantra.emergency.SosLogEvent>) {
        val snapshot = logs.take(200)
        ioExecutor.execute {
            try {
                val arr = org.json.JSONArray()
                snapshot.forEach { l ->
                    arr.put(org.json.JSONObject().apply {
                        put("id", l.id); put("ts", l.timestamp); put("src", l.triggerSource)
                        put("delivered", l.deliveredCount); put("nearby", l.nearbyDevices)
                        l.latitude?.let { put("lat", it) }; l.longitude?.let { put("lon", it) }
                    })
                }
                writeAtomically(sosLogFile, KeystoreVault.encryptString(arr.toString()))
            } catch (t: Throwable) {
                android.util.Log.w("LocalEncryptedDatabase", "Could not persist SOS log: ${t.message}")
            }
        }
    }

    // ------------------------------------------------------------------ mesh passphrase

    /** Team mesh passphrase, stored encrypted by the Keystore. Falls back to the open default. */
    fun getMeshPassphrase(): String {
        val prefs = context.getSharedPreferences("itantra_prefs", Context.MODE_PRIVATE)
        val enc = prefs.getString("mesh_passphrase_enc", null) ?: return MeshCrypto.DEFAULT_PASSPHRASE
        return try {
            KeystoreVault.decryptString(android.util.Base64.decode(enc, android.util.Base64.NO_WRAP))
                ?: MeshCrypto.DEFAULT_PASSPHRASE
        } catch (_: Throwable) {
            MeshCrypto.DEFAULT_PASSPHRASE
        }
    }

    fun setMeshPassphrase(passphrase: String) {
        val enc = android.util.Base64.encodeToString(KeystoreVault.encryptString(passphrase), android.util.Base64.NO_WRAP)
        context.getSharedPreferences("itantra_prefs", Context.MODE_PRIVATE).edit()
            .putString("mesh_passphrase_enc", enc).apply()
    }

    @Synchronized
    fun saveMessage(message: ChatMessage) {
        // An incoming retransmission of a message we already stored must not appear twice
        if (inMemoryMessages.any { it.id == message.id }) return
        inMemoryMessages.add(0, message) // Most recent first
        while (inMemoryMessages.size > MAX_STORED_MESSAGES) inMemoryMessages.removeAt(inMemoryMessages.size - 1)
        _messagesFlow.value = inMemoryMessages.toList()
        persistMessages()
    }

    @Synchronized
    fun updateMessageStatus(id: String, status: DeliveryStatus) {
        val index = inMemoryMessages.indexOfFirst { it.id == id }
        if (index != -1) {
            val updated = inMemoryMessages[index].copy(status = status)
            inMemoryMessages[index] = updated
            _messagesFlow.value = inMemoryMessages.toList()
            persistMessages()
        }
    }

    @Synchronized
    fun addOrUpdateNode(node: NetworkNode) {
        val myNodeId = getOrCreateNodeId().trim()
        val incomingId = node.id.trim()
        if (incomingId.equals(myNodeId, ignoreCase = true)) {
            return
        }
        val updatedNode = node.copy(lastSeenTimestamp = System.currentTimeMillis())
        val index = inMemoryNodes.indexOfFirst { it.id == updatedNode.id }
        if (index != -1) {
            inMemoryNodes[index] = updatedNode
        } else {
            inMemoryNodes.add(updatedNode)
        }
        _nodesFlow.value = inMemoryNodes.toList()
    }

    @Synchronized
    fun pruneStaleNodes(maxAgeMs: Long = 12_000L) {
        val now = System.currentTimeMillis()
        val changed = inMemoryNodes.removeAll { (now - it.lastSeenTimestamp) > maxAgeMs }
        if (changed) {
            _nodesFlow.value = inMemoryNodes.toList()
        }
    }

    @Synchronized
    fun clearNodes() {
        inMemoryNodes.clear()
        _nodesFlow.value = emptyList()
    }

    fun removeNode(nodeId: String) {
        inMemoryNodes.removeAll { it.id == nodeId }
        _nodesFlow.value = inMemoryNodes.toList()
    }
}
