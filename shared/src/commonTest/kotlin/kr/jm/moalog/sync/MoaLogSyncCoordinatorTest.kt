package kr.jm.moalog.sync

import kr.jm.moalog.core.contracts.AppliedMutationDto
import kr.jm.moalog.core.contracts.ApiProblemDto
import kr.jm.moalog.core.contracts.SyncChangeDto
import kr.jm.moalog.core.contracts.SyncEntityType
import kr.jm.moalog.core.contracts.SyncMutationDto
import kr.jm.moalog.core.contracts.SyncOperation
import kr.jm.moalog.core.contracts.SyncPullResponse
import kr.jm.moalog.core.contracts.SyncPushRequest
import kr.jm.moalog.core.contracts.SyncPushResponse
import kr.jm.moalog.core.contracts.UuidString
import kr.jm.moalog.core.database.PendingHouseholdSettings
import kr.jm.moalog.core.database.PersistentSyncMutation
import kr.jm.moalog.core.database.SyncLocalStore
import kr.jm.moalog.core.database.SyncUuidFactory
import kr.jm.moalog.core.network.MoaLogApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MoaLogSyncCoordinatorTest {
    private val household = uuid(1)
    private val device = uuid(2)

    @Test
    fun missingSessionStopsBeforeTouchingDatabaseOrNetwork() = runTest {
        val local = FakeLocal()
        val remote = FakeRemote()
        val coordinator = coordinator(local, remote, context = null)

        assertEquals(SyncRunResult.SkippedNoSession, coordinator.synchronize())
        assertEquals(0, local.calls)
        assertEquals(0, remote.calls)
    }

    @Test
    fun pushesDependenciesInServerSafeOrderThenPullsEveryPage() = runTest {
        val parent = mutation(3, SyncEntityType.PLAN_CATALOG_ITEM, SyncOperation.UPSERT)
        val child = mutation(4, SyncEntityType.MONTHLY_PLAN_ITEM, SyncOperation.UPSERT)
        val deleteParent = mutation(5, SyncEntityType.ASSET, SyncOperation.DELETE)
        val deleteChild = mutation(6, SyncEntityType.ASSET_VALUATION, SyncOperation.DELETE)
        val local = FakeLocal(mutableListOf(child, deleteParent, parent, deleteChild))
        val remote = FakeRemote(
            pullPages = ArrayDeque(
                listOf(
                    SyncPullResponse(emptyList(), 7, true),
                    SyncPullResponse(emptyList(), 9, false),
                ),
            ),
        )
        // A non-final page must carry progress; use one lightweight change page in the actual run.
        remote.pullPages.clear()
        remote.pullPages += SyncPullResponse(listOf(change(7)), 7, true)
        remote.pullPages += SyncPullResponse(listOf(change(9)), 9, false)

        val result = coordinator(local, remote).synchronize()

        assertEquals(
            listOf(deleteChild.mutation.entityType, deleteParent.mutation.entityType, parent.mutation.entityType, child.mutation.entityType),
            remote.pushed.single().mutations.map { it.entityType },
        )
        assertEquals(listOf(0L, 7L), remote.pullCursors)
        assertEquals(listOf(7L, 9L), local.appliedCursors)
        assertEquals(SyncRunResult.Completed(4, 2, 9), result)
    }

    @Test
    fun failedPushKeepsMutationAndPersistsBackoffState() = runTest {
        val pending = mutation(3, SyncEntityType.EXPENSE_CATEGORY, SyncOperation.UPSERT, attempt = 2)
        val local = FakeLocal(mutableListOf(pending))
        val remote = FakeRemote(pushFailure = RuntimeException("offline"))

        val result = coordinator(local, remote, now = 10_000).synchronize()

        assertIs<SyncRunResult.RetryScheduled>(result)
        assertEquals(14_000, result.retryAtEpochMillis)
        assertEquals(listOf(pending.mutationId), local.failedIds)
        assertEquals("offline", local.failedReason)
        assertTrue(local.pending.isNotEmpty())
    }

    @Test
    fun versionConflictRecoveryCanRecoverFromOldCursorStateAndSucceed() = runTest {
        val pending = mutation(3, SyncEntityType.EXPENSE_CATEGORY, SyncOperation.UPSERT)
        val local = FakeLocal(mutableListOf(pending))
        val conflict = MoaLogApiException(
            status = 409,
            problem = ApiProblemDto(
                type = "urn:moalog:problem:sync-version-conflict",
                title = "sync-version-conflict",
                status = 409,
                detail = null,
            ),
        )
        val remote = FakeRemote(
            pushFailureSequence = listOf(conflict, conflict),
            expectedPushBaseVersions = listOf(null, 7L, 8L),
            pullPages = ArrayDeque(
                listOf(
                    SyncPullResponse(
                        changes = listOf(change(7, pending.mutation.entityType, pending.mutation.entityId, 7)),
                        nextCursor = 7,
                        hasMore = false,
                    ),
                    SyncPullResponse(
                        changes = listOf(change(8, pending.mutation.entityType, pending.mutation.entityId, 8)),
                        nextCursor = 8,
                        hasMore = false,
                    ),
                    SyncPullResponse(emptyList(), 8, false),
                ),
            ),
        )

        val result = coordinator(local, remote).synchronize()

        assertIs<SyncRunResult.Completed>(result)
        assertEquals(6, remote.calls)
        assertEquals(emptyList(), local.failedIds)
        assertEquals(listOf(7L, 8L, 8L), local.appliedCursors)
        assertEquals(0, local.pending.size)
        assertEquals(3, remote.pushed.size)
    }

    @Test
    fun finalPullRebasesImmediatelyEligibleMutationAndReentersPushCycle() = runTest {
        val pending = mutation(9, SyncEntityType.EXPENSE_CATEGORY, SyncOperation.UPSERT)
        val local = FakeLocal(mutableListOf(pending), holdReadyUntilFirstPull = true)
        val remote = FakeRemote(
            expectedPushBaseVersions = listOf(5L),
            pullPages = ArrayDeque(
                listOf(
                    SyncPullResponse(
                        changes = listOf(change(5, pending.mutation.entityType, pending.mutation.entityId, 5)),
                        nextCursor = 5,
                        hasMore = false,
                    ),
                    SyncPullResponse(emptyList(), 5, false),
                ),
            ),
        )

        val result = coordinator(local, remote).synchronize()

        assertEquals(SyncRunResult.Completed(1, 1, 5), result)
        assertEquals(listOf(0L, 5L), remote.pullCursors)
        assertEquals(listOf(5L), remote.pushed.map { it.mutations.single().baseVersion })
        assertTrue(local.pending.isEmpty())
    }

    @Test
    fun nonRetryableFailureMarksFailedAndReturnsFailureResult() = runTest {
        val pending = mutation(4, SyncEntityType.EXPENSE_CATEGORY, SyncOperation.UPSERT)
        val local = FakeLocal(mutableListOf(pending))
        val remote = FakeRemote(pushFailure = IllegalArgumentException("bad payload"))

        val result = coordinator(local, remote).synchronize()

        assertIs<SyncRunResult.Failed>(result)
        assertEquals(1, remote.calls)
        assertEquals("bad payload", result.reason)
        assertEquals("bad payload", local.failedReason)
        assertEquals(listOf(pending.mutationId), local.failedIds)
    }

    @Test
    fun wrappedSerializationFailureIsPermanentAndRemainsImmediatelyRetryable() = runTest {
        val pending = mutation(4, SyncEntityType.EXPENSE_CATEGORY, SyncOperation.UPSERT)
        val local = FakeLocal(mutableListOf(pending))
        val remote = FakeRemote(
            pushFailure = RuntimeException("decode failed", SerializationException("bad json")),
        )

        val result = coordinator(local, remote, now = 42_000).synchronize()

        assertIs<SyncRunResult.Failed>(result)
        assertEquals(listOf(pending.mutationId), local.failedIds)
        assertEquals(42_000, local.failedAt)
        assertTrue(local.pending.isNotEmpty())
    }

    @Test
    fun concurrentRequestsAreSerialized() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val local = FakeLocal()
        val remote = FakeRemote(onPull = {
            entered.complete(Unit)
            release.await()
        })
        val coordinator = coordinator(local, remote)

        val first = async { coordinator.synchronize() }
        entered.await()
        val second = async { coordinator.synchronize() }
        assertEquals(1, remote.maxConcurrentCalls)
        release.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, remote.maxConcurrentCalls)
        assertEquals(2, remote.pullCursors.size)
    }

    private fun coordinator(
        local: FakeLocal,
        remote: FakeRemote,
        context: SyncSessionContext? = SyncSessionContext(household, device),
        now: Long = 1_000,
    ) = MoaLogSyncCoordinator(
        local,
        remote,
        SyncSessionContextProvider { context },
        SyncClock { now },
        SyncUuidFactory { uuid(99) },
    )

    private fun mutation(
        number: Int,
        type: SyncEntityType,
        operation: SyncOperation,
        attempt: Int = 0,
    ): PersistentSyncMutation {
        val mutationId = uuid(number)
        val payload = if (operation == SyncOperation.UPSERT) buildJsonObject { put("value", number) } else null
        return PersistentSyncMutation(
            mutationId, household, device,
            SyncMutationDto(
                mutationId,
                type,
                uuid(number + 100),
                operation,
                if (operation == SyncOperation.DELETE) 1 else null,
                payload,
            ),
            attempt,
        )
    }

    private fun change(
        cursor: Long,
        entityType: SyncEntityType = SyncEntityType.EXPENSE_CATEGORY,
        entityId: UuidString = uuid(cursor.toInt() + 200),
        version: Long = 1,
    ) = SyncChangeDto(
        cursor,
        entityType,
        entityId,
        SyncOperation.DELETE,
        version,
        null,
        kr.jm.moalog.core.contracts.UtcInstantString("2026-09-14T00:00:00Z"),
    )

    private class FakeLocal(
        val pending: MutableList<PersistentSyncMutation> = mutableListOf(),
        private val holdReadyUntilFirstPull: Boolean = false,
    ) : SyncLocalStore {
        var calls = 0
        val appliedCursors = mutableListOf<Long>()
        var failedIds = emptyList<UuidString>()
        var failedReason = ""
        var failedAt: Long? = null
        var pendingSettings: PendingHouseholdSettings? = null
        private var cursor = 0L
        private var pulledAtLeastOnce = false

        override suspend fun persistentDeviceId(uuidFactory: SyncUuidFactory) = deviceForFake
        override suspend fun requirePersistentDeviceId(deviceId: UuidString) { calls++ }
        override suspend fun boundHouseholdId(): UuidString? = null
        override suspend fun bindHousehold(householdId: UuidString) { calls++ }
        override suspend fun pendingHouseholdSettings(): PendingHouseholdSettings? = pendingSettings
        override suspend fun markHouseholdSettingsPending(householdId: UuidString, baseVersion: Long) {
            pendingSettings = PendingHouseholdSettings(householdId, baseVersion)
        }

        override suspend fun clearPendingHouseholdSettings(householdId: UuidString) {
            if (pendingSettings?.householdId == householdId) {
                pendingSettings = null
            }
        }

        override suspend fun resetCloudBinding() {
            pendingSettings = null
        }

        override suspend fun captureLocalChanges(
            householdId: UuidString,
            deviceId: UuidString,
            nowEpochMillis: Long,
            uuidFactory: SyncUuidFactory,
        ) {
            calls++
        }

        override suspend fun readyMutations(householdId: UuidString, nowEpochMillis: Long, limit: Int) =
            if (holdReadyUntilFirstPull && !pulledAtLeastOnce) emptyList() else pending.take(limit)

        override suspend fun nextPendingRetryAt(householdId: UuidString): Long? =
            pending.takeIf { it.isNotEmpty() }?.let { 0L }

        override fun observeLocalChanges(): Flow<Unit> = flowOf(Unit)

        override suspend fun acknowledgePush(householdId: UuidString, acknowledgements: List<AppliedMutationDto>) {
            val ids = acknowledgements.map { it.mutationId }.toSet()
            pending.removeAll { it.mutationId in ids }
        }

        override suspend fun markPushFailed(
            mutationIds: List<UuidString>,
            nextAttemptAtEpochMillis: Long,
            error: String,
        ) {
            failedIds = mutationIds
            failedReason = error
            failedAt = nextAttemptAtEpochMillis
            calls++
        }

        override suspend fun pullCursor(householdId: UuidString) = cursor

        override suspend fun applyPulledChanges(
            householdId: UuidString,
            changes: List<SyncChangeDto>,
            nextCursor: Long,
            resolvePendingConflicts: Boolean,
        ) {
            cursor = nextCursor
            appliedCursors += nextCursor
            if (resolvePendingConflicts) {
                changes.forEach { change ->
                    val index = pending.indexOfFirst {
                        it.mutation.entityType == change.entityType &&
                            it.mutation.entityId == change.entityId
                    }
                    if (index >= 0) {
                        val current = pending[index]
                        pending[index] = current.copy(
                            mutation = current.mutation.copy(baseVersion = change.version),
                        )
                    }
                }
            }
            pulledAtLeastOnce = true
        }
    }

    private class FakeRemote(
        val pullPages: ArrayDeque<SyncPullResponse> = ArrayDeque(
            listOf(SyncPullResponse(emptyList(), 0, false)),
        ),
        private val pushFailure: Throwable? = null,
        private val pushFailureSequence: List<Throwable> = emptyList(),
        private val expectedPushBaseVersions: List<Long?> = emptyList(),
        private val onPull: suspend () -> Unit = {},
    ) : SyncRemoteGateway {
        var calls = 0
        val pushed = mutableListOf<SyncPushRequest>()
        val pullCursors = mutableListOf<Long>()
        var concurrentCalls = 0
        var maxConcurrentCalls = 0
        private var pushFailureIndex = 0
        private val failures = buildList {
            addAll(pushFailureSequence)
            if (pushFailure != null) {
                add(pushFailure)
            }
        }

        override suspend fun push(householdId: UuidString, request: SyncPushRequest): SyncPushResponse {
            calls++
            pushed += request
            if (expectedPushBaseVersions.isNotEmpty()) {
                assertEquals(
                    expectedPushBaseVersions[pushed.lastIndex],
                    request.mutations.single().baseVersion,
                )
            }
            if (pushFailureIndex < failures.size) {
                throw failures[pushFailureIndex++]
            }
            return SyncPushResponse(
                request.mutations.mapIndexed { index, mutation ->
                    AppliedMutationDto(mutation.mutationId, mutation.entityId, index + 1L)
                },
                50,
            )
        }

        override suspend fun pull(householdId: UuidString, cursor: Long): SyncPullResponse {
            calls++
            pullCursors += cursor
            concurrentCalls++
            maxConcurrentCalls = maxOf(maxConcurrentCalls, concurrentCalls)
            return try {
                onPull()
                if (pullPages.size > 1) pullPages.removeFirst() else pullPages.first()
            } finally {
                concurrentCalls--
            }
        }
    }
}

private val deviceForFake = uuid(2)
private fun uuid(number: Int): UuidString = UuidString("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
