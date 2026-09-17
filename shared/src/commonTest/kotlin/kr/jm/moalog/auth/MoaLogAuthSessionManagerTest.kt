package kr.jm.moalog.auth

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kr.jm.moalog.core.contracts.AuthProvider
import kr.jm.moalog.core.contracts.AuthTokenResponse
import kr.jm.moalog.core.contracts.AuthenticatedUserDto
import kr.jm.moalog.core.contracts.ClientPlatform
import kr.jm.moalog.core.contracts.UuidString
import kr.jm.moalog.core.database.PendingHouseholdSettings
import kr.jm.moalog.core.database.PersistentSyncMutation
import kr.jm.moalog.core.database.SyncLocalStore
import kr.jm.moalog.core.database.SyncUuidFactory
import kr.jm.moalog.core.network.MoaLogAuthApi
import kr.jm.moalog.core.network.MoaLogApiException
import kr.jm.moalog.core.network.MoaLogNetworkConfig
import kr.jm.moalog.core.network.createMoaLogHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MoaLogAuthSessionManagerTest {
    private val appVersion = "1.0.0"
    private val authDevice = uuid("11111111-1111-4111-8111-111111111111")
    private val sessionId = uuid("22222222-2222-4222-8222-222222222222")

    @Test
    fun restoreClearsStateWhenNoSession() = runTest {
        val manager = createManager(
            tokenStore = FakeSessionStore(null),
            localStore = FakeSyncStore(),
            currentUserStatus = HttpStatusCode.OK,
            currentUserResponse = null,
        )

        manager.restore()

        assertEquals(AuthSessionStatus.SIGNED_OUT, manager.state.value.status)
    }

    @Test
    fun restoreUnauthorizedFromCurrentUserClearsTokenAndSignsOut() = runTest {
        val store = FakeSessionStore(
            StoredAuthSession(
                accessToken = "access",
                accessTokenExpiresAtEpochSeconds = 120,
                refreshToken = "refresh",
                refreshTokenExpiresAtEpochSeconds = 900,
                sessionId = sessionId,
                deviceId = authDevice,
            ),
        )
        val manager = createManager(
            tokenStore = store,
            localStore = FakeSyncStore(),
            currentUserStatus = HttpStatusCode.Unauthorized,
            currentUserResponse = null,
        )

        manager.restore()

        assertEquals(AuthSessionStatus.SIGNED_OUT, manager.state.value.status)
        assertNull(store.read())
    }

    @Test
    fun restoreTransientCurrentUserFailureKeepsStoredSessionSignedIn() = runTest {
        val store = FakeSessionStore(
            StoredAuthSession(
                accessToken = "access",
                accessTokenExpiresAtEpochSeconds = 1_200,
                refreshToken = "refresh",
                refreshTokenExpiresAtEpochSeconds = 1_900,
                sessionId = sessionId,
                deviceId = authDevice,
            ),
        )
        val manager = createManager(
            tokenStore = store,
            localStore = FakeSyncStore(),
            currentUserStatus = HttpStatusCode.InternalServerError,
            currentUserResponse = null,
        )

        manager.restore()

        assertEquals(AuthSessionStatus.SIGNED_IN, manager.state.value.status)
        assertNotNull(manager.state.value.errorMessage)
        assertNotNull(store.read())
    }

    @Test
    fun signInFlowSavesSessionAndSignsIn() = runTest {
        val store = FakeSessionStore(null)
        val manager = createManager(
            tokenStore = store,
            localStore = FakeSyncStore(),
            currentUserStatus = HttpStatusCode.OK,
            currentUserResponse = AuthenticatedUserDto(
                userId = uuid("33333333-3333-4333-8333-333333333333"),
                displayName = "모아",
                email = "moa@example.com",
            ),
            exchangeResponse = true,
            challengeResponse = true,
        )

        val challenge = manager.beginSignIn()

        assertEquals(AuthSessionStatus.SIGNING_IN, manager.state.value.status)
        manager.completeSignIn(
            provider = AuthProvider.GOOGLE,
            challenge = challenge,
            idToken = "google-id-token",
        )

        assertEquals(AuthSessionStatus.SIGNED_IN, manager.state.value.status)
        assertEquals("모아", manager.state.value.user?.displayName)
        assertNotNull(store.read())
    }

    @Test
    fun completeSignInFailureWhileFetchingUserKeepsSignedInAndCachesTokens() = runTest {
        val store = FakeSessionStore(null)
        val manager = createManager(
            tokenStore = store,
            localStore = FakeSyncStore(),
            currentUserStatus = HttpStatusCode.InternalServerError,
            currentUserResponse = null,
            exchangeResponse = true,
            challengeResponse = true,
        )

        val challenge = manager.beginSignIn()

        manager.completeSignIn(
            provider = AuthProvider.GOOGLE,
            challenge = challenge,
            idToken = "google-id-token",
        )

        assertEquals(AuthSessionStatus.SIGNED_IN, manager.state.value.status)
        assertNotNull(manager.state.value.errorMessage)
        assertNotNull(store.read())
    }

    @Test
    fun completeSignInAuthRejectionClearsSessionAndThrows() = runTest {
        val store = FakeSessionStore(null)
        val manager = createManager(
            tokenStore = store,
            localStore = FakeSyncStore(),
            currentUserStatus = HttpStatusCode.Unauthorized,
            currentUserResponse = null,
            exchangeResponse = true,
            challengeResponse = true,
        )

        val challenge = manager.beginSignIn()

        val failure = runCatching {
            manager.completeSignIn(
                provider = AuthProvider.GOOGLE,
                challenge = challenge,
                idToken = "google-id-token",
            )
        }.exceptionOrNull()

        assertIs<MoaLogApiException>(failure)
        assertEquals(AuthSessionStatus.FAILED, manager.state.value.status)
        assertNull(store.read())
    }

    @Test
    fun signOutClearsLocalSessionEvenIfServerRevocationFails() = runTest {
        val store = FakeSessionStore(
            StoredAuthSession(
                accessToken = "access",
                accessTokenExpiresAtEpochSeconds = 120,
                refreshToken = "refresh",
                refreshTokenExpiresAtEpochSeconds = 900,
                sessionId = sessionId,
                deviceId = authDevice,
            ),
        )
        val manager = createManager(
            tokenStore = store,
            localStore = FakeSyncStore(),
            currentUserStatus = HttpStatusCode.OK,
            currentUserResponse = null,
            revokeStatus = HttpStatusCode.InternalServerError,
        )

        manager.signOut()

        assertEquals(AuthSessionStatus.SIGNED_OUT, manager.state.value.status)
        assertNull(store.read())
    }

    private fun createManager(
        tokenStore: FakeSessionStore,
        localStore: SyncLocalStore,
        currentUserStatus: HttpStatusCode,
        currentUserResponse: AuthenticatedUserDto?,
        exchangeResponse: Boolean = false,
        challengeResponse: Boolean = false,
        revokeStatus: HttpStatusCode = HttpStatusCode.NoContent,
    ): MoaLogAuthSessionManager {
        val tokenProvider = PersistentMoaLogTokenProvider(tokenStore, FakeClock()).also {
            it.attachRefreshRequest { refreshToken ->
                if (refreshToken.startsWith("bad-refresh")) {
                    throw MoaLogApiException(
                        status = 401,
                        problem = null,
                    )
                }
                AuthTokenResponse(
                    accessToken = "access-refreshed",
                    accessTokenExpiresAtEpochSeconds = 200,
                    refreshToken = refreshToken,
                    refreshTokenExpiresAtEpochSeconds = 500,
                    sessionId = sessionId,
                )
            }
        }
        val api = createAuthApi(
            tokenProvider = tokenProvider,
            currentUserStatus = currentUserStatus,
            currentUserResponse = currentUserResponse,
            exchangeResponse = exchangeResponse,
            challengeResponse = challengeResponse,
            revokeStatus = revokeStatus,
        )
        return MoaLogAuthSessionManager(
            api = api,
            tokenProvider = tokenProvider,
            syncLocalStore = localStore,
            platform = ClientPlatform.ANDROID,
            appVersion = appVersion,
        )
    }

    private fun createAuthApi(
        tokenProvider: PersistentMoaLogTokenProvider,
        currentUserStatus: HttpStatusCode,
        currentUserResponse: AuthenticatedUserDto?,
        exchangeResponse: Boolean,
        challengeResponse: Boolean,
        revokeStatus: HttpStatusCode,
    ): MoaLogAuthApi {
        val publicClient = createMoaLogHttpClient(
            engine = MockEngine { request ->
                when {
                    request.method == HttpMethod.Post && request.url.encodedPath == "/api/auth/challenge" -> {
                        if (!challengeResponse) error("challenge response disabled")
                        respond(
                            content = CHALLENGE_JSON,
                            status = HttpStatusCode.OK,
                            headers = jsonHeaders,
                        )
                    }
                    request.method == HttpMethod.Post && request.url.encodedPath == "/api/auth/exchange" -> {
                        if (!exchangeResponse) error("exchange response disabled")
                        respond(
                            content = TOKEN_JSON,
                            status = HttpStatusCode.OK,
                            headers = jsonHeaders,
                        )
                    }
                    request.method == HttpMethod.Post && request.url.encodedPath == "/api/auth/refresh" -> {
                        respond(
                            content = TOKEN_JSON,
                            status = HttpStatusCode.OK,
                            headers = jsonHeaders,
                        )
                    }
                    else -> error("Unhandled public API request: ${request.url.encodedPath}")
                }
            },
            config = MoaLogNetworkConfig("https://api.moalog.test"),
            tokenProvider = null,
        )

        val authenticatedClient = createMoaLogHttpClient(
            engine = MockEngine { request ->
                when {
                    request.method == HttpMethod.Get && request.url.encodedPath == "/api/auth/me" -> {
                        if (currentUserStatus.value in 200..299) {
                            respond(
                                content =
                                    currentUserResponse?.let {
                                        "{\"userId\":\"${it.userId}\",\"displayName\":\"${it.displayName}\",\"email\":\"${it.email}\"}"
                                    } ?: CURRENT_USER_JSON,
                                status = currentUserStatus,
                                headers = jsonHeaders,
                            )
                        } else {
                            respond(content = "", status = currentUserStatus)
                        }
                    }
                    request.method == HttpMethod.Delete && request.url.encodedPath.startsWith("/api/auth/sessions/") -> {
                        respond(content = "", status = revokeStatus)
                    }
                    request.method == HttpMethod.Delete && request.url.encodedPath == "/api/auth/me" -> {
                        respond(content = "", status = HttpStatusCode.NoContent)
                    }
                    else -> error("Unhandled authenticated API request: ${request.url.encodedPath}")
                }
            },
            config = MoaLogNetworkConfig("https://api.moalog.test"),
            tokenProvider = tokenProvider,
        )
        return MoaLogAuthApi(publicClient, authenticatedClient)
    }

    private class FakeSessionStore(initial: StoredAuthSession?) : SecureAuthSessionStore {
        private var session: StoredAuthSession? = initial

        override suspend fun read(): StoredAuthSession? = session
        override suspend fun write(session: StoredAuthSession) { this.session = session }
        override suspend fun clear() { session = null }
    }

    private class FakeClock : AuthClock {
        override fun nowEpochSeconds(): Long = 1_000L
    }

    private class FakeSyncStore : SyncLocalStore {
        override suspend fun persistentDeviceId(uuidFactory: SyncUuidFactory): UuidString =
            UuidString("00000000-0000-4000-8000-ffffffffffff")
        override suspend fun requirePersistentDeviceId(deviceId: UuidString) {}
        override suspend fun boundHouseholdId(): UuidString? = null
        override suspend fun bindHousehold(householdId: UuidString) {}
        override suspend fun pendingHouseholdSettings(): PendingHouseholdSettings? = null
        override suspend fun markHouseholdSettingsPending(householdId: UuidString, baseVersion: Long) {}
        override suspend fun clearPendingHouseholdSettings(householdId: UuidString) {}
        override suspend fun resetCloudBinding() {}
        override suspend fun captureLocalChanges(
            householdId: UuidString,
            deviceId: UuidString,
            nowEpochMillis: Long,
            uuidFactory: SyncUuidFactory,
        ) {}
        override suspend fun readyMutations(
            householdId: UuidString,
            nowEpochMillis: Long,
            limit: Int,
        ): List<PersistentSyncMutation> = emptyList()
        override suspend fun nextPendingRetryAt(householdId: UuidString): Long? = null
        override fun observeLocalChanges(): Flow<Unit> = flowOf(Unit)
        override suspend fun acknowledgePush(
            householdId: UuidString,
            acknowledgements: List<kr.jm.moalog.core.contracts.AppliedMutationDto>,
        ) {}
        override suspend fun markPushFailed(mutationIds: List<UuidString>, nextAttemptAtEpochMillis: Long, error: String) {}
        override suspend fun pullCursor(householdId: UuidString): Long = 0L
        override suspend fun applyPulledChanges(
            householdId: UuidString,
            changes: kotlin.collections.List<kr.jm.moalog.core.contracts.SyncChangeDto>,
            nextCursor: Long,
            resolvePendingConflicts: Boolean,
        ) {}
    }

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        const val CHALLENGE_JSON =
            "{\"challengeId\":\"44444444-4444-4444-8444-444444444444\",\"nonce\":\"nonce\",\"expiresAtEpochSeconds\":1600}"
        const val TOKEN_JSON =
            "{\"accessToken\":\"access-token\",\"accessTokenExpiresAtEpochSeconds\":1200,\"refreshToken\":\"refresh-token\",\"refreshTokenExpiresAtEpochSeconds\":1500,\"sessionId\":\"22222222-2222-4222-8222-222222222222\"}"
        const val CURRENT_USER_JSON =
            "{\"userId\":\"33333333-3333-4333-8333-333333333333\",\"displayName\":\"모아\",\"email\":\"moa@example.com\"}"
    }
}

private fun uuid(value: String): UuidString = UuidString(value)
