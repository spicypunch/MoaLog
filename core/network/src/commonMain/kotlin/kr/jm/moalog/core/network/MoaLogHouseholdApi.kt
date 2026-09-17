package kr.jm.moalog.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kr.jm.moalog.core.contracts.HouseholdCreateRequest
import kr.jm.moalog.core.contracts.HouseholdDetailDto
import kr.jm.moalog.core.contracts.HouseholdInvitationAcceptRequest
import kr.jm.moalog.core.contracts.HouseholdInvitationCreateRequest
import kr.jm.moalog.core.contracts.HouseholdInvitationResponse
import kr.jm.moalog.core.contracts.HouseholdSummaryDto
import kr.jm.moalog.core.contracts.HouseholdUpdateRequest
import kr.jm.moalog.core.contracts.UuidString

class MoaLogHouseholdApi(
    private val authenticatedClient: HttpClient,
) {
    suspend fun create(request: HouseholdCreateRequest): HouseholdDetailDto =
        authenticatedClient.post(HOUSEHOLDS_PATH) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.bodyOrThrow()

    suspend fun list(): List<HouseholdSummaryDto> =
        authenticatedClient.get(HOUSEHOLDS_PATH).bodyOrThrow()

    suspend fun get(householdId: UuidString): HouseholdDetailDto =
        authenticatedClient.get("$HOUSEHOLDS_PATH/${householdId.value}").bodyOrThrow()

    suspend fun update(
        householdId: UuidString,
        request: HouseholdUpdateRequest,
    ): HouseholdDetailDto = authenticatedClient.patch("$HOUSEHOLDS_PATH/${householdId.value}") {
        contentType(ContentType.Application.Json)
        setBody(request)
    }.bodyOrThrow()

    suspend fun createInvitation(
        householdId: UuidString,
        targetMemberId: UuidString,
    ): HouseholdInvitationResponse =
        authenticatedClient.post("$HOUSEHOLDS_PATH/${householdId.value}/invitations") {
            contentType(ContentType.Application.Json)
            setBody(HouseholdInvitationCreateRequest(targetMemberId))
        }.bodyOrThrow()

    suspend fun revokeInvitation(householdId: UuidString, invitationId: UuidString) {
        authenticatedClient.delete(
            "$HOUSEHOLDS_PATH/${householdId.value}/invitations/${invitationId.value}",
        ).throwIfError()
    }

    suspend fun acceptInvitation(invitationToken: String): HouseholdDetailDto =
        authenticatedClient.post(INVITATION_ACCEPT_PATH) {
            contentType(ContentType.Application.Json)
            setBody(HouseholdInvitationAcceptRequest(invitationToken))
        }.bodyOrThrow()

    private companion object {
        const val HOUSEHOLDS_PATH = "api/households"
        const val INVITATION_ACCEPT_PATH = "api/household-invitations/accept"
    }
}
