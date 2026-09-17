package kr.jm.moalog.server.auth.application

import kr.jm.moalog.server.auth.config.AuthProperties
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class IssuedAccessToken(val value: String, val expiresAt: Instant) {
    override fun toString(): String = "IssuedAccessToken(value=<redacted>, expiresAt=$expiresAt)"
}

data class IssuedRefreshToken(val value: String, val hash: String) {
    override fun toString(): String = "IssuedRefreshToken(value=<redacted>, hash=<redacted>)"
}

@Service
class AuthTokenService(
    private val jwtEncoder: JwtEncoder,
    private val properties: AuthProperties,
    private val clock: Clock,
) {
    private val secureRandom = SecureRandom()

    fun issueAccessToken(userId: UUID, sessionId: UUID, sessionExpiresAt: Instant): IssuedAccessToken {
        val now = clock.instant()
        val configuredExpiry = now.plus(properties.accessToken.ttl)
        // The shared contract requires refresh expiry to be strictly later than access expiry.
        val expiresAt = minOf(configuredExpiry, sessionExpiresAt.minusSeconds(1))
        require(expiresAt.isAfter(now)) { "Cannot issue an access token for an expired session" }
        val claims = JwtClaimsSet.builder()
            .issuer(properties.accessToken.issuer)
            .audience(listOf(properties.accessToken.audience))
            .subject(userId.toString())
            .id(UUID.randomUUID().toString())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim(SESSION_ID_CLAIM, sessionId.toString())
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return IssuedAccessToken(jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue, expiresAt)
    }

    fun issueRefreshToken(sessionId: UUID): IssuedRefreshToken {
        val randomBytes = ByteArray(32).also(secureRandom::nextBytes)
        val randomPart = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        val value = "${sessionId}.$randomPart"
        return IssuedRefreshToken(value, hashRefreshToken(value))
    }

    fun parseSessionId(refreshToken: String): UUID? {
        val sessionPart = refreshToken.substringBefore('.', missingDelimiterValue = "")
        if (sessionPart.isEmpty() || refreshToken.count { it == '.' } != 1) return null
        return runCatching { UUID.fromString(sessionPart) }.getOrNull()
    }

    fun hashRefreshToken(refreshToken: String): String = hashSecret(refreshToken)

    fun hashSecret(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    companion object {
        const val SESSION_ID_CLAIM = "sid"
    }
}
