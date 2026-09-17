package kr.jm.moalog.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoaLogHttpClientTest {
    @Test
    fun `base URL and JSON accept header are applied to requests`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://api.moalog.test/api/system/info", request.url.toString())
            assertEquals("application/json", request.headers[HttpHeaders.Accept])
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = createMoaLogHttpClient(
            engine = engine,
            config = MoaLogNetworkConfig(baseUrl = "https://api.moalog.test/"),
        )

        try {
            assertEquals(HttpStatusCode.OK, client.get("api/system/info").status)
        } finally {
            client.close()
        }
    }

    @Test
    fun `HTTP error responses remain available for problem detail decoding`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"type":"urn:moalog:problem:validation","status":400}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        }
        val client = createMoaLogHttpClient(
            engine = engine,
            config = MoaLogNetworkConfig(baseUrl = "https://api.moalog.test"),
        )

        try {
            assertEquals(HttpStatusCode.BadRequest, client.get("api/test").status)
        } finally {
            client.close()
        }
    }

    @Test
    fun `problem detail response becomes a typed API exception`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"type":"urn:moalog:problem:validation","title":"요청 오류","status":400,"detail":"금액을 확인해 주세요","code":"VALIDATION_FAILED","errors":[]}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        }
        val client = createMoaLogHttpClient(
            engine = engine,
            config = MoaLogNetworkConfig(baseUrl = "https://api.moalog.test"),
        )

        try {
            val exception = assertFailsWith<MoaLogApiException> {
                client.get("api/test").bodyOrThrow<String>()
            }
            assertEquals(400, exception.status)
            assertEquals("VALIDATION_FAILED", exception.problem?.code)
            assertEquals("금액을 확인해 주세요", exception.message)
        } finally {
            client.close()
        }
    }

    @Test
    fun `non problem error response does not expose its body through the exception`() = runTest {
        val engine = MockEngine {
            respond(
                content = "internal proxy details",
                status = HttpStatusCode.BadGateway,
            )
        }
        val client = createMoaLogHttpClient(
            engine = engine,
            config = MoaLogNetworkConfig(baseUrl = "https://api.moalog.test"),
        )

        try {
            val response = client.get("api/test")
            val exception = assertFailsWith<MoaLogApiException> {
                response.bodyOrThrow<String>()
            }
            assertEquals(502, exception.status)
            assertNull(exception.problem)
            assertEquals(false, exception.message.orEmpty().contains("internal proxy details"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `network configuration rejects invalid values`() {
        assertFailsWith<IllegalArgumentException> {
            MoaLogNetworkConfig(baseUrl = "file:///tmp/moalog")
        }
        assertFailsWith<IllegalArgumentException> {
            MoaLogNetworkConfig(baseUrl = "https://api.moalog.test", requestTimeoutMillis = 0)
        }
        listOf(
            "https://user:secret@api.moalog.test",
            "https://api.moalog.test/v1",
            "https://api.moalog.test?tenant=one",
            "https://api.moalog.test#fragment",
        ).forEach { invalidUrl ->
            assertFailsWith<IllegalArgumentException> {
                MoaLogNetworkConfig(baseUrl = invalidUrl)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            MoaLogNetworkConfig(baseUrl = "http://10.0.2.2:8080")
        }
        assertEquals(
            "http://10.0.2.2:8080/",
            MoaLogNetworkConfig(
                baseUrl = "http://10.0.2.2:8080",
                allowInsecureHttp = true,
            ).normalizedBaseUrl,
        )
    }

    @Test
    fun `bearer token is refreshed once after unauthorized response`() = runTest {
        var requestCount = 0
        var refreshCount = 0
        val engine = MockEngine { request ->
            requestCount += 1
            when (requestCount) {
                1 -> {
                    assertEquals("Bearer expired-access", request.headers[HttpHeaders.Authorization])
                    respond(status = HttpStatusCode.Unauthorized, content = "")
                }
                else -> {
                    assertEquals("Bearer refreshed-access", request.headers[HttpHeaders.Authorization])
                    respond(status = HttpStatusCode.OK, content = "ok")
                }
            }
        }
        val tokenProvider = object : MoaLogTokenProvider {
            override suspend fun loadTokens() =
                MoaLogNetworkTokens("expired-access", "refresh-token")

            override suspend fun refreshTokens(): MoaLogNetworkTokens {
                refreshCount += 1
                return MoaLogNetworkTokens("refreshed-access", "rotated-refresh")
            }
        }
        val client = createMoaLogHttpClient(
            engine = engine,
            config = MoaLogNetworkConfig(baseUrl = "https://api.moalog.test"),
            tokenProvider = tokenProvider,
        )

        try {
            assertEquals("ok", client.get("api/v1/me").bodyAsText())
            assertEquals(2, requestCount)
            assertEquals(1, refreshCount)
        } finally {
            client.close()
        }
    }

    @Test
    fun `network tokens redact secrets from diagnostics`() {
        val rendered = MoaLogNetworkTokens("access-secret", "refresh-secret").toString()

        assertTrue("access-secret" !in rendered)
        assertTrue("refresh-secret" !in rendered)
    }

    @Test
    fun `bearer token is never sent to a different origin`() = runTest {
        var requestCount = 0
        val engine = MockEngine {
            requestCount += 1
            respond(status = HttpStatusCode.OK, content = "ok")
        }
        val tokenProvider = object : MoaLogTokenProvider {
            override suspend fun loadTokens() = MoaLogNetworkTokens("access", "refresh")
            override suspend fun refreshTokens(): MoaLogNetworkTokens? = null
        }
        val client = createMoaLogHttpClient(
            engine = engine,
            config = MoaLogNetworkConfig(baseUrl = "https://api.moalog.test"),
            tokenProvider = tokenProvider,
        )

        try {
            listOf(
                "https://other.moalog.test/data",
                "http://api.moalog.test/data",
                "https://api.moalog.test:8443/data",
            ).forEach { url ->
                assertFailsWith<IllegalArgumentException> { client.get(url) }
            }
            assertEquals(0, requestCount)
        } finally {
            client.close()
        }
    }

    @Test
    fun `bearer challenge from a different origin cannot trigger refresh`() = runTest {
        var requestCount = 0
        var refreshCount = 0
        val engine = MockEngine {
            requestCount += 1
            respond(
                status = HttpStatusCode.Unauthorized,
                content = "",
                headers = headersOf(HttpHeaders.WWWAuthenticate, "Bearer realm=external"),
            )
        }
        val tokenProvider = object : MoaLogTokenProvider {
            override suspend fun loadTokens() = MoaLogNetworkTokens("access", "refresh")
            override suspend fun refreshTokens(): MoaLogNetworkTokens {
                refreshCount += 1
                return MoaLogNetworkTokens("new-access", "new-refresh")
            }
        }
        val client = createMoaLogHttpClient(
            engine = engine,
            config = MoaLogNetworkConfig(baseUrl = "https://api.moalog.test"),
            tokenProvider = tokenProvider,
        )

        try {
            assertFailsWith<IllegalArgumentException> {
                client.get("https://outside.test/data")
            }
            assertEquals(0, requestCount)
            assertEquals(0, refreshCount)
        } finally {
            client.close()
        }
    }

    @Test
    fun `concurrent unauthorized responses share one token refresh`() = runTest {
        val requestMutex = Mutex()
        val refreshMutex = Mutex()
        val authorizationHeaders = mutableListOf<String?>()
        var refreshCount = 0
        val engine = MockEngine { request ->
            val authorization = request.headers[HttpHeaders.Authorization]
            requestMutex.withLock { authorizationHeaders += authorization }
            if (authorization == "Bearer expired-access") {
                respond(status = HttpStatusCode.Unauthorized, content = "")
            } else {
                respond(status = HttpStatusCode.OK, content = "ok")
            }
        }
        val tokenProvider = object : MoaLogTokenProvider {
            override suspend fun loadTokens() =
                MoaLogNetworkTokens("expired-access", "refresh-token")

            override suspend fun refreshTokens(): MoaLogNetworkTokens {
                refreshMutex.withLock { refreshCount += 1 }
                delay(50)
                return MoaLogNetworkTokens("refreshed-access", "rotated-refresh")
            }
        }
        val client = createMoaLogHttpClient(
            engine = engine,
            config = MoaLogNetworkConfig(baseUrl = "https://api.moalog.test"),
            tokenProvider = tokenProvider,
        )

        try {
            val bodies = coroutineScope {
                List(2) { index ->
                    async { client.get("api/v1/test/$index").bodyAsText() }
                }.awaitAll()
            }
            assertEquals(listOf("ok", "ok"), bodies)
            assertEquals(1, refreshCount)
            assertEquals(2, authorizationHeaders.count { it == "Bearer expired-access" })
            assertEquals(2, authorizationHeaders.count { it == "Bearer refreshed-access" })
        } finally {
            client.close()
        }
    }
}
