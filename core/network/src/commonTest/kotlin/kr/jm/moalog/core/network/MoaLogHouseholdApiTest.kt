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
import kr.jm.moalog.core.contracts.AnnualSavingsTargetDto
import kr.jm.moalog.core.contracts.HouseholdCreateRequest
import kr.jm.moalog.core.contracts.HouseholdUpdateRequest
import kr.jm.moalog.core.contracts.LedgerMemberInputDto
import kr.jm.moalog.core.contracts.UuidString
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoaLogHouseholdApiTest {
    @Test
    fun `household CRUD uses authenticated endpoints and preserves zero savings target`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            val content = if (request.method == HttpMethod.Get && request.url.encodedPath == "/api/households") {
                "[$summaryJson]"
            } else {
                detailJson
            }
            respond(content, HttpStatusCode.OK, jsonHeaders)
        }
        val client = authenticatedClient(engine)
        val api = MoaLogHouseholdApi(client)
        val create = HouseholdCreateRequest(
            name = "우리집",
            baseYear = 2026,
            creatorMemberOrder = 0,
            members = listOf(LedgerMemberInputDto("A", 0), LedgerMemberInputDto("B", 1)),
            annualSavingsTargets = listOf(AnnualSavingsTargetDto(2026, 0)),
        )

        try {
            api.create(create)
            assertEquals(1, api.list().size)
            api.get(householdId)
            api.update(householdId, HouseholdUpdateRequest(baseVersion = 1, name = "새 이름"))

            assertEquals(
                listOf(HttpMethod.Post, HttpMethod.Get, HttpMethod.Get, HttpMethod.Patch),
                requests.map(HttpRequestData::method),
            )
            assertEquals(
                listOf(
                    "/api/households",
                    "/api/households",
                    "/api/households/${householdId.value}",
                    "/api/households/${householdId.value}",
                ),
                requests.map { it.url.encodedPath },
            )
            assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer access" })
            assertTrue(requests[0].bodyText().contains("\"amountWon\":0"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `invitation creation acceptance and revocation use typed contracts`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when {
                request.method == HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                request.url.encodedPath.endsWith("/invitations") ->
                    respond(invitationJson, HttpStatusCode.Created, jsonHeaders)
                else -> respond(detailJson, HttpStatusCode.OK, jsonHeaders)
            }
        }
        val client = authenticatedClient(engine)
        val api = MoaLogHouseholdApi(client)

        try {
            val invitation = api.createInvitation(householdId, targetMemberId)
            val accepted = api.acceptInvitation(invitation.invitationToken)
            api.revokeInvitation(householdId, invitationId)

            assertEquals("invitation-secret", invitation.invitationToken)
            assertEquals(householdId, accepted.householdId)
            assertEquals(
                listOf(
                    "/api/households/${householdId.value}/invitations",
                    "/api/household-invitations/accept",
                    "/api/households/${householdId.value}/invitations/${invitationId.value}",
                ),
                requests.map { it.url.encodedPath },
            )
            assertTrue(requests[1].bodyText().contains("\"invitationToken\":\"invitation-secret\""))
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
        val targetMemberId = UuidString("20000000-0000-4000-8000-000000000002")
        val invitationId = UuidString("30000000-0000-4000-8000-000000000003")
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
        const val summaryJson =
            """{"householdId":"10000000-0000-4000-8000-000000000001","name":"우리집","currency":"KRW","baseYear":2026,"currentUserRole":"owner","updatedAt":"2026-09-14T01:00:00Z"}"""
        const val detailJson =
            """{"householdId":"10000000-0000-4000-8000-000000000001","name":"우리집","currency":"KRW","baseYear":2026,"currentUserRole":"owner","members":[{"memberId":"20000000-0000-4000-8000-000000000002","displayName":"A","order":0,"linkedUserId":"40000000-0000-4000-8000-000000000004"},{"memberId":"20000000-0000-4000-8000-000000000005","displayName":"B","order":1,"linkedUserId":null}],"annualSavingsTargets":[{"year":2026,"amountWon":0}],"version":1,"updatedAt":"2026-09-14T01:00:00Z"}"""
        const val invitationJson =
            """{"invitation":{"invitationId":"30000000-0000-4000-8000-000000000003","householdId":"10000000-0000-4000-8000-000000000001","targetMemberId":"20000000-0000-4000-8000-000000000002","status":"active","expiresAt":"2026-09-15T01:00:00Z"},"invitationToken":"invitation-secret"}"""
    }
}
