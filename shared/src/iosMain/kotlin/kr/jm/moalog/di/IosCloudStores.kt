package kr.jm.moalog.di

import kr.jm.moalog.auth.AuthSessionState
import kr.jm.moalog.auth.MoaLogAuthSessionManager
import kr.jm.moalog.core.contracts.AuthChallengeResponse
import kr.jm.moalog.core.contracts.AuthProvider
import kr.jm.moalog.core.contracts.UuidString
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.household.HouseholdSessionState
import kr.jm.moalog.household.MoaLogHouseholdSessionManager
import kr.jm.moalog.sync.MoaLogSyncManager
import kr.jm.moalog.sync.SyncRunResult
import kr.jm.moalog.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class IosAuthStore internal constructor(private val manager: MoaLogAuthSessionManager) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    val currentState: AuthSessionState get() = manager.state.value

    fun observe(observer: (AuthSessionState) -> Unit): IosObservation =
        IosObservation(scope.launch { manager.state.collect(observer) })

    suspend fun restore() = manager.restore()
    suspend fun beginSignIn(): AuthChallengeResponse = manager.beginSignIn()
    suspend fun completeAppleSignIn(challenge: AuthChallengeResponse, idToken: String) =
        manager.completeSignIn(AuthProvider.APPLE, challenge, idToken)
    suspend fun cancelSignIn() = manager.cancelSignIn()
    suspend fun signOut() = manager.signOut()
    suspend fun deleteAccount() = manager.deleteAccount()

    /**
     * Swift calls these launch helpers instead of the exported suspend methods. Kotlin/Native only
     * converts cancellation failures to NSError for suspend exports, so ordinary network failures
     * must be caught on the Kotlin side before they cross the Objective-C boundary.
     */
    fun beginSignInSafely(completion: (AuthChallengeResponse?, String?) -> Unit) {
        scope.launch {
            runCatchingPreservingCancellation { manager.beginSignIn() }
                .fold(
                    onSuccess = { completion(it, null) },
                    onFailure = { completion(null, it.userMessage("로그인을 시작하지 못했어요")) },
                )
        }
    }

    fun completeAppleSignInSafely(
        challenge: AuthChallengeResponse,
        idToken: String,
        completion: (String?) -> Unit,
    ) {
        scope.launch {
            runCatchingPreservingCancellation {
                manager.completeSignIn(AuthProvider.APPLE, challenge, idToken)
            }.fold(
                onSuccess = { completion(null) },
                onFailure = { completion(it.userMessage("Apple 로그인에 실패했어요")) },
            )
        }
    }

    fun cancelSignInSafely() {
        scope.launch { manager.cancelSignIn() }
    }

    fun restoreSafely(completion: (String?) -> Unit) {
        scope.launch {
            runCatchingPreservingCancellation { manager.restore() }
                .fold(
                    onSuccess = { completion(null) },
                    onFailure = { completion(it.userMessage("로그인 정보를 불러오지 못했어요")) },
                )
        }
    }

    fun signOutSafely(completion: (String?) -> Unit) {
        scope.launch {
            runCatchingPreservingCancellation { manager.signOut() }
                .fold(
                    onSuccess = { completion(null) },
                    onFailure = { completion(it.userMessage("로그아웃하지 못했어요")) },
                )
        }
    }

    fun deleteAccountSafely(completion: (String?) -> Unit) {
        scope.launch {
            runCatchingPreservingCancellation { manager.deleteAccount() }
                .fold(
                    onSuccess = { completion(null) },
                    onFailure = { completion(it.userMessage("계정을 삭제하지 못했어요")) },
                )
        }
    }

    fun close() = job.cancel()
}

