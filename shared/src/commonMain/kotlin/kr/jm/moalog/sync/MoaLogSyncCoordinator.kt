package kr.jm.moalog.sync

import kr.jm.moalog.core.contracts.SyncEntityType
import kr.jm.moalog.core.contracts.SyncOperation
import kr.jm.moalog.core.contracts.SyncPullResponse
import kr.jm.moalog.core.contracts.SyncPushRequest
import kr.jm.moalog.core.contracts.SyncPushResponse
import kr.jm.moalog.core.contracts.UuidString
import kr.jm.moalog.core.database.SyncLocalStore
import kr.jm.moalog.core.database.SyncUuidFactory
import kr.jm.moalog.core.network.MoaLogApiException
import kr.jm.moalog.core.network.MoaLogSyncApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlin.math.min

data class SyncSessionContext(
    val householdId: UuidString,
    val deviceId: UuidString,
)

fun interface SyncSessionContextProvider {
    /** Returns null while logged out, before a household is selected, or while the session is being restored. */
    suspend fun current(): SyncSessionContext?
}

class MutableSyncSessionContextProvider : SyncSessionContextProvider {
    private val mutableContext = MutableStateFlow<SyncSessionContext?>(null)
    val context: StateFlow<SyncSessionContext?> = mutableContext.asStateFlow()

    override suspend fun current(): SyncSessionContext? = mutableContext.value
    fun update(value: SyncSessionContext) { mutableContext.value = value }
    fun clear() { mutableContext.value = null }
}

fun interface SyncClock {
    fun nowEpochMillis(): Long
}

interface SyncRemoteGateway {
    suspend fun push(householdId: UuidString, request: SyncPushRequest): SyncPushResponse
    suspend fun pull(householdId: UuidString, cursor: Long): SyncPullResponse
}

class MoaLogSyncRemoteGateway(private val api: MoaLogSyncApi) : SyncRemoteGateway {
    override suspend fun push(householdId: UuidString, request: SyncPushRequest) = api.push(householdId, request)
    override suspend fun pull(householdId: UuidString, cursor: Long) = api.pull(householdId, cursor)
}

sealed interface SyncRunResult {
    data object SkippedNoSession : SyncRunResult
    data class Completed(val pushedMutations: Int, val pulledChanges: Int, val cursor: Long) : SyncRunResult
    data class RetryScheduled(
        val pushedMutations: Int,
        val pulledChanges: Int,
        val retryAtEpochMillis: Long,
        val reason: String,
    ) : SyncRunResult
    data class Failed(
        val pushedMutations: Int,
        val pulledChanges: Int,
        val reason: String,
    ) : SyncRunResult
}

/** Serializes sync with destructive local-ledger replacement. */
class SyncOperationGuard {
    private val mutex = Mutex()
    suspend fun <T> withExclusive(block: suspend () -> T): T = mutex.withLock { block() }
}

/**
 * Runs one household sync at a time. Local pages are committed before the pull cursor advances,
 * while acknowledged pushes keep their UUID/version pair even when the response is replayed.
 */
