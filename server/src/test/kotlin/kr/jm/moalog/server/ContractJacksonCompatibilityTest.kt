package kr.jm.moalog.server

import com.fasterxml.jackson.databind.ObjectMapper
import kr.jm.moalog.core.contracts.AuthExchangeRequest
import kr.jm.moalog.core.contracts.AuthProvider
import kr.jm.moalog.core.contracts.ClientDeviceDto
import kr.jm.moalog.core.contracts.ClientPlatform
import kr.jm.moalog.core.contracts.SyncEntityType
import kr.jm.moalog.core.contracts.SyncMutationDto
import kr.jm.moalog.core.contracts.SyncOperation
import kr.jm.moalog.core.contracts.UtcInstantString
import kr.jm.moalog.core.contracts.UuidString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class ContractJacksonCompatibilityTest(
    @param:Autowired private val objectMapper: ObjectMapper,
) {
    private val deviceId = UuidString("123e4567-e89b-12d3-a456-426614174000")

    @Test
    fun `Jackson uses the same authentication wire enum values as Kotlin serialization`() {
        val request = AuthExchangeRequest(
            provider = AuthProvider.GOOGLE,
            challengeId = deviceId,
            idToken = "provider-token",
            device = ClientDeviceDto(deviceId, ClientPlatform.ANDROID, "1.0.0"),
        )

        val json = objectMapper.writeValueAsString(request)
        val decoded = objectMapper.readValue(json, AuthExchangeRequest::class.java)
        val mobileJson = Json.encodeToString(request)

        assertEquals(request, decoded)
        assertEquals(objectMapper.readTree(mobileJson), objectMapper.readTree(json))
        assertEquals("google", objectMapper.readTree(json)["provider"].asText())
        assertEquals("android", objectMapper.readTree(json)["device"]["platform"].asText())
    }

    @Test
    fun `Jackson round trips synchronization enum and value contracts`() {
        val mutation = SyncMutationDto(
            mutationId = deviceId,
            entityType = SyncEntityType.LEDGER_MEMBER,
            entityId = UuidString("123e4567-e89b-12d3-a456-426614174001"),
            operation = SyncOperation.UPSERT,
            baseVersion = 2,
            payload = buildJsonObject { put("changedAt", UtcInstantString("2026-09-14T12:34:56Z").value) },
        )

        val json = objectMapper.writeValueAsString(mutation)
        val decoded = objectMapper.readValue(json, SyncMutationDto::class.java)
        val mobileJson = Json.encodeToString(mutation)

        assertEquals(mutation, decoded)
        assertEquals(objectMapper.readTree(mobileJson), objectMapper.readTree(json))
        assertEquals("ledger_member", objectMapper.readTree(json)["entityType"].asText())
        assertEquals("upsert", objectMapper.readTree(json)["operation"].asText())
    }
}
