package kr.jm.moalog.core.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json

class AuthAndProblemContractsTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun auth_exchange_round_trips_with_stable_wire_enums() {
        val request = AuthExchangeRequest(
            provider = AuthProvider.GOOGLE,
            challengeId = UuidString("223e4567-e89b-42d3-a456-426614174000"),
            idToken = "provider-token",
            device = ClientDeviceDto(
                deviceId = UuidString("123e4567-e89b-12d3-a456-426614174000"),
                platform = ClientPlatform.ANDROID,
                appVersion = "1.0.0",
            ),
        )

        val encoded = json.encodeToString(request)

        assertEquals(request, json.decodeFromString<AuthExchangeRequest>(encoded))
        assertEquals(true, encoded.contains("\"provider\":\"google\""))
        assertEquals(true, encoded.contains("\"platform\":\"android\""))
    }

    @Test
    fun blank_auth_secrets_are_rejected() {
        assertFailsWith<IllegalArgumentException> { AuthRefreshRequest(" ") }
    }

    @Test
    fun auth_contracts_redact_secrets_from_debug_strings() {
        val exchange = AuthExchangeRequest(
            provider = AuthProvider.APPLE,
            challengeId = UuidString("223e4567-e89b-42d3-a456-426614174000"),
            idToken = "sensitive-id-token",
            device = ClientDeviceDto(
                UuidString("123e4567-e89b-12d3-a456-426614174000"),
                ClientPlatform.IOS,
                "1.0.0",
            ),
        )
        val refresh = AuthRefreshRequest("sensitive-refresh-token")
        val challenge = AuthChallengeResponse(
            challengeId = UuidString("223e4567-e89b-42d3-a456-426614174000"),
            nonce = "sensitive-login-nonce",
            expiresAtEpochSeconds = 100,
        )

        assertFalse(exchange.toString().contains("sensitive-id-token"))
        assertFalse(refresh.toString().contains("sensitive-refresh-token"))
        assertFalse(challenge.toString().contains("sensitive-login-nonce"))
    }

    @Test
    fun problem_details_preserve_validation_fields() {
        val problem = ApiProblemDto(
            type = "urn:moalog:problem:validation",
            title = "요청 값이 올바르지 않습니다",
            status = 400,
            code = "VALIDATION_FAILED",
            traceId = "trace-1",
            errors = listOf(ApiFieldErrorDto("amountWon", "금액을 확인해 주세요")),
        )

        assertEquals(problem, json.decodeFromString<ApiProblemDto>(json.encodeToString(problem)))
    }
}
