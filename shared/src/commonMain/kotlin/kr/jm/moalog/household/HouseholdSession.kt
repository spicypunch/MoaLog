package kr.jm.moalog.household

import kr.jm.moalog.auth.AuthSessionStatus
import kr.jm.moalog.auth.MoaLogAuthSessionManager
import kr.jm.moalog.core.contracts.AnnualSavingsTargetDto
import kr.jm.moalog.core.contracts.HouseholdCreateRequest
import kr.jm.moalog.core.contracts.HouseholdDetailDto
import kr.jm.moalog.core.contracts.HouseholdInvitationResponse
import kr.jm.moalog.core.contracts.HouseholdSummaryDto
import kr.jm.moalog.core.contracts.HouseholdUpdateRequest
import kr.jm.moalog.core.contracts.LedgerMemberInputDto
import kr.jm.moalog.core.contracts.LedgerMemberUpdateDto
import kr.jm.moalog.core.contracts.UuidString
import kr.jm.moalog.core.database.SyncLocalStore
import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.core.network.MoaLogHouseholdApi
import kr.jm.moalog.core.network.MoaLogApiException
import kr.jm.moalog.feature.setup.domain.SetupRepository
import kr.jm.moalog.sync.SyncSessionContext
import kr.jm.moalog.sync.SyncSessionContextProvider
import kr.jm.moalog.sync.SyncOperationGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class HouseholdSessionStatus { IDLE, LOADING, NEEDS_CONNECTION, READY, FAILED }

data class HouseholdSessionState(
    val status: HouseholdSessionStatus = HouseholdSessionStatus.IDLE,
    val availableHouseholds: List<HouseholdSummaryDto> = emptyList(),
    val activeHousehold: HouseholdDetailDto? = null,
    val invitation: HouseholdInvitationResponse? = null,
    val errorMessage: String? = null,
    val requiresLocalReconnection: Boolean = false,
)

