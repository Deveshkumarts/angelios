package org.sih.itantra.storage

import org.json.JSONObject
import org.sih.itantra.domain.model.ChatMessage
import org.sih.itantra.domain.model.DeliveryStatus
import org.sih.itantra.domain.model.Language
import org.sih.itantra.domain.model.PacketType
import org.sih.itantra.domain.model.Priority
import org.sih.itantra.domain.model.TechnicalTelemetry
import org.sih.itantra.domain.model.TransportType

/** JSON (de)serialisation of [ChatMessage] for the encrypted on-device message store. */
object MessageCodec {

    fun toJson(m: ChatMessage): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("senderId", m.senderId)
        put("senderName", m.senderName)
        put("receiverId", m.receiverId)
        put("text", m.text)
        put("speech", m.reconstructedSpeechText)
        put("priority", m.priority.name)
        put("packetType", m.packetType.name)
        put("transport", m.transport.name)
        put("language", m.language.name)
        put("status", m.status.name)
        put("incoming", m.isIncoming)
        put("ts", m.timestamp)
        put("telemetry", JSONObject().apply {
            val t = m.telemetry
            put("stt", t.sttDurationMs); put("sem", t.semanticDurationMs); put("comp", t.compressionDurationMs)
            put("net", t.networkTransitDurationMs); put("reasm", t.reassemblyDurationMs); put("tts", t.ttsDurationMs)
            put("bytes", t.payloadBytes); put("raw", t.rawEquivalentBytes)
        })
    }

    fun fromJson(o: JSONObject): ChatMessage? = try {
        val t = o.optJSONObject("telemetry")
        ChatMessage(
            id = o.getString("id"),
            senderId = o.getString("senderId"),
            senderName = o.getString("senderName"),
            receiverId = o.getString("receiverId"),
            text = o.getString("text"),
            reconstructedSpeechText = o.optString("speech", o.getString("text")),
            priority = enumOr(o.optString("priority"), Priority.NORMAL),
            packetType = enumOr(o.optString("packetType"), PacketType.NORMAL_TEXT),
            transport = enumOr(o.optString("transport"), TransportType.WIFI_DIRECT),
            language = enumOr(o.optString("language"), Language.ENGLISH),
            status = enumOr(o.optString("status"), DeliveryStatus.RECEIVED).let {
                // A send that was still in flight when the app died never got its ACK
                if (it == DeliveryStatus.TRANSMITTING || it == DeliveryStatus.RETRYING) DeliveryStatus.FAILED else it
            },
            isIncoming = o.optBoolean("incoming", false),
            timestamp = o.optLong("ts", 0L),
            telemetry = if (t == null) TechnicalTelemetry() else TechnicalTelemetry(
                sttDurationMs = t.optLong("stt"), semanticDurationMs = t.optLong("sem"),
                compressionDurationMs = t.optLong("comp"), networkTransitDurationMs = t.optLong("net"),
                reassemblyDurationMs = t.optLong("reasm"), ttsDurationMs = t.optLong("tts"),
                payloadBytes = t.optInt("bytes"), rawEquivalentBytes = t.optInt("raw", 64000)
            )
        )
    } catch (_: Exception) {
        null
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
