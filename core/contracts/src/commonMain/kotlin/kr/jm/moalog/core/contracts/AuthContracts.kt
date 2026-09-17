package kr.jm.moalog.core.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AuthProvider {
    @SerialName("google")
    GOOGLE,

    @SerialName("apple")
    APPLE,
}

@Serializable
enum class ClientPlatform {
    @SerialName("android")
    ANDROID,

    @SerialName("ios")
    IOS,
}

@Serializable
data class ClientDeviceDto(
    val deviceId: UuidString,
    val platform: ClientPlatform,
    val appVersion: String,
) {
    init {
        require(appVersion.isNotBlank()) { "appVersion must not be blank" }
    }
}

@Serializable
data class AuthChallengeResponse(
    val challengeId: UuidString,
    val nonce: String,
    val expiresAtEpochSeconds: Long,
) {
    init {
        require(nonce.isNotBlank()) { "nonce must not be blank" }
        require(expiresAtEpochSeconds > 0) { "challenge expiry must be positive" }
    }

    override fun toString(): String =
        "AuthChallengeResponse(challengeId=$challengeId, nonce=<redacted>, " +
            "expiresAtEpochSeconds=$expiresAtEpochSeconds)"
}

@Serializable
data class AuthExchangeRequest(
    val provider: AuthProvider,
    val challengeId: UuidString,
    val idToken: String,
    val device: ClientDeviceDto,
) {
    init {
        require(idToken.isNotBlank()) { "idToken must not be blank" }
    }

    override fun toString(): String =
        "AuthExchangeRequest(provider=$provider, challengeId=$challengeId, " +
            "idToken=<redacted>, device=$device)"
}

@Serializable
data class AuthRefreshRequest(
    val refreshToken: String,
) {
    init {
        require(refreshToken.isNotBlank()) { "refreshToken must not be blank" }
    }

    override fun toString(): String = "AuthRefreshRequest(refreshToken=<redacted>)"
}

@Serializable
data class AuthTokenResponse(
    val accessToken: String,
    val accessTokenExpiresAtEpochSeconds: Long,
    val refreshToken: String,
    val refreshTokenExpiresAtEpochSeconds: Long,
    val sessionId: UuidString,
) {
    init {
        require(accessToken.isNotBlank()) { "accessToken must not be blank" }
        require(refreshToken.isNotBlank()) { "refreshToken must not be blank" }
        require(accessTokenExpiresAtEpochSeconds > 0) { "access token expiry must be positive" }
        require(refreshTokenExpiresAtEpochSeconds > accessTokenExpiresAtEpochSeconds) {
            "refresh token must expire after the access token"
        }
    }

    override fun toString(): String =
        "AuthTokenResponse(accessToken=<redacted>, " +
            "accessTokenExpiresAtEpochSeconds=$accessTokenExpiresAtEpochSeconds, " +
            "refreshToken=<redacted>, " +
            "refreshTokenExpiresAtEpochSeconds=$refreshTokenExpiresAtEpochSeconds, " +
            "sessionId=$sessionId)"
}

@Serializable
data class AuthenticatedUserDto(
    val userId: UuidString,
    val displayName: String?,
    val email: String?,
)