/** Coordinates the one local ledger with one server household. */
class MoaLogHouseholdSessionManager(
    private val api: MoaLogHouseholdApi,
    private val setupRepository: SetupRepository,
    private val syncLocalStore: SyncLocalStore,
    private val syncOperationGuard: SyncOperationGuard = SyncOperationGuard(),
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(HouseholdSessionState())
    val state: StateFlow<HouseholdSessionState> = mutableState.asStateFlow()

    suspend fun restore(authStatus: AuthSessionStatus) = mutex.withLock {
        if (authStatus != AuthSessionStatus.SIGNED_IN) {
            mutableState.value = HouseholdSessionState()
            return@withLock
        }
        loadHouseholds()
    }

    suspend fun refresh() = mutex.withLock { loadHouseholds() }

    suspend fun createFromLocal(setup: LedgerSetup, creatorMemberOrder: Int) = mutex.withLock {
        require(creatorMemberOrder in 0..1)
        mutableState.value = mutableState.value.copy(
            status = HouseholdSessionStatus.LOADING,
            errorMessage = null,
            invitation = null,
        )
        perform {
            val detail = api.create(setup.createRequest(creatorMemberOrder))
            syncLocalStore.bindHousehold(detail.householdId)
            mutableState.value = HouseholdSessionState(
                status = HouseholdSessionStatus.READY,
                availableHouseholds = listOf(detail.summary()),
                activeHousehold = detail,
            )
        }
    }

    suspend fun connectExisting(householdId: UuidString) = mutex.withLock {
        mutableState.value = mutableState.value.copy(
            status = HouseholdSessionStatus.LOADING,
            errorMessage = null,
            invitation = null,
        )
        perform {
            val detail = api.get(householdId)
            syncLocalStore.bindHousehold(detail.householdId)
            setupRepository.saveSetup(detail.localSetup())
            mutableState.value = HouseholdSessionState(
                status = HouseholdSessionStatus.READY,
                availableHouseholds = listOf(detail.summary()),
                activeHousehold = detail,
            )
        }
    }

    suspend fun acceptInvitation(invitationToken: String) = mutex.withLock {
        require(invitationToken.isNotBlank())
        mutableState.value = mutableState.value.copy(
            status = HouseholdSessionStatus.LOADING,
            errorMessage = null,
            invitation = null,
        )
        perform {
            val detail = api.acceptInvitation(invitationToken.trim())
            syncLocalStore.bindHousehold(detail.householdId)
            setupRepository.saveSetup(detail.localSetup())
            mutableState.value = HouseholdSessionState(
                status = HouseholdSessionStatus.READY,
                availableHouseholds = listOf(detail.summary()),
                activeHousehold = detail,
            )
        }
    }

    suspend fun updateFromLocal(setup: LedgerSetup) = mutex.withLock {
        val current = mutableState.value.activeHousehold
        if (current == null) {
            // Cold-start offline mode has a durable household binding but no remote detail/version.
            // Record intent before returning so the next successful refresh uploads this local
            // setup instead of replacing it with the older server copy.
            val bound = syncLocalStore.boundHouseholdId()
                ?: error("연결된 가계부를 찾을 수 없어요")
            val baseVersion = syncLocalStore.pendingHouseholdSettings()
                ?.takeIf { it.householdId == bound }
                ?.baseVersion
                ?: 1L
            syncLocalStore.markHouseholdSettingsPending(bound, baseVersion)
            mutableState.value = mutableState.value.copy(
                errorMessage = "인터넷에 연결되면 가계부 설정을 반영할게요",
            )
            return@withLock
        }
        if (current.matches(setup)) return@withLock
        mutableState.value = mutableState.value.copy(errorMessage = null)
        syncLocalStore.markHouseholdSettingsPending(current.householdId, current.version)
        perform {
            val updated = updateRemoteSettings(current, setup)
            syncLocalStore.clearPendingHouseholdSettings(current.householdId)
            mutableState.value = mutableState.value.copy(
                status = HouseholdSessionStatus.READY,
                activeHousehold = updated,
                availableHouseholds = mutableState.value.availableHouseholds.replace(updated.summary()),
                errorMessage = null,
            )
        }
    }

    suspend fun createInvitation(targetMemberOrder: Int) = mutex.withLock {
        val current = requireNotNull(mutableState.value.activeHousehold)
        val target = requireNotNull(current.members.firstOrNull { it.order == targetMemberOrder })
        require(target.linkedUserId == null) { "이미 연결된 구성원이에요" }
        perform {
            val invitation = api.createInvitation(current.householdId, target.memberId)
            mutableState.value = mutableState.value.copy(invitation = invitation, errorMessage = null)
        }
    }

    suspend fun revokeInvitation() = mutex.withLock {
        val current = requireNotNull(mutableState.value.activeHousehold)
        val invitation = mutableState.value.invitation ?: return@withLock
        perform {
            api.revokeInvitation(current.householdId, invitation.invitation.invitationId)
            mutableState.value = mutableState.value.copy(invitation = null, errorMessage = null)
        }
    }

    suspend fun clearInvitation() = mutex.withLock {
        mutableState.value = mutableState.value.copy(invitation = null)
    }

    suspend fun resetLocalCloudConnection() = mutex.withLock {
        // Remove the READY context before waiting for any in-flight sync. A scheduled retry that
        // wakes afterwards will see no usable context and cannot re-bind the former household.
        mutableState.value = HouseholdSessionState(
            status = HouseholdSessionStatus.NEEDS_CONNECTION,
            availableHouseholds = mutableState.value.availableHouseholds,
        )
        syncOperationGuard.withExclusive { syncLocalStore.resetCloudBinding() }
    }

    suspend fun activeHouseholdId(): UuidString? {
        return mutableState.value.activeHousehold?.householdId ?: syncLocalStore.boundHouseholdId()
    }

    private suspend fun loadHouseholds() {
        val existingInvitation = mutableState.value.invitation
        val bound = syncLocalStore.boundHouseholdId()
        mutableState.value = mutableState.value.copy(
            status = if (mutableState.value.activeHousehold == null) {
                HouseholdSessionStatus.LOADING
            } else {
                HouseholdSessionStatus.READY
            },
            errorMessage = null,
            invitation = existingInvitation,
        )
        perform(offlineFallbackStatus = bound?.let { HouseholdSessionStatus.READY }) {
            val households = api.list()
            if (bound == null) {
                mutableState.value = HouseholdSessionState(
                    status = HouseholdSessionStatus.NEEDS_CONNECTION,
                    availableHouseholds = households,
                )
                return@perform
            }
            if (households.none { it.householdId == bound }) {
                mutableState.value = HouseholdSessionState(
                    status = HouseholdSessionStatus.NEEDS_CONNECTION,
                    availableHouseholds = households,
                    errorMessage = "이 기기의 이전 가계부 연결을 먼저 해제해 주세요",
                    requiresLocalReconnection = true,
                )
                return@perform
            }
            var detail = api.get(bound)
            val pendingSettings = syncLocalStore.pendingHouseholdSettings()
            val localSetup = setupRepository.observeSetup().first()
            if (pendingSettings?.householdId == bound && localSetup != null) {
                detail = if (detail.matches(localSetup)) detail else updateRemoteSettings(detail, localSetup)
                syncLocalStore.clearPendingHouseholdSettings(bound)
            } else {
                setupRepository.saveSetup(detail.localSetup())
            }
            val stillPendingInvitation = existingInvitation?.takeIf { pending ->
                detail.members.any { member ->
                    member.memberId == pending.invitation.targetMemberId && member.linkedUserId == null
                }
            }
            mutableState.value = HouseholdSessionState(
                status = HouseholdSessionStatus.READY,
                availableHouseholds = households,
                activeHousehold = detail,
                invitation = stillPendingInvitation,
            )
        }
    }

    private suspend fun updateRemoteSettings(
        current: HouseholdDetailDto,
        setup: LedgerSetup,
    ): HouseholdDetailDto {
        suspend fun update(base: HouseholdDetailDto): HouseholdDetailDto {
            val membersByOrder = base.members.associateBy { it.order }
            return api.update(
                base.householdId,
                HouseholdUpdateRequest(
                    baseVersion = base.version,
                    name = setup.ledgerName,
                    baseYear = setup.baseYear,
                    members = setup.members.sortedBy(LedgerMember::order).map { member ->
                        val remote = requireNotNull(membersByOrder[member.order])
                        LedgerMemberUpdateDto(remote.memberId, member.displayName, member.order)
                    },
                    annualSavingsTargets = setup.annualSavingsTargetsWon.entries
                        .sortedBy { it.key }
                        .map { AnnualSavingsTargetDto(it.key, it.value) },
                ),
            )
        }
        return try {
            update(current)
        } catch (failure: MoaLogApiException) {
            if (failure.status != 409) throw failure
            update(api.get(current.householdId))
        }
    }

    private suspend fun perform(
        offlineFallbackStatus: HouseholdSessionStatus? = null,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.value = mutableState.value.copy(
                status = when {
                    mutableState.value.activeHousehold != null -> HouseholdSessionStatus.READY
                    offlineFallbackStatus != null && failure.isTransientNetworkFailure() -> offlineFallbackStatus
                    else -> HouseholdSessionStatus.FAILED
                },
                errorMessage = failure.message ?: "가계부를 연결하지 못했어요",
            )
        }
    }
}

