package kr.jm.moalog.feature.setup.presentation

import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.feature.setup.domain.SetupRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LedgerSettingsDraft(
    val ledgerName: String,
    val firstMemberName: String,
    val secondMemberName: String,
)

data class LedgerSettingsUiState(
    val ledgerName: String = "",
    val firstMemberName: String = "",
    val secondMemberName: String = "",
    val baseYear: Int? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isDirty: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = !isLoading && !isSaving && isDirty
    fun draft() = LedgerSettingsDraft(ledgerName, firstMemberName, secondMemberName)
}

sealed interface LedgerSettingsAction {
    data class ChangeLedgerName(val value: String) : LedgerSettingsAction
    data class ChangeFirstMemberName(val value: String) : LedgerSettingsAction
    data class ChangeSecondMemberName(val value: String) : LedgerSettingsAction
    data class RestoreDraft(val value: LedgerSettingsDraft) : LedgerSettingsAction
    data object Save : LedgerSettingsAction
    data object Retry : LedgerSettingsAction
}

class LedgerSettingsStateHolder(
    private val repository: SetupRepository,
    parentScope: CoroutineScope,
) {
    private val holderJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + holderJob)
    private val mutableState = MutableStateFlow(LedgerSettingsUiState())
    val state: StateFlow<LedgerSettingsUiState> = mutableState.asStateFlow()
    private var loadedSetup: LedgerSetup? = null
    private var originalDraft: LedgerSettingsDraft? = null
    private var pendingDraft: LedgerSettingsDraft? = null
    private var loadJob: Job? = null

    init { load() }

    fun close() = holderJob.cancel()

    fun onAction(action: LedgerSettingsAction) {
        when (action) {
            is LedgerSettingsAction.ChangeLedgerName -> edit { copy(ledgerName = action.value) }
            is LedgerSettingsAction.ChangeFirstMemberName -> edit { copy(firstMemberName = action.value) }
            is LedgerSettingsAction.ChangeSecondMemberName -> edit { copy(secondMemberName = action.value) }
            is LedgerSettingsAction.RestoreDraft -> restore(action.value)
            LedgerSettingsAction.Save -> save()
            LedgerSettingsAction.Retry -> load()
        }
    }

    private fun edit(transform: LedgerSettingsUiState.() -> LedgerSettingsUiState) {
        val current = mutableState.value
        if (current.isLoading || current.isSaving || current.saved) return
        val changed = current.transform().copy(error = null)
        mutableState.value = changed.copy(isDirty = changed.draft() != originalDraft)
    }

    private fun restore(draft: LedgerSettingsDraft) {
        if (mutableState.value.isLoading) {
            pendingDraft = draft
            return
        }
        if (mutableState.value.isSaving || mutableState.value.saved) return
        mutableState.value = mutableState.value.copy(
            ledgerName = draft.ledgerName,
            firstMemberName = draft.firstMemberName,
            secondMemberName = draft.secondMemberName,
            isDirty = draft != originalDraft,
            error = null,
        )
    }

    private fun load() {
        if (loadJob?.isActive == true || mutableState.value.isSaving) return
        mutableState.value = mutableState.value.copy(isLoading = true, error = null)
        loadJob = scope.launch {
            try {
                repository.observeSetup().collect { setup ->
                    if (setup == null) {
                        mutableState.value = mutableState.value.copy(isLoading = false, error = "가계부 정보를 찾을 수 없어요")
                    } else {
                        val members = setup.members.sortedBy { it.order }
                        if (members.size != 2) {
                            mutableState.value = mutableState.value.copy(isLoading = false, error = "구성원 정보를 확인해 주세요")
                        } else {
                            loadedSetup = setup
                            mutableState.value = LedgerSettingsUiState(
                                ledgerName = setup.ledgerName,
                                firstMemberName = members[0].displayName,
                                secondMemberName = members[1].displayName,
                                baseYear = setup.baseYear,
                                isLoading = false,
                            )
                            originalDraft = mutableState.value.draft()
                            pendingDraft?.also { pendingDraft = null }?.let(::restore)
                            loadJob?.cancel()
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(isLoading = false, error = "가계부 정보를 불러오지 못했어요. 다시 시도해 주세요")
            }
        }
    }

    private fun save() {
        val state = mutableState.value
        val setup = loadedSetup ?: return
        if (!state.canSave) return
        val ledgerName = state.ledgerName.trim()
        val firstName = state.firstMemberName.trim()
        val secondName = state.secondMemberName.trim()
        val validation = when {
            ledgerName.isEmpty() -> "가계부 이름을 입력해 주세요"
            firstName.isEmpty() || secondName.isEmpty() -> "두 구성원의 이름을 입력해 주세요"
            else -> null
        }
        if (validation != null) {
            mutableState.value = state.copy(error = validation)
            return
        }
        mutableState.value = state.copy(isSaving = true, error = null)
        scope.launch {
            try {
                repository.saveSetup(
                    setup.copy(
                        ledgerName = ledgerName,
                        members = setup.members.sortedBy { it.order }.mapIndexed { index, member ->
                            member.copy(displayName = if (index == 0) firstName else secondName)
                        },
                    ),
                )
                mutableState.value = mutableState.value.copy(isSaving = false, isDirty = false, saved = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(isSaving = false, error = "저장하지 못했어요. 다시 시도해 주세요")
            }
        }
    }
}
