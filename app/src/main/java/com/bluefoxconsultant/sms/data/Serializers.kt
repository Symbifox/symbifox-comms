@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.bluefoxconsultant.sms.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The server sometimes emits `false` (boolean) where a numeric value is expected
 * (e.g. partner_id, last_message_date). These serializers coerce such values to null.
 */
object FalseAsNullLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FalseAsNullLong", PrimitiveKind.LONG).nullable

    override fun deserialize(decoder: Decoder): Long? {
        val jd = decoder as? JsonDecoder ?: return null
        return (jd.decodeJsonElement() as? JsonPrimitive)?.longOrNull
    }

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }
}

/**
 * Same idea one level up: `record` is an object when the email was routed and
 * the boolean `false` when it wasn't. Decoding `false` into a data class throws,
 * so unwrap it here rather than at every call site.
 */
object FalseAsNullRecordSerializer : KSerializer<RecordRef?> {
    override val descriptor: SerialDescriptor = RecordRef.serializer().descriptor.nullable

    override fun deserialize(decoder: Decoder): RecordRef? {
        val jd = decoder as? JsonDecoder ?: return null
        val element = jd.decodeJsonElement()
        if (element is JsonPrimitive) return null // false / null / anything scalar
        return runCatching {
            jd.json.decodeFromJsonElement(RecordRef.serializer(), element)
        }.getOrNull()
    }

    override fun serialize(encoder: Encoder, value: RecordRef?) {
        if (value == null) encoder.encodeNull()
        else encoder.encodeSerializableValue(RecordRef.serializer(), value)
    }
}

object FalseAsNullIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FalseAsNullInt", PrimitiveKind.INT).nullable

    override fun deserialize(decoder: Decoder): Int? {
        val jd = decoder as? JsonDecoder ?: return null
        return (jd.decodeJsonElement() as? JsonPrimitive)?.intOrNull
    }

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }
}