private fun Throwable.isTransientNetworkFailure(): Boolean =
    this !is MoaLogApiException || status == 408 || status == 429 || status >= 500

class ActiveSyncSessionContextProvider(
    private val auth: MoaLogAuthSessionManager,
    private val households: MoaLogHouseholdSessionManager,
) : SyncSessionContextProvider {
    override suspend fun current(): SyncSessionContext? {
        if (!auth.state.value.isAuthenticated) return null
        if (households.state.value.status != HouseholdSessionStatus.READY) return null
        val householdId = households.activeHouseholdId() ?: return null
        return SyncSessionContext(householdId, auth.deviceId())
    }
}

private fun LedgerSetup.createRequest(creatorMemberOrder: Int) = HouseholdCreateRequest(
    name = ledgerName,
    baseYear = baseYear,
    creatorMemberOrder = creatorMemberOrder,
    members = members.sortedBy(LedgerMember::order).map { LedgerMemberInputDto(it.displayName, it.order) },
    annualSavingsTargets = annualSavingsTargetsWon.entries.sortedBy { it.key }
        .map { AnnualSavingsTargetDto(it.key, it.value) },
)

private fun HouseholdDetailDto.localSetup() = LedgerSetup(
    ledgerName = name,
    members = members.sortedBy { it.order }.map { LedgerMember(it.displayName, it.order) },
    baseYear = baseYear,
    annualSavingsTargetWon = annualSavingsTargets.firstOrNull { it.year == baseYear }?.amountWon,
    annualSavingsTargetsWon = annualSavingsTargets.associate { it.year to it.amountWon },
)

private fun HouseholdDetailDto.matches(setup: LedgerSetup): Boolean =
    name == setup.ledgerName &&
        baseYear == setup.baseYear &&
        members.sortedBy { it.order }.map { it.displayName to it.order } ==
        setup.members.sortedBy(LedgerMember::order).map { it.displayName to it.order } &&
        annualSavingsTargets.associate { it.year to it.amountWon } == setup.annualSavingsTargetsWon

private fun HouseholdDetailDto.summary() = HouseholdSummaryDto(
    householdId = householdId,
    name = name,
    currency = currency,
    baseYear = baseYear,
    currentUserRole = currentUserRole,
    updatedAt = updatedAt,
)

private fun List<HouseholdSummaryDto>.replace(value: HouseholdSummaryDto): List<HouseholdSummaryDto> =
    map { if (it.householdId == value.householdId) value else it }