class MoaLogSyncCoordinator(
    private val local: SyncLocalStore,
    private val remote: SyncRemoteGateway,
    private val contextProvider: SyncSessionContextProvider,
    private val clock: SyncClock,
    private val uuidFactory: SyncUuidFactory = PlatformSyncUuidFactory,
    private val operationGuard: SyncOperationGuard = SyncOperationGuard(),
) {
    suspend fun synchronize(): SyncRunResult = operationGuard.withExclusive {
        val context = contextProvider.current() ?: return@withExclusive SyncRunResult.SkippedNoSession
        local.requirePersistentDeviceId(context.deviceId)
        local.bindHousehold(context.householdId)

        var pushed = 0
        var pulled = 0
        var activeMutationIds = emptyList<UuidString>()
        var activeAttempt = 0
        var conflictRecoveries = 0
        var postPullRetries = 0
        try {
            syncCycle@ while (true) {
                while (true) {
                    val now = clock.nowEpochMillis()
                    local.captureLocalChanges(context.householdId, context.deviceId, now, uuidFactory)
                    val ready = local.readyMutations(context.householdId, now)
                        .sortedWith(syncMutationComparator)
                    if (ready.isEmpty()) break
                    check(ready.all { it.deviceId == context.deviceId }) {
                        "Pending mutations belong to a different authenticated device"
                    }
                    activeMutationIds = ready.map { it.mutationId }
                    activeAttempt = ready.maxOf { it.attemptCount }
                    val response = try {
                        remote.push(
                            context.householdId,
                            SyncPushRequest(context.deviceId, ready.map { it.mutation }),
                        )
                    } catch (failure: Throwable) {
                        if (failure.isVersionConflict() && conflictRecoveries < MAX_CONFLICT_RECOVERIES) {
                            val recovered = pullAll(
                                householdId = context.householdId,
                                resolvePendingConflicts = true,
                            )
                            check(recovered.changes > 0) {
                                "Server reported a version conflict without a newer remote change"
                            }
                            pulled += recovered.changes
                            conflictRecoveries++
                            activeMutationIds = emptyList()
                            continue
                        }
                        throw failure
                    }
                    check(response.applied.map { it.mutationId }.toSet() == activeMutationIds.toSet()) {
                        "Server acknowledgement did not match the pushed mutation batch"
                    }
                    local.acknowledgePush(context.householdId, response.applied)
                    pushed += ready.size
                    activeMutationIds = emptyList()
                }

                val finalPull = pullAll(context.householdId, resolvePendingConflicts = true)
                pulled += finalPull.changes
                val nextAttempt = local.nextPendingRetryAt(context.householdId)
                    ?: return@withExclusive SyncRunResult.Completed(pushed, pulled, finalPull.cursor)
                if (nextAttempt > clock.nowEpochMillis()) {
                    return@withExclusive SyncRunResult.RetryScheduled(
                        pushedMutations = pushed,
                        pulledChanges = pulled,
                        retryAtEpochMillis = nextAttempt,
                        reason = "대기 중인 변경 사항을 다시 동기화할 예정이에요",
                    )
                }
                if (postPullRetries++ >= MAX_POST_PULL_RETRIES) {
                    return@withExclusive SyncRunResult.Failed(
                        pushedMutations = pushed,
                        pulledChanges = pulled,
                        reason = "충돌을 정리한 변경 사항을 다시 전송하지 못했어요",
                    )
                }
                continue@syncCycle
            }
            error("Unreachable sync cycle")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val reason = failure.message ?: failure::class.simpleName ?: "Sync failed"
            if (failure.isRetryableSyncFailure()) {
                val retryAt = clock.nowEpochMillis() + retryDelayMillis(activeAttempt + 1)
                if (activeMutationIds.isNotEmpty()) {
                    local.markPushFailed(activeMutationIds, retryAt, reason)
                }
                SyncRunResult.RetryScheduled(pushed, pulled, retryAt, reason)
            } else {
                if (activeMutationIds.isNotEmpty()) {
                    // Keep the mutation immediately eligible for an explicit retry after the user
                    // resolves authentication, permission, or invalid-data problems.
                    local.markPushFailed(activeMutationIds, clock.nowEpochMillis(), reason)
                }
                SyncRunResult.Failed(pushed, pulled, reason)
            }
        }
    }

    private suspend fun pullAll(
        householdId: UuidString,
        resolvePendingConflicts: Boolean,
    ): PullProgress {
        var cursor = local.pullCursor(householdId)
        var pulled = 0
        do {
            val page = remote.pull(householdId, cursor)
            check(page.nextCursor >= cursor) { "Server pull cursor moved backwards" }
            local.applyPulledChanges(
                householdId = householdId,
                changes = page.changes,
                nextCursor = page.nextCursor,
                resolvePendingConflicts = resolvePendingConflicts,
            )
            pulled += page.changes.size
            cursor = page.nextCursor
            check(!page.hasMore || page.changes.isNotEmpty()) {
                "Server returned an empty non-final sync page"
            }
        } while (page.hasMore)
        return PullProgress(changes = pulled, cursor = cursor)
    }

    private data class PullProgress(val changes: Int, val cursor: Long)

    private companion object {
        const val MAX_CONFLICT_RECOVERIES = 2
        const val MAX_POST_PULL_RETRIES = 2
    }
}

fun createMoaLogSyncCoordinator(
    local: SyncLocalStore,
    api: MoaLogSyncApi,
    contextProvider: SyncSessionContextProvider,
    clock: SyncClock,
    uuidFactory: SyncUuidFactory = PlatformSyncUuidFactory,
    operationGuard: SyncOperationGuard = SyncOperationGuard(),
): MoaLogSyncCoordinator = MoaLogSyncCoordinator(
    local = local,
    remote = MoaLogSyncRemoteGateway(api),
    contextProvider = contextProvider,
    clock = clock,
    uuidFactory = uuidFactory,
    operationGuard = operationGuard,
)

