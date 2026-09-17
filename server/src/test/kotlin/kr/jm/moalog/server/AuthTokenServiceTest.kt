package kr.jm.moalog.server

import kr.jm.moalog.server.auth.application.AuthTokenService
import kr.jm.moalog.server.auth.config.AccessTokenProperties
import kr.jm.moalog.server.auth.config.AuthProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AuthTokenServiceTest {
    @Test
    fun `access token expiry is capped by session expiry`() {
        val now = Instant.parse("2026-09-14T00:00:00Z")
        val sessionExpiry = now.plusSeconds(60)
        val service = AuthTokenService(
            jwtEncoder = CapturingJwtEncoder(),
            properties = AuthProperties(
                accessToken = AccessTokenProperties(
                    issuer = "issuer",
                    audience = "audience",
                    secretBase64 = "unused-in-unit-test",
                    ttl = Duration.ofMinutes(15),
                ),
                refreshTokenTtl = Duration.ofDays(30),
                loginChallengeTtl = Duration.ofMinutes(5),
                maxActiveLoginChallenges = 10,
                maxActiveLoginChallengesPerDevice = 5,
                maxActiveLoginChallengesPerSource = 8,
                maxExchangeAttemptsPerChallenge = 5,
                googleAudience = "google",
                appleAudience = "apple",
            ),
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

        val issued = service.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), sessionExpiry)

        assertEquals(sessionExpiry.minusSeconds(1), issued.expiresAt)
    }

    private class CapturingJwtEncoder : JwtEncoder {
        override fun encode(parameters: JwtEncoderParameters): Jwt = Jwt(
            "encoded-access-token",
            parameters.claims.issuedAt,
            parameters.claims.expiresAt,
            parameters.jwsHeader!!.headers,
            parameters.claims.claims,
        )
    }
}
