package kr.jm.moalog.auth

import kr.jm.moalog.core.contracts.AuthChallengeResponse
import kr.jm.moalog.core.contracts.AuthExchangeRequest
import kr.jm.moalog.core.contracts.AuthProvider
import kr.jm.moalog.core.contracts.AuthTokenResponse
import kr.jm.moalog.core.contracts.AuthenticatedUserDto
import kr.jm.moalog.core.contracts.ClientDeviceDto
import kr.jm.moalog.core.contracts.ClientPlatform
import kr.jm.moalog.core.contracts.UuidString
import kr.jm.moalog.core.database.SyncLocalStore
import kr.jm.moalog.core.database.SyncUuidFactory
import kr.jm.moalog.core.network.MoaLogAuthApi
import kr.jm.moalog.core.network.MoaLogApiException
import kr.jm.moalog.core.network.MoaLogNetworkTokens
import kr.jm.moalog.core.network.MoaLogTokenProvider
import kr.jm.moalog.sync.PlatformSyncUuidFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class StoredAuthSession(
    val accessToken: String,
    val accessTokenExpiresAtEpochSeconds: Long,
    val refreshToken: String,
    val refreshTokenExpiresAtEpochSeconds: Long,
    val sessionId: UuidString,
    val deviceId: UuidString,
) {
    init {
        require(accessToken.isNotBlank())
        require(refreshToken.isNotBlank())
        require(accessTokenExpiresAtEpochSeconds > 0)
        require(refreshTokenExpiresAtEpochSeconds > accessTokenExpiresAtEpochSeconds)
    }

    fun networkTokens(): MoaLogNetworkTokens = MoaLogNetworkTokens(accessToken, refreshToken)

    override fun toString(): String =
        "StoredAuthSession(accessToken=<redacted>, accessTokenExpiresAtEpochSeconds=$accessTokenExpiresAtEpochSeconds, " +
            "refreshToken=<redacted>, refreshTokenExpiresAtEpochSeconds=$refreshTokenExpiresAtEpochSeconds, " +
            "sessionId=$sessionId, deviceId=$deviceId)"
}

interface SecureAuthSessionStore {
    suspend fun read(): StoredAuthSession?
    suspend fun write(session: StoredAuthSession)
    suspend fun clear()
}

fun interface AuthClock {
    fun nowEpochSeconds(): Long
}

enum class AuthSessionStatus { RESTORING, SIGNED_OUT, SIGNING_IN, SIGNED_IN, FAILED }

data class AuthSessionState(
    val status: AuthSessionStatus = AuthSessionStatus.RESTORING,
    val user: AuthenticatedUserDto? = null,
    val errorMessage: String? = null,
) {
    val isAuthenticated: Boolean get() = status == AuthSessionStatus.SIGNED_IN
}

/**
 * The bearer plugin calls this object from arbitrary request coroutines. Refresh token rotation is
 * serialized because replaying an already-used refresh token revokes the complete server session.
 */
class PersistentMoaLogTokenProvider(
    private val store: SecureAuthSessionStore,
    private val clock: AuthClock,
) : MoaLogTokenProvider {
    private val refreshMutex = Mutex()
    private var refreshRequest: (suspend (String) -> AuthTokenResponse)? = null
    private var authenticationRejectionHandler: (() -> Unit)? = null

    fun attachRefreshRequest(request: suspend (String) -> AuthTokenResponse) {
        check(refreshRequest == null) { "Refresh request is already attached" }
        refreshRequest = request
    }

    fun attachAuthenticationRejectionHandler(handler: () -> Unit) {
        check(authenticationRejectionHandler == null) {
            "Authentication rejection handler is already attached"
        }
        authenticationRejectionHandler = handler
    }

    override suspend fun loadTokens(): MoaLogNetworkTokens? {
        val current = store.read() ?: return null
        if (current.refreshTokenExpiresAtEpochSeconds <= clock.nowEpochSeconds()) {
            invalidateSession()
            return null
        }
        return if (current.accessTokenExpiresAtEpochSeconds <= clock.nowEpochSeconds() + REFRESH_SKEW_SECONDS) {
            refreshTokens()
        } else {
            current.networkTokens()
        }
    }

    override suspend fun refreshTokens(): MoaLogNetworkTokens? {
        // Read before waiting for the mutex. If another request rotates this token while this
        // coroutine waits, reuse the successor instead of rotating it again.
        val observed = store.read() ?: return null
        return refreshMutex.withLock {
        val current = store.read() ?: return@withLock null
        if (current.refreshToken != observed.refreshToken) {
            return@withLock current.networkTokens()
        }
        if (current.refreshTokenExpiresAtEpochSeconds <= clock.nowEpochSeconds()) {
            invalidateSession()
            return@withLock null
        }
        val refresh = checkNotNull(refreshRequest) { "Refresh request has not been attached" }
        try {
            refresh(current.refreshToken).let { response ->
                response.stored(current.deviceId).also { store.write(it) }.networkTokens()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failure.isAuthenticationRejection()) {
                invalidateSession()
                null
            } else {
                throw failure
            }
        }
        }
    }

    suspend fun save(response: AuthTokenResponse, deviceId: UuidString) =
        store.write(response.stored(deviceId))
    suspend fun clear() = store.clear()
    suspend fun session(): StoredAuthSession? = store.read()

    private suspend fun invalidateSession() {
        store.clear()
        authenticationRejectionHandler?.invoke()
    }

    private companion object {
        const val REFRESH_SKEW_SECONDS = 30L
    }
}

/**
 * Owns the server session. Platform sign-in code first asks for a challenge, obtains an ID token
 * containing that nonce from Google or Apple, and then calls [completeSignIn].
 */
