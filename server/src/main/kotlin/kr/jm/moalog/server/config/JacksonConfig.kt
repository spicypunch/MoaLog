package kr.jm.moalog.server.config

import com.fasterxml.jackson.databind.EnumNamingStrategies
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JacksonConfig {
    @Bean
    fun kotlinxJsonObjectModule(): Module = SimpleModule("MoaLogKotlinxJson").apply {
        addSerializer(
            JsonObject::class.java,
            object : JsonSerializer<JsonObject>() {
                override fun serialize(
                    value: JsonObject,
                    generator: JsonGenerator,
                    serializers: SerializerProvider,
                ) {
                    generator.writeRawValue(value.toString())
                }
            },
        )
        addDeserializer(
            JsonObject::class.java,
            object : JsonDeserializer<JsonObject>() {
                override fun deserialize(
                    parser: JsonParser,
                    context: DeserializationContext,
                ): JsonObject {
                    val tree = parser.codec.readTree<JsonNode>(parser)
                    return Json.parseToJsonElement(tree.toString()).jsonObject
                }
            },
        )
    }

    @Bean
    fun enumWireValueCustomizer(): Jackson2ObjectMapperBuilderCustomizer =
        Jackson2ObjectMapperBuilderCustomizer { builder ->
            builder.postConfigurer { objectMapper ->
                objectMapper.setConfig(
                    objectMapper.serializationConfig.with(EnumNamingStrategies.SNAKE_CASE),
                )
                objectMapper.setConfig(
                    objectMapper.deserializationConfig.with(EnumNamingStrategies.SNAKE_CASE),
                )
            }
        }
}
