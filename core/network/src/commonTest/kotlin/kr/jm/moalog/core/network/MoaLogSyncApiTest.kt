package kr.jm.moalog.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.readRemaining
import kr.jm.moalog.core.contracts.SyncEntityType
import kr.jm.moalog.core.contracts.SyncMutationDto
import kr.jm.moalog.core.contracts.SyncOperation
import kr.jm.moalog.core.contracts.SyncPushRequest
import kr.jm.moalog.core.contracts.UuidString
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoaLogSyncApiTest {
    @Test
    fun `push sends authenticated shared mutation contract`() = runTest {
        var captured: HttpRequestData? = null
        val client = authenticatedClient(MockEngine { request ->
            captured = request
            respond(
                """{"applied":[{"mutationId":"20000000-0000-4000-8000-000000000002","entityId":"30000000-0000-4000-8000-000000000003","version":1}],"currentCursor":7}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        })
        val request = SyncPushRequest(
            deviceId = deviceId,
            mutations = listOf(
                SyncMutationDto(
                    mutationId = mutationId,
                    entityType = SyncEntityType.EXPENSE_RECORD,
                    entityId = entityId,
                    operation = SyncOperation.UPSERT,
                    baseVersion = null,
                    payload = buildJsonObject { put("amountWon", 12_000L) },
                ),
            ),
        )

        try {
            val response = MoaLogSyncApi(client).push(householdId, request)

            assertEquals(7, response.currentCursor)
            assertEquals(HttpMethod.Post, captured?.method)
            assertEquals("/api/households/${householdId.value}/sync/push", captured?.url?.encodedPath)
            assertEquals("Bearer access", captured?.headers?.get(HttpHeaders.Authorization))
            assertTrue(captured!!.bodyText().contains("\"entityType\":\"expense_record\""))
        } finally {
            client.close()
        }
    }

    @Test
    fun `pull sends cursor and bounded page size and decodes ordered changes`() = runTest {
        var captured: HttpRequestData? = null
        val client = authenticatedClient(MockEngine { request ->
            captured = request
            respond(
                """{"changes":[{"cursor":8,"entityType":"expense_record","entityId":"30000000-0000-4000-8000-000000000003","operation":"delete","version":2,"changedAt":"2026-09-14T03:00:00Z"}],"nextCursor":8,"hasMore":false}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        })
        val api = MoaLogSyncApi(client)

        try {
            val response = api.pull(householdId, cursor = 7, limit = 50)

            assertEquals(8, response.nextCursor)
            assertEquals("7", captured?.url?.parameters?.get("cursor"))
            assertEquals("50", captured?.url?.parameters?.get("limit"))
            assertFailsWith<IllegalArgumentException> { api.pull(householdId, -1) }
            assertFailsWith<IllegalArgumentException> { api.pull(householdId, 0, 501) }
        } finally {
            client.close()
        }
    }

    private fun authenticatedClient(engine: MockEngine) = createMoaLogHttpClient(
        engine = engine,
        config = MoaLogNetworkConfig("https://api.moalog.test"),
        tokenProvider = object : MoaLogTokenProvider {
            override suspend fun loadTokens() = MoaLogNetworkTokens("access", "refresh")
            override suspend fun refreshTokens(): MoaLogNetworkTokens? = null
        },
    )

    private suspend fun HttpRequestData.bodyText(): String = when (val content = body) {
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        is OutgoingContent.ReadChannelContent -> content.readFrom().readRemaining().readText()
        else -> error("Unexpected request body: ${content::class}")
    }

    private companion object {
        val householdId = UuidString("10000000-0000-4000-8000-000000000001")
        val mutationId = UuidString("20000000-0000-4000-8000-000000000002")
        val entityId = UuidString("30000000-0000-4000-8000-000000000003")
        val deviceId = UuidString("40000000-0000-4000-8000-000000000004")
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
