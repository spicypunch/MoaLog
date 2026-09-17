package kr.jm.moalog.server

import com.fasterxml.jackson.databind.ObjectMapper
import kr.jm.moalog.core.contracts.AuthExchangeRequest
import kr.jm.moalog.core.contracts.AuthChallengeResponse
import kr.jm.moalog.core.contracts.AuthProvider
import kr.jm.moalog.core.contracts.AuthRefreshRequest
import kr.jm.moalog.core.contracts.AuthTokenResponse
import kr.jm.moalog.core.contracts.ClientDeviceDto
import kr.jm.moalog.core.contracts.ClientPlatform
import kr.jm.moalog.core.contracts.UuidString
import kr.jm.moalog.server.auth.domain.ProviderIdTokenVerifier
import kr.jm.moalog.server.auth.domain.VerifiedProviderIdentity
import kr.jm.moalog.server.auth.domain.InvalidProviderTokenException
import kr.jm.moalog.server.auth.infrastructure.AuthLoginChallengeRepository
import kr.jm.moalog.server.auth.infrastructure.AuthRefreshTokenRepository
import kr.jm.moalog.server.auth.infrastructure.AuthSessionRepository
import kr.jm.moalog.server.auth.infrastructure.RefreshTokenStatus
import kr.jm.moalog.server.auth.web.AuthExchangeWebRequest
import kr.jm.moalog.server.auth.web.AuthRefreshWebRequest
import kr.jm.moalog.server.auth.web.ClientDeviceWebRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.security.oauth2.jwt.JwtDecoder
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthFlowIntegrationTest.StubVerifierConfig::class)
class AuthFlowIntegrationTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val objectMapper: ObjectMapper,
    @param:Autowired private val refreshTokens: AuthRefreshTokenRepository,
    @param:Autowired private val sessions: AuthSessionRepository,
    @param:Autowired private val loginChallenges: AuthLoginChallengeRepository,
    @param:Autowired private val jwtDecoder: JwtDecoder,
) {
    @BeforeEach
    fun setUp() {
        refreshTokens.deleteAll()
        sessions.deleteAll()
        loginChallenges.deleteAll()
    }

    @Test
    fun `exchange creates a session and me returns the authenticated user`() {
        val tokens = exchange("first-user", DEVICE_ONE)
        val persistedToken = refreshTokens.findAll().single()
        check(persistedToken.tokenHash.length == 64)
        check(persistedToken.tokenHash != tokens.refreshToken)
        check(!tokens.toString().contains(tokens.accessToken))
        check(!tokens.toString().contains(tokens.refreshToken))
        val jwt = jwtDecoder.decode(tokens.accessToken)
        check(jwt.issuer.toString() == "https://test.moalog.local")
        check(jwt.audience == listOf("moalog-test"))
        check(jwt.id.isNotBlank())
        check(jwt.getClaimAsString("sid") == tokens.sessionId.value)
        check(tokens.accessTokenExpiresAtEpochSeconds - jwt.issuedAt!!.epochSecond == 900L)

        mockMvc.get("/api/auth/me") {
            header("Authorization", "Bearer ${tokens.accessToken}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.displayName") { value("User first-user") }
            jsonPath("$.email") { value("first-user@example.com") }
        }
    }

    @Test
    fun `invalid provider credentials and invalid request bodies return stable problems`() {
        mockMvc.post("/api/auth/exchange") {
            contentType = MediaType.APPLICATION_JSON
            val challenge = issueChallenge(DEVICE_ONE)
            content = objectMapper.writeValueAsBytes(exchangeRequest("invalid", DEVICE_ONE, challenge))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.type") { value("urn:moalog:problem:invalid-provider-credential") }
        }

        mockMvc.post("/api/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":" "}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.type") { value("urn:moalog:problem:validation") }
        }
    }

    @Test
    fun `web authentication request strings redact credentials`() {
        val exchange = AuthExchangeWebRequest(
            challengeId = DEVICE_TWO.toString(),
            provider = AuthProvider.GOOGLE,
            idToken = "sensitive-provider-token",
            device = ClientDeviceWebRequest(DEVICE_ONE.toString(), ClientPlatform.ANDROID, "test"),
        )
        val refresh = AuthRefreshWebRequest("sensitive-refresh-token")

        check(!exchange.toString().contains("sensitive-provider-token"))
        check(!refresh.toString().contains("sensitive-refresh-token"))
    }

    @Test
    fun `login challenge is single use and a mismatched nonce is rejected`() {
        val challenge = issueChallenge(DEVICE_ONE)
        exchange("challenge-owner", DEVICE_ONE, challenge)

        mockMvc.post("/api/auth/exchange") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsBytes(exchangeRequest("challenge-owner", DEVICE_ONE, challenge))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.type") { value("urn:moalog:problem:invalid-provider-credential") }
        }

        val otherChallenge = issueChallenge(DEVICE_ONE)
        mockMvc.post("/api/auth/exchange") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsBytes(
                AuthExchangeRequest(
                    provider = AuthProvider.GOOGLE,
                    challengeId = otherChallenge.challengeId,
                    idToken = "challenge-owner|wrong-nonce",
                    device = ClientDeviceDto(UuidString(DEVICE_ONE.toString()), ClientPlatform.ANDROID, "test"),
                ),
            )
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.type") { value("urn:moalog:problem:invalid-provider-credential") }
        }
    }

    @Test
    fun `login challenge is bound to the requesting device`() {
        val challenge = issueChallenge(DEVICE_ONE)

        mockMvc.post("/api/auth/exchange") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsBytes(exchangeRequest("device-owner", DEVICE_TWO, challenge))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.type") { value("urn:moalog:problem:invalid-provider-credential") }
        }
    }

    @Test
    fun `active login challenge capacity is bounded in the database`() {
        repeat(3) { issueChallenge(DEVICE_ONE) }

        mockMvc.post("/api/auth/challenge") {
            header("X-MoaLog-Device-Id", DEVICE_ONE.toString())
        }.andExpect {
            status { isTooManyRequests() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("urn:moalog:problem:too-many-login-challenges") }
        }
        check(loginChallenges.count() == 3L)
    }

    @Test
    fun `rotating device ids cannot bypass the source challenge limit`() {
        repeat(3) { index ->
            issueChallenge(UUID.fromString("123e4567-e89b-12d3-a456-4266141741${index}0"))
        }

        mockMvc.post("/api/auth/challenge") {
            header("X-MoaLog-Device-Id", "123e4567-e89b-12d3-a456-426614174199")
        }.andExpect {
            status { isTooManyRequests() }
            jsonPath("$.type") { value("urn:moalog:problem:too-many-login-challenges") }
        }
    }

    @Test
    fun `login challenge limits invalid exchange attempts`() {
        val challenge = issueChallenge(DEVICE_ONE)
        repeat(3) {
            mockMvc.post("/api/auth/exchange") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsBytes(exchangeRequest("invalid", DEVICE_ONE, challenge))
            }.andExpect { status { isUnauthorized() } }
        }

        mockMvc.post("/api/auth/exchange") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsBytes(exchangeRequest("attempt-owner", DEVICE_ONE, challenge))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.type") { value("urn:moalog:problem:invalid-provider-credential") }
        }
    }

    @Test
    fun `refresh replay inside grace replaces the active successor without revoking session`() {
        val original = exchange("rotation-user", DEVICE_ONE)
        val rotated = refresh(original.refreshToken)
        check(original.refreshToken != rotated.refreshToken)

        val recovered = refresh(original.refreshToken)
        check(recovered.refreshToken != rotated.refreshToken)
        val sessionId = UUID.fromString(original.sessionId.value)
        check(refreshTokens.findAllBySessionIdAndStatus(sessionId, RefreshTokenStatus.ACTIVE).size == 1)
        check(sessions.findById(sessionId).orElseThrow().revokedAt == null)

        mockMvc.get("/api/auth/me") {
            header("Authorization", "Bearer ${recovered.accessToken}")
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `refresh replay outside grace revokes the whole session`() {
        val original = exchange("late-replay-user", DEVICE_ONE)
        val rotated = refresh(original.refreshToken)
        val sessionId = UUID.fromString(original.sessionId.value)
        refreshTokens.findAll().single { it.status == RefreshTokenStatus.USED }.also {
            it.usedAt = Instant.now().minusSeconds(31)
            refreshTokens.saveAndFlush(it)
        }

        mockMvc.post("/api/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsBytes(AuthRefreshRequest(original.refreshToken))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.type") { value("urn:moalog:problem:invalid-refresh-token") }
        }

        check(sessions.findById(sessionId).orElseThrow().revokedAt != null)
        mockMvc.get("/api/auth/me") {
            header("Authorization", "Bearer ${rotated.accessToken}")
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `refresh near session expiry revokes the session and requires login again`() {
        val tokens = exchange("expiring-user", DEVICE_ONE)
        val sessionId = UUID.fromString(tokens.sessionId.value)
        sessions.findById(sessionId).orElseThrow().also {
            it.expiresAt = Instant.now().plusSeconds(60)
            sessions.saveAndFlush(it)
        }

        mockMvc.post("/api/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsBytes(AuthRefreshRequest("$sessionId.forged-secret"))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.type") { value("urn:moalog:problem:invalid-refresh-token") }
        }
        check(sessions.findById(sessionId).orElseThrow().revokedAt == null)

        mockMvc.post("/api/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsBytes(AuthRefreshRequest(tokens.refreshToken))
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.type") { value("urn:moalog:problem:invalid-refresh-token") }
        }

        check(sessions.findById(sessionId).orElseThrow().revokedAt != null)
    }

    @Test
    fun `a user cannot revoke another users session`() {
        val owner = exchange("owner", DEVICE_ONE)
        val attacker = exchange("attacker", DEVICE_TWO)

        mockMvc.delete("/api/auth/sessions/${owner.sessionId.value}") {
            header("Authorization", "Bearer ${attacker.accessToken}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.type") { value("urn:moalog:problem:forbidden") }
        }

        mockMvc.get("/api/auth/me") {
            header("Authorization", "Bearer ${owner.accessToken}")
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `session owner can revoke it and the access token stops working`() {
        val owner = exchange("owner", DEVICE_ONE)

        mockMvc.delete("/api/auth/sessions/${owner.sessionId.value}") {
            header("Authorization", "Bearer ${owner.accessToken}")
        }.andExpect { status { isNoContent() } }

        mockMvc.get("/api/auth/me") {
            header("Authorization", "Bearer ${owner.accessToken}")
        }.andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }

    @Test
    fun `protected endpoints preserve problem detail security errors`() {
        mockMvc.get("/api/auth/me").andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("urn:moalog:problem:unauthorized") }
        }

        mockMvc.get("/api/auth/me") {
            header("Authorization", "Bearer not-a-jwt")
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.type") { value("urn:moalog:problem:unauthorized") }
        }
    }

    private fun exchange(
        providerToken: String,
        deviceId: UUID,
        challenge: AuthChallengeResponse = issueChallenge(deviceId),
    ): AuthTokenResponse {
        val result = mockMvc.post("/api/auth/exchange") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsBytes(exchangeRequest(providerToken, deviceId, challenge))
        }.andExpect { status { isOk() } }
            .andReturn()
        return objectMapper.readValue(result.response.contentAsByteArray, AuthTokenResponse::class.java)
    }

    private fun exchangeRequest(
        providerToken: String,
        deviceId: UUID,
        challenge: AuthChallengeResponse,
    ) = AuthExchangeRequest(
        provider = AuthProvider.GOOGLE,
        challengeId = challenge.challengeId,
        idToken = "$providerToken|${challenge.nonce}",
        device = ClientDeviceDto(
            deviceId = UuidString(deviceId.toString()),
            platform = ClientPlatform.ANDROID,
            appVersion = "1.0.0-test",
        ),
    )

    private fun issueChallenge(deviceId: UUID): AuthChallengeResponse {
        val result = mockMvc.post("/api/auth/challenge") {
            header("X-MoaLog-Device-Id", deviceId.toString())
        }
            .andExpect { status { isOk() } }
            .andReturn()
        return objectMapper.readValue(result.response.contentAsByteArray, AuthChallengeResponse::class.java)
    }

    private fun refresh(refreshToken: String): AuthTokenResponse {
        val result = mockMvc.post("/api/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsBytes(AuthRefreshRequest(refreshToken))
        }.andExpect { status { isOk() } }
            .andReturn()
        return objectMapper.readValue(result.response.contentAsByteArray, AuthTokenResponse::class.java)
    }

    companion object {
        private val DEVICE_ONE: UUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174010")
        private val DEVICE_TWO: UUID = UUID.fromString("123e4567-e89b-12d3-a456-426614174011")
    }

    @TestConfiguration(proxyBeanMethods = false)
    class StubVerifierConfig {
        @Bean
        @Primary
        fun stubProviderVerifier(): ProviderIdTokenVerifier = ProviderIdTokenVerifier { provider, token ->
            val parts = token.split('|', limit = 2)
            if (parts.size != 2 || parts[0] == "invalid") throw InvalidProviderTokenException()
            VerifiedProviderIdentity(
                provider = provider,
                subject = "subject-${parts[0]}",
                email = "${parts[0]}@example.com",
                displayName = "User ${parts[0]}",
                nonce = parts[1],
            )
        }
    }
}
