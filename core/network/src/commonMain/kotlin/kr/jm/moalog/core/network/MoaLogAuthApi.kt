package kr.jm.moalog.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kr.jm.moalog.core.contracts.AuthExchangeRequest
import kr.jm.moalog.core.contracts.AuthChallengeResponse
import kr.jm.moalog.core.contracts.AuthRefreshRequest
import kr.jm.moalog.core.contracts.AuthTokenResponse
import kr.jm.moalog.core.contracts.AuthenticatedUserDto
import kr.jm.moalog.core.contracts.UuidString

/**
 * Authentication endpoints use a public client for token exchange and refresh so a failed access
 * token cannot recursively invoke refresh. User and session operations use the authenticated client.
 */
class MoaLogAuthApi(
    private val publicClient: HttpClient,
    private val authenticatedClient: HttpClient,
) {
    suspend fun createChallenge(deviceId: UuidString): AuthChallengeResponse =
        publicClient.post(AUTH_CHALLENGE_PATH) {
            header(DEVICE_ID_HEADER, deviceId.value)
        }.bodyOrThrow()

    suspend fun exchange(request: AuthExchangeRequest): AuthTokenResponse =
        publicClient.post(AUTH_EXCHANGE_PATH) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.bodyOrThrow()

    suspend fun refresh(refreshToken: String): AuthTokenResponse =
        publicClient.post(AUTH_REFRESH_PATH) {
            contentType(ContentType.Application.Json)
            setBody(AuthRefreshRequest(refreshToken))
        }.bodyOrThrow()

    suspend fun currentUser(): AuthenticatedUserDto =
        authenticatedClient.get(AUTH_ME_PATH).bodyOrThrow()

    suspend fun deleteAccount() {
        authenticatedClient.delete(AUTH_ME_PATH).throwIfError()
    }

    suspend fun revokeSession(sessionId: UuidString) {
        authenticatedClient.delete("$AUTH_SESSIONS_PATH/${sessionId.value}").throwIfError()
    }

    private companion object {
        const val AUTH_CHALLENGE_PATH = "api/auth/challenge"
        const val AUTH_EXCHANGE_PATH = "api/auth/exchange"
        const val AUTH_REFRESH_PATH = "api/auth/refresh"
        const val AUTH_ME_PATH = "api/auth/me"
        const val AUTH_SESSIONS_PATH = "api/auth/sessions"
        const val DEVICE_ID_HEADER = "X-MoaLog-Device-Id"
    }
}