class IosHouseholdStore internal constructor(private val manager: MoaLogHouseholdSessionManager) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    val currentState: HouseholdSessionState get() = manager.state.value

    fun observe(observer: (HouseholdSessionState) -> Unit): IosObservation =
        IosObservation(scope.launch { manager.state.collect(observer) })

    suspend fun restoreAuthenticated() = manager.restore(kr.jm.moalog.auth.AuthSessionStatus.SIGNED_IN)
    suspend fun resetSignedOut() = manager.restore(kr.jm.moalog.auth.AuthSessionStatus.SIGNED_OUT)
    suspend fun refresh() = manager.refresh()
    suspend fun createFromLocal(setup: LedgerSetup, creatorMemberOrder: Int) =
        manager.createFromLocal(setup, creatorMemberOrder)
    suspend fun connectExisting(householdId: String) = manager.connectExisting(UuidString(householdId))
    suspend fun acceptInvitation(token: String) = manager.acceptInvitation(token)
    suspend fun createInvitation(targetMemberOrder: Int) = manager.createInvitation(targetMemberOrder)
    suspend fun revokeInvitation() = manager.revokeInvitation()
    suspend fun clearInvitation() = manager.clearInvitation()
    suspend fun resetLocalCloudConnection() = manager.resetLocalCloudConnection()

    fun restoreAuthenticatedSafely(completion: (String?) -> Unit) {
        scope.launch {
            runCatchingPreservingCancellation {
                manager.restore(kr.jm.moalog.auth.AuthSessionStatus.SIGNED_IN)
            }.fold(
                onSuccess = { completion(null) },
                onFailure = { completion(it.userMessage("가계부를 불러오지 못했어요")) },
            )
        }
    }

    fun resetSignedOutSafely() {
        scope.launch { manager.restore(kr.jm.moalog.auth.AuthSessionStatus.SIGNED_OUT) }
    }

    fun refreshSafely(completion: (String?) -> Unit) {
        scope.launch {
            runCatchingPreservingCancellation { manager.refresh() }
                .fold(
                    onSuccess = { completion(null) },
                    onFailure = { completion(it.userMessage("가계부를 다시 불러오지 못했어요")) },
                )
        }
    }

    fun createFromLocalSafely(
        setup: LedgerSetup,
        creatorMemberOrder: Int,
        completion: (String?) -> Unit,
    ) {
        scope.launch {
            runCatchingPreservingCancellation { manager.createFromLocal(setup, creatorMemberOrder) }
                .fold(
                    onSuccess = { completion(null) },
                    onFailure = { completion(it.userMessage("새 가계부를 만들지 못했어요")) },
                )
        }
    }

    fun connectExistingSafely(
        household: kr.jm.moalog.core.contracts.HouseholdSummaryDto,
        completion: (String?) -> Unit,
    ) {
        scope.launch {
            runCatchingPreservingCancellation { manager.connectExisting(household.householdId) }
                .fold(
                    onSuccess = { completion(null) },
                    onFailure = { completion(it.userMessage("가계부를 연결하지 못했어요")) },
                )
        }
    }

    fun acceptInvitationSafely(token: String, completion: (String?) -> Unit) {
        scope.launch {
            runCatchingPreservingCancellation { manager.acceptInvitation(token) }
                .fold(
                    onSuccess = { completion(null) },
                    onFailure = { completion(it.userMessage("초대 코드를 확인하지 못했어요")) },
                )
        }
    }

    fun createInvitationSafely(targetMemberOrder: Int, completion: (String?) -> Unit) {
        scope.launch {
            runCatchingPreservingCancellation { manager.createInvitation(targetMemberOrder) }
                .fold(
                    onSuccess = { completion(null) },
                    onFailure = { completion(it.userMessage("초대 코드를 만들지 못했어요")) },
                )
        }
    }

    fun updateFromLocalSafely(setup: LedgerSetup, completion: (String?) -> Unit) {
        scope.launch {
            runCatchingPreservingCancellation { manager.updateFromLocal(setup) }
                .fold(
                    onSuccess = { completion(null) },
                    onFailure = { completion(it.userMessage("가계부 설정을 동기화하지 못했어요")) },
                )
        }
    }

    fun revokeInvitationSafely(completion: (String?) -> Unit) {
        scope.launch {
            runCatchingPreservingCancellation { manager.revokeInvitation() }
                .fold(
                    onSuccess = { completion(null) },
                    onFailure = { completion(it.userMessage("초대 코드를 취소하지 못했어요")) },
                )
        }
    }

    fun resetLocalCloudConnectionSafely(completion: (String?) -> Unit) {
        scope.launch {
            runCatchingPreservingCancellation { manager.resetLocalCloudConnection() }
                .fold(
                    onSuccess = { completion(null) },
                    onFailure = { completion(it.userMessage("이 기기의 가계부 연결을 초기화하지 못했어요")) },
                )
        }
    }

    fun close() = job.cancel()
}

class IosSyncStore internal constructor(private val manager: MoaLogSyncManager) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    val currentStatus: SyncStatus get() = manager.status.value

    fun observe(observer: (SyncStatus) -> Unit): IosObservation =
        IosObservation(scope.launch { manager.status.collect(observer) })

    suspend fun synchronize(): SyncRunResult = manager.synchronize()

    fun synchronizeSafely(completion: (SyncRunResult?, String?) -> Unit) {
        scope.launch {
            runCatchingPreservingCancellation { manager.synchronize() }
                .fold(
                    onSuccess = { completion(it, null) },
                    onFailure = { completion(null, it.userMessage("동기화하지 못했어요")) },
                )
        }
    }

    fun close() = job.cancel()
}

private suspend inline fun <T> runCatchingPreservingCancellation(
    crossinline block: suspend () -> T,
): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}

private fun Throwable.userMessage(fallback: String): String = message?.takeIf(String::isNotBlank) ?: fallback
