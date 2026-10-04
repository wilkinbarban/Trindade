package com.trindade.app.network

import com.trindade.app.contract.models.UpdateAdminTaskRequest
import java.math.BigDecimal
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/**
 * Lossless JSON numeric [BigDecimal] contextual serializer.
 *
 * Encodes [BigDecimal] as unquoted numeric JSON literals and decodes JSON numeric/string
 * primitives into [BigDecimal] without precision loss.
 */
@OptIn(ExperimentalSerializationApi::class)
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(JsonUnquotedLiteral(value.toPlainString()))
        } else {
            encoder.encodeString(value.toPlainString())
        }
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        return if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonPrimitive && element !is JsonNull) {
                BigDecimal(element.content)
            } else {
                throw SerializationException("Expected JsonPrimitive for BigDecimal, got $element")
            }
        } else {
            runCatching { BigDecimal(decoder.decodeString()) }
                .getOrElse { BigDecimal(decoder.decodeDouble().toString()) }
        }
    }
}

/**
 * Root [UpdateAdminTaskRequest] transforming serializer.
 *
 * The generated OpenAPI model specifies [UpdateAdminTaskRequest.IsActive] as an enum with values `"0"`
 * and `"1"`, which encodes as JSON strings by default. The backend expects numeric literals `0` or `1`.
 *
 * This serializer transforms `is_active` to a numeric [JsonPrimitive] on serialization and converts
 * incoming numeric tokens to string enum values on deserialization so the generated model decodes correctly.
 */
object UpdateAdminTaskRequestSerializer : KSerializer<UpdateAdminTaskRequest> {
    private val delegate: KSerializer<UpdateAdminTaskRequest> = UpdateAdminTaskRequest.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: UpdateAdminTaskRequest) {
        if (encoder is JsonEncoder) {
            val element = encoder.json.encodeToJsonElement(delegate, value)
            if (element is JsonObject && "is_active" in element) {
                val activeElement = element["is_active"]
                if (activeElement is JsonPrimitive && activeElement !is JsonNull) {
                    val transformed = when (activeElement.content) {
                        "1" -> JsonPrimitive(1)
                        "0" -> JsonPrimitive(0)
                        else -> activeElement
                    }
                    val map = element.toMutableMap()
                    map["is_active"] = transformed
                    encoder.encodeJsonElement(JsonObject(map))
                    return
                }
            }
            encoder.encodeJsonElement(element)
        } else {
            delegate.serialize(encoder, value)
        }
    }

    override fun deserialize(decoder: Decoder): UpdateAdminTaskRequest {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonObject && "is_active" in element) {
                val activeElement = element["is_active"]
                if (activeElement is JsonPrimitive && activeElement !is JsonNull) {
                    val transformed = when (activeElement.content) {
                        "1", "true" -> JsonPrimitive("1")
                        "0", "false" -> JsonPrimitive("0")
                        else -> activeElement
                    }
                    val map = element.toMutableMap()
                    map["is_active"] = transformed
                    return decoder.json.decodeFromJsonElement(delegate, JsonObject(map))
                }
            }
            return decoder.json.decodeFromJsonElement(delegate, element)
        } else {
            return delegate.deserialize(decoder)
        }
    }
}
