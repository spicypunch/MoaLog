package kr.jm.moalog.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.readRemaining
import kr.jm.moalog.core.contracts.AuthExchangeRequest
import kr.jm.moalog.core.contracts.AuthProvider
import kr.jm.moalog.core.contracts.ClientDeviceDto
import kr.jm.moalog.core.contracts.ClientPlatform
import kr.jm.moalog.core.contracts.UuidString
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoaLogAuthApiTest {
    @Test
    fun `exchange and refresh use the public client with shared contracts`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = tokenResponseJson,
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val publicClient = testClient(engine)
        val authenticatedClient = testClient(MockEngine { error("authenticated client must not be used") })
        val api = MoaLogAuthApi(publicClient, authenticatedClient)

        try {
            val exchange = api.exchange(
                AuthExchangeRequest(
                    provider = AuthProvider.GOOGLE,
                    challengeId = UuidString("44444444-4444-4444-8444-444444444444"),
                    idToken = "provider-id-token",
                    device = ClientDeviceDto(
                        deviceId = UuidString("11111111-1111-4111-8111-111111111111"),
                        platform = ClientPlatform.ANDROID,
                        appVersion = "1.0.0",
                    ),
                ),
            )
            val refresh = api.refresh("refresh-token")

            assertEquals("access-token", exchange.accessToken)
            assertEquals("rotated-refresh-token", refresh.refreshToken)
            assertEquals(HttpMethod.Post, requests[0].method)
            assertEquals("/api/auth/exchange", requests[0].url.encodedPath)
            assertEquals(HttpMethod.Post, requests[1].method)
            assertEquals("/api/auth/refresh", requests[1].url.encodedPath)
            assertTrue(requests[0].bodyText().contains("\"idToken\":\"provider-id-token\""))
            assertTrue(requests[1].bodyText().contains("\"refreshToken\":\"refresh-token\""))
        } finally {
            publicClient.close()
            authenticatedClient.close()
        }
    }

    @Test
    fun `login challenge uses the public client and redacts its nonce`() = runTest {
        var request: HttpRequestData? = null
        val engine = MockEngine { captured ->
            request = captured
            respond(
                content = """{"challengeId":"44444444-4444-4444-8444-444444444444","nonce":"one-time-nonce","expiresAtEpochSeconds":400}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val publicClient = testClient(engine)
        val authenticatedClient = testClient(MockEngine { error("authenticated client must not be used") })
        val api = MoaLogAuthApi(publicClient, authenticatedClient)

        try {
            val deviceId = UuidString("55555555-5555-4555-8555-555555555555")
            val challenge = api.createChallenge(deviceId)

            assertEquals(HttpMethod.Post, request?.method)
            assertEquals("/api/auth/challenge", request?.url?.encodedPath)
            assertEquals(deviceId.value, request?.headers?.get("X-MoaLog-Device-Id"))
            assertEquals("one-time-nonce", challenge.nonce)
            assertTrue("one-time-nonce" !in challenge.toString())
        } finally {
            publicClient.close()
            authenticatedClient.close()
        }
    }

    @Test
    fun `current user and session revocation use the authenticated client`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            if (request.method == HttpMethod.Get) {
                respond(
                    content = """{"userId":"22222222-2222-4222-8222-222222222222","displayName":"모아","email":null}""",
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
            } else {
                respond(content = "", status = HttpStatusCode.NoContent)
            }
        }
        val publicClient = testClient(MockEngine { error("public client must not be used") })
        val authenticatedClient = createMoaLogHttpClient(
            engine = engine,
            config = testConfig,
            tokenProvider = object : MoaLogTokenProvider {
                override suspend fun loadTokens() = MoaLogNetworkTokens("access-token", "refresh-token")
                override suspend fun refreshTokens(): MoaLogNetworkTokens? = null
            },
        )
        val api = MoaLogAuthApi(publicClient, authenticatedClient)
        val sessionId = UuidString("33333333-3333-4333-8333-333333333333")

        try {
            val user = api.currentUser()
            api.revokeSession(sessionId)
            api.deleteAccount()

            assertEquals("모아", user.displayName)
            assertEquals(listOf(HttpMethod.Get, HttpMethod.Delete, HttpMethod.Delete), requests.map { it.method })
            assertEquals("/api/auth/me", requests[0].url.encodedPath)
            assertEquals("/api/auth/sessions/${sessionId.value}", requests[1].url.encodedPath)
            assertEquals("/api/auth/me", requests[2].url.encodedPath)
            assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer access-token" })
        } finally {
            publicClient.close()
            authenticatedClient.close()
        }
    }

    private fun testClient(engine: MockEngine) = createMoaLogHttpClient(engine, testConfig)

    private suspend fun HttpRequestData.bodyText(): String = when (val content = body) {
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        is OutgoingContent.ReadChannelContent -> content.readFrom().readRemaining().readText()
        else -> error("Unexpected request body: ${content::class}")
    }

    private companion object {
        val testConfig = MoaLogNetworkConfig(baseUrl = "https://api.moalog.test")
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        const val tokenResponseJson =
            """{"accessToken":"access-token","accessTokenExpiresAtEpochSeconds":200,"refreshToken":"rotated-refresh-token","refreshTokenExpiresAtEpochSeconds":300,"sessionId":"33333333-3333-4333-8333-333333333333"}"""
    }
}