class MoaLogAuthSessionManager(
    private val api: MoaLogAuthApi,
    private val tokenProvider: PersistentMoaLogTokenProvider,
    private val syncLocalStore: SyncLocalStore,
    private val platform: ClientPlatform,
    private val appVersion: String,
    private val uuidFactory: SyncUuidFactory = PlatformSyncUuidFactory,
) {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow(AuthSessionState())
    val state: StateFlow<AuthSessionState> = mutableState.asStateFlow()

    init {
        require(appVersion.isNotBlank())
        tokenProvider.attachAuthenticationRejectionHandler {
            mutableState.value = AuthSessionState(
                status = AuthSessionStatus.SIGNED_OUT,
                errorMessage = "로그인 세션이 만료됐어요. 다시 로그인해 주세요",
            )
        }
    }

    suspend fun restore() = operationMutex.withLock {
        mutableState.value = AuthSessionState(AuthSessionStatus.RESTORING)
        val session = tokenProvider.session()
        if (session == null) {
            mutableState.value = AuthSessionState(AuthSessionStatus.SIGNED_OUT)
            return@withLock
        }
        try {
            syncLocalStore.requirePersistentDeviceId(session.deviceId)
            val user = api.currentUser()
            mutableState.value = AuthSessionState(AuthSessionStatus.SIGNED_IN, user)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (failure.isAuthenticationRejection()) {
                tokenProvider.clear()
                mutableState.value = AuthSessionState(AuthSessionStatus.SIGNED_OUT)
            } else {
                mutableState.value = AuthSessionState(
                    status = AuthSessionStatus.SIGNED_IN,
                    errorMessage = failure.message ?: "로그인 상태를 확인하지 못했어요",
                )
            }
        }
    }

    suspend fun beginSignIn(): AuthChallengeResponse = operationMutex.withLock {
        mutableState.value = AuthSessionState(AuthSessionStatus.SIGNING_IN)
        try {
            api.createChallenge(deviceId())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.value = AuthSessionState(
                AuthSessionStatus.FAILED,
                errorMessage = failure.message ?: "로그인을 시작하지 못했어요",
            )
            throw failure
        }
    }

    suspend fun completeSignIn(
        provider: AuthProvider,
        challenge: AuthChallengeResponse,
        idToken: String,
    ) = operationMutex.withLock {
        mutableState.value = AuthSessionState(AuthSessionStatus.SIGNING_IN)
        var sessionSaved = false
        try {
            val deviceId = deviceId()
            val tokens = api.exchange(
                AuthExchangeRequest(
                    provider = provider,
                    challengeId = challenge.challengeId,
                    idToken = idToken,
                    device = ClientDeviceDto(deviceId, platform, appVersion),
                ),
            )
            tokenProvider.save(tokens, deviceId)
            sessionSaved = true
            val user = api.currentUser()
            mutableState.value = AuthSessionState(AuthSessionStatus.SIGNED_IN, user)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (sessionSaved && !failure.isAuthenticationRejection()) {
                mutableState.value = AuthSessionState(
                    status = AuthSessionStatus.SIGNED_IN,
                    errorMessage = failure.message ?: "계정 정보를 불러오지 못했어요",
                )
                return@withLock
            }
            if (!sessionSaved || failure.isAuthenticationRejection()) {
                tokenProvider.clear()
            }
            mutableState.value = AuthSessionState(
                AuthSessionStatus.FAILED,
                errorMessage = failure.message ?: "로그인하지 못했어요",
            )
            throw failure
        }
    }

    suspend fun cancelSignIn() = operationMutex.withLock {
        if (mutableState.value.status == AuthSessionStatus.SIGNING_IN ||
            mutableState.value.status == AuthSessionStatus.FAILED
        ) {
            mutableState.value = AuthSessionState(AuthSessionStatus.SIGNED_OUT)
        }
    }

    suspend fun reportSignInFailure(message: String?) = operationMutex.withLock {
        mutableState.value = AuthSessionState(
            status = AuthSessionStatus.FAILED,
            errorMessage = message?.takeIf(String::isNotBlank) ?: "로그인하지 못했어요",
        )
    }

    suspend fun signOut() = operationMutex.withLock {
        val currentSession = tokenProvider.session()
        try {
            if (currentSession != null) api.revokeSession(currentSession.sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Local sign-out must still complete when the server is unreachable.
        } finally {
            tokenProvider.clear()
            mutableState.value = AuthSessionState(AuthSessionStatus.SIGNED_OUT)
        }
    }

    suspend fun deleteAccount() = operationMutex.withLock {
        try {
            api.deleteAccount()
            tokenProvider.clear()
            mutableState.value = AuthSessionState(AuthSessionStatus.SIGNED_OUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.value = mutableState.value.copy(
                errorMessage = failure.message ?: "계정을 삭제하지 못했어요",
            )
            throw failure
        }
    }

    suspend fun clearError() = operationMutex.withLock {
        if (mutableState.value.status == AuthSessionStatus.FAILED) {
            mutableState.value = AuthSessionState(AuthSessionStatus.SIGNED_OUT)
        }
    }

    suspend fun deviceId(): UuidString = syncLocalStore.persistentDeviceId(uuidFactory)
}

private fun AuthTokenResponse.stored(deviceId: UuidString) = StoredAuthSession(
    accessToken = accessToken,
    accessTokenExpiresAtEpochSeconds = accessTokenExpiresAtEpochSeconds,
    refreshToken = refreshToken,
    refreshTokenExpiresAtEpochSeconds = refreshTokenExpiresAtEpochSeconds,
    sessionId = sessionId,
    deviceId = deviceId,
)

private fun Throwable.isAuthenticationRejection(): Boolean =
    this is MoaLogApiException && (status == 400 || status == 401 || status == 403)
