package kr.jm.moalog.auth

import kr.jm.moalog.core.contracts.AuthTokenResponse
import kr.jm.moalog.core.contracts.UuidString
import kr.jm.moalog.core.network.MoaLogApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistentMoaLogTokenProviderTest {
    @Test
    fun loadTokensReturnsCurrentSessionWhenStillValid() = runTest {
        val store = FakeStore(
            StoredAuthSession(
                accessToken = "access",
                accessTokenExpiresAtEpochSeconds = 300,
                refreshToken = "refresh",
                refreshTokenExpiresAtEpochSeconds = 900,
                sessionId = uuid("11111111-1111-4111-8111-111111111111"),
                deviceId = uuid("22222222-2222-4222-8222-222222222222"),
            ),
        )
        val clock = FakeClock(100)
        val provider = tokenProvider(store, clock) { error("should not refresh") }

        val loaded = provider.loadTokens()

        assertNotNull(loaded)
        assertEquals("access", loaded.accessToken)
        assertEquals("refresh", loaded.refreshToken)
        assertEquals(0, store.refreshInvocations)
    }

    @Test
    fun loadTokensRefreshesNearExpiryAndPersistsNewSession() = runTest {
        val store = FakeStore(
            StoredAuthSession(
                accessToken = "access",
                accessTokenExpiresAtEpochSeconds = 120,
                refreshToken = "refresh-old",
                refreshTokenExpiresAtEpochSeconds = 900,
                sessionId = uuid("11111111-1111-4111-8111-111111111111"),
                deviceId = uuid("22222222-2222-4222-8222-222222222222"),
            ),
        )
        val clock = FakeClock(100)
        val provider = tokenProvider(store, clock) { token ->
            assertEquals("refresh-old", token)
            AuthTokenResponse(
                accessToken = "access-new",
                accessTokenExpiresAtEpochSeconds = 400,
                refreshToken = "refresh-new",
                refreshTokenExpiresAtEpochSeconds = 1_000,
                sessionId = uuid("11111111-1111-4111-8111-111111111111"),
            )
        }

        val loaded = provider.loadTokens()

        assertEquals("access-new", loaded?.accessToken)
        assertEquals("refresh-new", loaded?.refreshToken)
        assertEquals(1, store.refreshInvocations)
        assertEquals("refresh-new", store.read()?.refreshToken)
    }

    @Test
    fun loadTokensClearsExpiredSessionAndTriggersRejectionHandler() = runTest {
        val store = FakeStore(
            StoredAuthSession(
                accessToken = "access",
                accessTokenExpiresAtEpochSeconds = 90,
                refreshToken = "refresh",
                refreshTokenExpiresAtEpochSeconds = 100,
                sessionId = uuid("11111111-1111-4111-8111-111111111111"),
                deviceId = uuid("22222222-2222-4222-8222-222222222222"),
            ),
        )
        val clock = FakeClock(120)
        var rejected = false
        val provider = tokenProvider(store, clock) { error("should not refresh") }
        provider.attachAuthenticationRejectionHandler { rejected = true }

        assertNull(provider.loadTokens())
        assertNull(store.read())
        assertTrue(rejected)
    }

    @Test
    fun concurrentRefreshRequestsRunOnceDueToSerialization() = runTest {
        val store = FakeStore(
            StoredAuthSession(
                accessToken = "access",
                accessTokenExpiresAtEpochSeconds = 120,
                refreshToken = "refresh-old",
                refreshTokenExpiresAtEpochSeconds = 900,
                sessionId = uuid("11111111-1111-4111-8111-111111111111"),
                deviceId = uuid("22222222-2222-4222-8222-222222222222"),
            ),
        )
        val clock = FakeClock(100)
        val provider = tokenProvider(store, clock) { token ->
            assertEquals("refresh-old", token)
            delay(50)
            AuthTokenResponse(
                accessToken = "access-refresh",
                accessTokenExpiresAtEpochSeconds = 360,
                refreshToken = "refresh-new",
                refreshTokenExpiresAtEpochSeconds = 900,
                sessionId = uuid("11111111-1111-4111-8111-111111111111"),
            )
        }

        val first = async { provider.refreshTokens() }
        val second = async { provider.refreshTokens() }

        val firstTokens = first.await()
        val secondTokens = second.await()

        assertEquals(1, store.refreshInvocations)
        assertEquals(firstTokens?.accessToken, secondTokens?.accessToken)
        assertEquals(firstTokens?.refreshToken, secondTokens?.refreshToken)
        assertEquals("access-refresh", firstTokens?.accessToken)
    }

    @Test
    fun refreshAuthenticationRejectionClearsStoreAndNotifiesHandler() = runTest {
        val store = FakeStore(
            StoredAuthSession(
                accessToken = "access",
                accessTokenExpiresAtEpochSeconds = 120,
                refreshToken = "refresh-old",
                refreshTokenExpiresAtEpochSeconds = 900,
                sessionId = uuid("11111111-1111-4111-8111-111111111111"),
                deviceId = uuid("22222222-2222-4222-8222-222222222222"),
            ),
        )
        val clock = FakeClock(100)
        var rejected = false
        val provider = tokenProvider(store, clock) {
            throw MoaLogApiException(
                status = 401,
                problem = null,
            )
        }
        provider.attachAuthenticationRejectionHandler { rejected = true }

        assertNull(provider.refreshTokens())
        assertTrue(rejected)
        assertNull(store.read())
    }

    private fun tokenProvider(
        store: FakeStore,
        clock: FakeClock,
        refresh: suspend (String) -> AuthTokenResponse,
    ): PersistentMoaLogTokenProvider = PersistentMoaLogTokenProvider(store, clock).also {
        it.attachRefreshRequest(refresh)
    }

    private class FakeStore(initial: StoredAuthSession? = null) : SecureAuthSessionStore {
        private var current: StoredAuthSession? = initial
        var refreshInvocations = 0

        override suspend fun read(): StoredAuthSession? = current

        override suspend fun write(session: StoredAuthSession) {
            refreshInvocations += 1
            current = session
        }

        override suspend fun clear() {
            current = null
        }
    }

    private class FakeClock(now: Long) : AuthClock {
        private var value = now
        override fun nowEpochSeconds(): Long = value
    }
}

private fun uuid(value: String): UuidString = UuidString(value)