private val syncMutationComparator = compareBy<kr.jm.moalog.core.database.PersistentSyncMutation> {
    if (it.mutation.operation == SyncOperation.DELETE) 0 else 1
}.thenBy {
    val rank = entityDependencyRank(it.mutation.entityType)
    if (it.mutation.operation == SyncOperation.DELETE) -rank else rank
}.thenBy { it.mutationId.value }

private fun entityDependencyRank(type: SyncEntityType): Int = when (type) {
    SyncEntityType.PLAN_CATALOG_ITEM, SyncEntityType.EXPENSE_CATEGORY,
    SyncEntityType.SALARY_INCOME, SyncEntityType.SALARY_ALLOCATION_CATEGORY,
    SyncEntityType.FIXED_COST_ITEM, SyncEntityType.ASSET, SyncEntityType.MAINTENANCE_FEE_MONTH -> 0
    SyncEntityType.MONTHLY_PLAN_ITEM, SyncEntityType.EXPENSE_RECORD,
    SyncEntityType.SALARY_ALLOCATION_DEDUCTION,
    SyncEntityType.SALARY_ALLOCATION_CHILD, SyncEntityType.ASSET_VALUATION,
    SyncEntityType.ASSET_GROWTH_RULE -> 1
    SyncEntityType.SALARY_ALLOCATION_GRANDCHILD -> 2
    else -> 3
}

internal fun retryDelayMillis(attempt: Int): Long {
    val exponent = min((attempt - 1).coerceAtLeast(0), 8)
    return min(1_000L shl exponent, 5 * 60_000L)
}

private fun Throwable.isVersionConflict(): Boolean =
    this is MoaLogApiException &&
        status == 409 &&
        problem?.type == "urn:moalog:problem:sync-version-conflict"

private fun Throwable.isRetryableSyncFailure(): Boolean {
    if (hasSerializationFailure()) return false
    return when (this) {
        is MoaLogApiException -> status == 408 || status == 429 || status >= 500
        is IllegalArgumentException, is IllegalStateException -> false
        else -> true
    }
}

private fun Throwable.hasSerializationFailure(): Boolean {
    var failure: Throwable? = this
    while (failure != null) {
        if (failure is SerializationException) return true
        failure = failure.cause
    }
    return false
}

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Running : SyncStatus
    data class Finished(val result: SyncRunResult) : SyncStatus
}

class MoaLogSyncManager(
    private val coordinator: MoaLogSyncCoordinator,
    private val scope: CoroutineScope,
    private val clock: SyncClock,
    private val local: SyncLocalStore? = null,
) {
    private val mutableStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()
    private val retryControl = Mutex()
    private val runControl = Mutex()
    private var retryJob: Job? = null

    init {
        local?.let { store ->
            scope.launch {
                store.observeLocalChanges()
                    .conflate()
                    .collect {
                        delay(500L)
                        synchronize()
                    }
            }
        }
    }

    suspend fun synchronize(): SyncRunResult {
        retryControl.withLock {
            retryJob?.cancel()
            retryJob = null
        }
        return runOnce()
    }

    private suspend fun runOnce(): SyncRunResult = runControl.withLock {
        mutableStatus.value = SyncStatus.Running
        try {
            coordinator.synchronize().also { result ->
                mutableStatus.value = SyncStatus.Finished(result)
                if (result is SyncRunResult.RetryScheduled) scheduleRetry(result.retryAtEpochMillis)
            }
        } catch (failure: Throwable) {
            mutableStatus.value = SyncStatus.Idle
            throw failure
        }
    }

    private suspend fun scheduleRetry(retryAtEpochMillis: Long) {
        retryControl.withLock {
            retryJob?.cancel()
            retryJob = scope.launchRetry(retryAtEpochMillis)
        }
    }

    private fun CoroutineScope.launchRetry(retryAtEpochMillis: Long): Job =
        launch {
            delay((retryAtEpochMillis - clock.nowEpochMillis()).coerceAtLeast(0L))
            retryControl.withLock { retryJob = null }
            runOnce()
        }
}

expect object PlatformSyncUuidFactory : SyncUuidFactory
expect object PlatformSyncClock : SyncClock
