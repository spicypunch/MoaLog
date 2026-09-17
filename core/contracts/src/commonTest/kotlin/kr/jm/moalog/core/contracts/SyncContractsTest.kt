package kr.jm.moalog.core.contracts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SyncContractsTest {
    private val json = Json { encodeDefaults = true }
    private val mutationId = UuidString("123e4567-e89b-12d3-a456-426614174000")
    private val entityId = UuidString("123e4567-e89b-12d3-a456-426614174001")
    private val deviceId = UuidString("123e4567-e89b-12d3-a456-426614174002")

    @Test
    fun push_round_trips_nullable_money_without_collapsing_zero() {
        val missingAmount = SyncMutationDto(
            mutationId = mutationId,
            entityType = SyncEntityType.MONTHLY_PLAN_ITEM,
            entityId = entityId,
            operation = SyncOperation.UPSERT,
            baseVersion = null,
            payload = buildJsonObject { put("amountWon", JsonNull) },
        )
        val zeroAmount = missingAmount.copy(
            mutationId = UuidString("123e4567-e89b-12d3-a456-426614174003"),
            payload = buildJsonObject { put("amountWon", 0L) },
        )
        val request = SyncPushRequest(deviceId, listOf(missingAmount, zeroAmount))

        val decoded = json.decodeFromString<SyncPushRequest>(json.encodeToString(request))

        assertEquals(JsonNull, decoded.mutations[0].payload?.get("amountWon"))
        assertEquals("0", decoded.mutations[1].payload?.get("amountWon").toString())
    }

    @Test
    fun delete_rejects_payload_and_upsert_requires_it() {
        assertFailsWith<IllegalArgumentException> {
            SyncMutationDto(
                mutationId,
                SyncEntityType.EXPENSE_RECORD,
                entityId,
                SyncOperation.DELETE,
                baseVersion = 1,
                payload = buildJsonObject { put("amountWon", 1L) },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SyncMutationDto(
                mutationId,
                SyncEntityType.EXPENSE_RECORD,
                entityId,
                SyncOperation.UPSERT,
                baseVersion = null,
                payload = null,
            )
        }
    }

    @Test
    fun pull_round_trips_ordered_changes_and_delete_tombstone() {
        val response = SyncPullResponse(
            changes = listOf(
                SyncChangeDto(
                    cursor = 7,
                    entityType = SyncEntityType.EXPENSE_RECORD,
                    entityId = entityId,
                    operation = SyncOperation.DELETE,
                    version = 3,
                    changedAt = UtcInstantString("2026-09-14T12:34:56Z"),
                ),
            ),
            nextCursor = 7,
            hasMore = false,
        )

        val decoded = json.decodeFromString<SyncPullResponse>(json.encodeToString(response))

        assertEquals(response, decoded)
        assertNull(decoded.changes.single().payload)
    }

    @Test
    fun pull_rejects_unordered_changes() {
        val first = SyncChangeDto(
            cursor = 2,
            entityType = SyncEntityType.ASSET,
            entityId = entityId,
            operation = SyncOperation.DELETE,
            version = 1,
            changedAt = UtcInstantString("2026-09-14T12:34:56Z"),
        )

        assertFailsWith<IllegalArgumentException> {
            SyncPullResponse(listOf(first, first.copy(cursor = 1)), nextCursor = 2, hasMore = false)
        }
    }

    @Test
    fun push_rejects_a_batch_above_the_transport_limit() {
        val mutations = List(MAX_SYNC_MUTATIONS_PER_PUSH + 1) { index ->
            SyncMutationDto(
                mutationId = indexedUuid(index + 100),
                entityType = SyncEntityType.EXPENSE_RECORD,
                entityId = indexedUuid(index + 1_000),
                operation = SyncOperation.DELETE,
                baseVersion = 1,
            )
        }

        assertFailsWith<IllegalArgumentException> { SyncPushRequest(deviceId, mutations) }
    }

    @Test
    fun upsert_rejects_a_payload_above_the_transport_limit() {
        assertFailsWith<IllegalArgumentException> {
            SyncMutationDto(
                mutationId = mutationId,
                entityType = SyncEntityType.EXPENSE_RECORD,
                entityId = entityId,
                operation = SyncOperation.UPSERT,
                baseVersion = null,
                payload = buildJsonObject { put("data", "가".repeat(MAX_SYNC_PAYLOAD_BYTES)) },
            )
        }
    }

    private fun indexedUuid(value: Int): UuidString =
        UuidString("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")
}
