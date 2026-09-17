package kr.jm.moalog.server.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("moalog.auth")
data class AuthProperties(
    val accessToken: AccessTokenProperties,
    val refreshTokenTtl: Duration,
    val loginChallengeTtl: Duration,
    val maxActiveLoginChallenges: Long,
    val maxActiveLoginChallengesPerDevice: Long,
    val maxActiveLoginChallengesPerSource: Long,
    val maxExchangeAttemptsPerChallenge: Int,
    val googleAudience: String,
    val appleAudience: String,
    val refreshTokenReplayGrace: Duration = Duration.ofSeconds(30),
) {
    init {
        require(refreshTokenTtl > accessToken.ttl) { "Refresh token TTL must exceed access token TTL" }
        require(!loginChallengeTtl.isNegative && !loginChallengeTtl.isZero) { "Login challenge TTL must be positive" }
        require(!refreshTokenReplayGrace.isNegative && !refreshTokenReplayGrace.isZero) {
            "Refresh token replay grace must be positive"
        }
        require(maxActiveLoginChallenges > 0) { "Maximum active login challenges must be positive" }
        require(maxActiveLoginChallengesPerDevice > 0) {
            "Maximum active login challenges per device must be positive"
        }
        require(maxActiveLoginChallengesPerDevice <= maxActiveLoginChallenges) {
            "Per-device challenge limit cannot exceed the global limit"
        }
        require(maxActiveLoginChallengesPerSource > 0) {
            "Maximum active login challenges per source must be positive"
        }
        require(maxActiveLoginChallengesPerSource <= maxActiveLoginChallenges) {
            "Per-source challenge limit cannot exceed the global limit"
        }
        require(maxExchangeAttemptsPerChallenge > 0) { "Exchange attempt limit must be positive" }
        require(googleAudience.isNotBlank()) { "Google audience is required" }
        require(appleAudience.isNotBlank()) { "Apple audience is required" }
    }
}

data class AccessTokenProperties(
    val issuer: String,
    val audience: String,
    val secretBase64: String,
    val ttl: Duration,
) {
    init {
        require(issuer.isNotBlank()) { "Access token issuer is required" }
        require(audience.isNotBlank()) { "Access token audience is required" }
        require(secretBase64.isNotBlank()) { "Access token secret is required" }
        require(!ttl.isNegative && !ttl.isZero) { "Access token TTL must be positive" }
    }
}
