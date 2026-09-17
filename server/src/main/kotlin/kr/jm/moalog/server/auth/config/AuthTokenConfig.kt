package kr.jm.moalog.server.auth.config

import com.nimbusds.jose.jwk.source.ImmutableSecret
import kr.jm.moalog.server.auth.domain.InvalidProviderTokenException
import kr.jm.moalog.server.auth.domain.ProviderIdTokenVerifier
import kr.jm.moalog.server.auth.domain.VerifiedProviderIdentity
import kr.jm.moalog.server.auth.application.AuthTokenService
import kr.jm.moalog.server.auth.infrastructure.AuthSessionRepository
import kr.jm.moalog.core.contracts.AuthProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.time.Clock
import java.util.UUID
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

@Configuration
@EnableConfigurationProperties(AuthProperties::class)
class AuthTokenConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun accessTokenSecret(properties: AuthProperties): SecretKeySpec {
        val decoded = runCatching { Base64.getDecoder().decode(properties.accessToken.secretBase64) }
            .getOrElse { throw IllegalArgumentException("Access token secret must be valid Base64") }
        require(decoded.size >= 32) { "Access token secret must contain at least 256 bits" }
        return SecretKeySpec(decoded, "HmacSHA256")
    }

    @Bean
    fun jwtEncoder(secret: SecretKeySpec): JwtEncoder = NimbusJwtEncoder(ImmutableSecret(secret))

    @Bean
    fun jwtDecoder(
        secret: SecretKeySpec,
        properties: AuthProperties,
        sessions: AuthSessionRepository,
        clock: Clock,
    ): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(secret).build().apply {
            setJwtValidator(
                DelegatingOAuth2TokenValidator(
                    JwtTimestampValidator(),
                    issuerValidator(setOf(properties.accessToken.issuer)),
                    audienceValidator(properties.accessToken.audience),
                    activeSessionValidator(sessions, clock),
                ),
            )
        }

    @Bean
    @ConditionalOnMissingBean(ProviderIdTokenVerifier::class)
    fun providerIdTokenVerifier(properties: AuthProperties): ProviderIdTokenVerifier {
        val google = providerDecoder(
            jwkSetUri = "https://www.googleapis.com/oauth2/v3/certs",
            issuers = setOf("https://accounts.google.com", "accounts.google.com"),
            audience = properties.googleAudience,
        )
        val apple = providerDecoder(
            jwkSetUri = "https://appleid.apple.com/auth/keys",
            issuers = setOf("https://appleid.apple.com"),
            audience = properties.appleAudience,
        )
        return ProviderIdTokenVerifier { provider, idToken ->
            val jwt = try {
                when (provider) {
                    AuthProvider.GOOGLE -> google.decode(idToken)
                    AuthProvider.APPLE -> apple.decode(idToken)
                }
            } catch (_: JwtException) {
                throw InvalidProviderTokenException()
            }
            val subject = jwt.subject?.takeIf(String::isNotBlank) ?: throw InvalidProviderTokenException()
            VerifiedProviderIdentity(
                provider = provider,
                subject = subject,
                email = jwt.getClaimAsString("email")?.takeIf(String::isNotBlank),
                displayName = jwt.getClaimAsString("name")?.takeIf(String::isNotBlank),
                nonce = jwt.getClaimAsString("nonce")?.takeIf(String::isNotBlank)
                    ?: throw InvalidProviderTokenException(),
            )
        }
    }

    internal fun providerDecoder(jwkSetUri: String, issuers: Set<String>, audience: String): JwtDecoder =
        NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build().apply {
            setJwtValidator(
                DelegatingOAuth2TokenValidator(
                    JwtTimestampValidator(),
                    issuerValidator(issuers),
                    audienceValidator(audience),
                ),
            )
        }

    private fun issuerValidator(allowed: Set<String>) = OAuth2TokenValidator<Jwt> { jwt ->
        if (jwt.getClaimAsString(JwtClaimNames.ISS) in allowed) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "Invalid issuer", null))
        }
    }

    private fun audienceValidator(required: String) = OAuth2TokenValidator<Jwt> { jwt ->
        if (required in jwt.audience) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "Invalid audience", null))
        }
    }

    private fun activeSessionValidator(
        sessions: AuthSessionRepository,
        clock: Clock,
    ) = OAuth2TokenValidator<Jwt> { jwt ->
        val userId = runCatching { UUID.fromString(jwt.subject) }.getOrNull()
        val sessionId = runCatching { UUID.fromString(jwt.getClaimAsString(AuthTokenService.SESSION_ID_CLAIM)) }.getOrNull()
        if (
            userId != null && sessionId != null &&
            sessions.existsByIdAndUserIdAndRevokedAtIsNullAndExpiresAtAfter(sessionId, userId, clock.instant())
        ) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "Inactive session", null))
        }
    }
}
