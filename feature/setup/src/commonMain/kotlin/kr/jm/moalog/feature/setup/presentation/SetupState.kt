package kr.jm.moalog.feature.setup.presentation

import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.feature.setup.domain.SetupRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupFieldErrors(
    val ledgerName: String? = null,
    val firstMemberName: String? = null,
    val secondMemberName: String? = null,
    val baseYear: String? = null,
    val annualSavingsTarget: String? = null,
) {
    val hasAny: Boolean
        get() = listOf(ledgerName, firstMemberName, secondMemberName, baseYear, annualSavingsTarget).any { it != null }
}

data class SetupUiState(
    val ledgerName: String = "",
    val firstMemberName: String = "",
    val secondMemberName: String = "",
    val baseYear: String = "",
    val annualSavingsTarget: String = "",
    val errors: SetupFieldErrors = SetupFieldErrors(),
    val isSaving: Boolean = false,
    val persistenceError: String? = null,
)

sealed interface SetupAction {
    data class LedgerNameChanged(val value: String) : SetupAction
    data class FirstMemberNameChanged(val value: String) : SetupAction
    data class SecondMemberNameChanged(val value: String) : SetupAction
    data class BaseYearChanged(val value: String) : SetupAction
    data class AnnualSavingsTargetChanged(val value: String) : SetupAction
    data object Save : SetupAction
}

sealed interface WonParseResult {
    data class Valid(val value: Long?) : WonParseResult
    data object Invalid : WonParseResult
}

fun parseOptionalWon(input: String): WonParseResult {
    val normalized = input.trim().replace(",", "")
    if (normalized.isEmpty()) return WonParseResult.Valid(null)
    if (normalized.any { !it.isDigit() }) return WonParseResult.Invalid
    return normalized.toLongOrNull()?.let { WonParseResult.Valid(it) } ?: WonParseResult.Invalid
}

internal fun validateSetup(state: SetupUiState): Pair<SetupFieldErrors, LedgerSetup?> {
    val ledgerName = state.ledgerName.trim()
    val firstName = state.firstMemberName.trim()
    val secondName = state.secondMemberName.trim()
    val year = state.baseYear.trim().toIntOrNull()
    val target = parseOptionalWon(state.annualSavingsTarget)
    val errors = SetupFieldErrors(
        ledgerName = if (ledgerName.isEmpty()) "가계부 이름을 입력해 주세요" else null,
        firstMemberName = if (firstName.isEmpty()) "첫 번째 사람 이름을 입력해 주세요" else null,
        secondMemberName = if (secondName.isEmpty()) "두 번째 사람 이름을 입력해 주세요" else null,
        baseYear = if (year == null || year !in 2000..9999) "기준 연도를 4자리 숫자로 입력해 주세요" else null,
        annualSavingsTarget = if (target is WonParseResult.Invalid) "0원 이상의 금액을 숫자로 입력해 주세요" else null,
    )
    if (errors.hasAny) return errors to null
    return errors to LedgerSetup(
        ledgerName = ledgerName,
        members = listOf(LedgerMember(firstName, 0), LedgerMember(secondName, 1)),
        baseYear = requireNotNull(year),
        annualSavingsTargetWon = (target as WonParseResult.Valid).value,
    )
}

class SetupStateHolder(
    private val repository: SetupRepository,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = mutableState.asStateFlow()

    fun onAction(action: SetupAction) {
        when (action) {
            is SetupAction.LedgerNameChanged -> edit { copy(ledgerName = action.value, errors = errors.copy(ledgerName = null)) }
            is SetupAction.FirstMemberNameChanged -> edit { copy(firstMemberName = action.value, errors = errors.copy(firstMemberName = null)) }
            is SetupAction.SecondMemberNameChanged -> edit { copy(secondMemberName = action.value, errors = errors.copy(secondMemberName = null)) }
            is SetupAction.BaseYearChanged -> edit { copy(baseYear = action.value.filter(Char::isDigit).take(4), errors = errors.copy(baseYear = null)) }
            is SetupAction.AnnualSavingsTargetChanged -> edit {
                copy(annualSavingsTarget = action.value, errors = errors.copy(annualSavingsTarget = null))
            }
            SetupAction.Save -> save()
        }
    }

    private fun edit(transform: SetupUiState.() -> SetupUiState) {
        if (!mutableState.value.isSaving) mutableState.update { it.transform().copy(persistenceError = null) }
    }

    private fun save() {
        val (errors, setup) = validateSetup(mutableState.value)
        if (setup == null) {
            mutableState.update { it.copy(errors = errors, persistenceError = null) }
            return
        }
        if (mutableState.value.isSaving) return
        mutableState.update { it.copy(errors = errors, isSaving = true, persistenceError = null) }
        scope.launch {
            try {
                repository.saveSetup(setup)
                mutableState.update { it.copy(isSaving = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.update {
                    it.copy(isSaving = false, persistenceError = "저장하지 못했어요. 잠시 후 다시 시도해 주세요")
                }
            }
        }
    }
}
