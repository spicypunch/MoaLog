package kr.jm.moalog.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kr.jm.moalog.core.contracts.DEFAULT_SYNC_PULL_LIMIT
import kr.jm.moalog.core.contracts.MAX_SYNC_PULL_LIMIT
import kr.jm.moalog.core.contracts.SyncPullResponse
import kr.jm.moalog.core.contracts.SyncPushRequest
import kr.jm.moalog.core.contracts.SyncPushResponse
import kr.jm.moalog.core.contracts.UuidString

class MoaLogSyncApi(
    private val authenticatedClient: HttpClient,
) {
    suspend fun push(householdId: UuidString, request: SyncPushRequest): SyncPushResponse =
        authenticatedClient.post(syncPath(householdId, "push")) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.bodyOrThrow()

    suspend fun pull(
        householdId: UuidString,
        cursor: Long,
        limit: Int = DEFAULT_SYNC_PULL_LIMIT,
    ): SyncPullResponse {
        require(cursor >= 0) { "cursor must be non-negative" }
        require(limit in 1..MAX_SYNC_PULL_LIMIT) { "limit must be between 1 and $MAX_SYNC_PULL_LIMIT" }
        return authenticatedClient.get(syncPath(householdId, "pull")) {
            parameter("cursor", cursor)
            parameter("limit", limit)
        }.bodyOrThrow()
    }

    private fun syncPath(householdId: UuidString, operation: String): String =
        "api/households/${householdId.value}/sync/$operation"
}
