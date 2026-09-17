package kr.jm.moalog.feature.assets.presentation

import kr.jm.moalog.core.model.AssetKind
import kr.jm.moalog.core.model.AssetItem
import kr.jm.moalog.core.model.AssetPortfolio
import kr.jm.moalog.core.model.AssetValueStatus
import kr.jm.moalog.core.model.ResolvedAssetValue
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.core.model.resolveValue
import kr.jm.moalog.core.model.sumWonOrNull
import kr.jm.moalog.feature.assets.domain.AssetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PurposeAccountRow(
    val asset: AssetItem,
    val value: ResolvedAssetValue,
    val monthlyChangeWon: Long?,
    val monthlyChangeStatus: AssetValueStatus? = null,
    val hasOverflow: Boolean = false,
)

data class PurposeAccountsUiState(
    val month: YearMonthKey,
    val rows: List<PurposeAccountRow> = emptyList(),
    val enteredTotalWon: Long? = null,
    val previousMonthDeltaWon: Long? = null,
    val incompleteCount: Int = 0,
    val hasTotalOverflow: Boolean = false,
    val isLoading: Boolean = true,
    val loadError: String? = null,
) {
    val isEmpty: Boolean get() = !isLoading && rows.isEmpty()
}

sealed interface PurposeAccountsAction {
    data object PreviousMonth : PurposeAccountsAction
    data object NextMonth : PurposeAccountsAction
    data class SelectMonth(val month: YearMonthKey) : PurposeAccountsAction
    data object Retry : PurposeAccountsAction
}

internal fun buildPurposeAccountsState(month: YearMonthKey, portfolio: AssetPortfolio): PurposeAccountsUiState {
    val purposeAccounts = portfolio.assets.filter { it.kind == AssetKind.PurposeAccount }

    fun totalAt(targetMonth: YearMonthKey): Pair<Long?, Boolean> {
        val values = purposeAccounts.map { portfolio.resolveValue(it.id, targetMonth) }
        val amounts = values.mapNotNull { it.amountWon }
        val overflow = values.any { it.hasOverflow }
        return (if (overflow) null else amounts.takeIf { it.isNotEmpty() }?.sumWonOrNull()) to
            (overflow || (amounts.isNotEmpty() && amounts.sumWonOrNull() == null))
    }

    fun completeTotalAt(targetMonth: YearMonthKey): Long? {
        val values = purposeAccounts.map { portfolio.resolveValue(it.id, targetMonth) }
        if (values.isEmpty() || values.any { it.amountWon == null || it.hasOverflow }) return null
        return values.map { requireNotNull(it.amountWon) }.sumWonOrNull()
    }

    val (currentTotal, totalOverflow) = totalAt(month)
    val comparableCurrentTotal = completeTotalAt(month)
    val previousTotal = month.plusMonthsOrNull(-1)?.let(::completeTotalAt)
    return PurposeAccountsUiState(
        month = month,
        rows = purposeAccounts.map { asset ->
            val current = portfolio.resolveValue(asset.id, month)
            val next = month.plusMonthsOrNull(1)?.let { portfolio.resolveValue(asset.id, it) }
            val currentAmount = current.amountWon
            val nextAmount = next?.amountWon
            PurposeAccountRow(
                asset = asset,
                value = current,
                monthlyChangeWon = if (currentAmount != null && nextAmount != null) differenceOrNull(nextAmount, currentAmount) else null,
                monthlyChangeStatus = if (currentAmount != null && nextAmount != null) {
                    if (current.status == AssetValueStatus.Confirmed && next.status == AssetValueStatus.Confirmed) {
                        AssetValueStatus.Confirmed
                    } else {
                        AssetValueStatus.Estimated
                    }
                } else null,
                hasOverflow = current.hasOverflow || next?.hasOverflow == true,
            )
        },
        enteredTotalWon = currentTotal,
        previousMonthDeltaWon = if (comparableCurrentTotal != null && previousTotal != null) comparableCurrentTotal - previousTotal else null,
        incompleteCount = purposeAccounts.count {
            portfolio.resolveValue(it.id, month).let { value -> value.amountWon == null && !value.hasOverflow }
        },
        hasTotalOverflow = totalOverflow,
        isLoading = false,
    )
}

private fun differenceOrNull(left: Long, right: Long): Long? = when {
    right > 0L && left < Long.MIN_VALUE + right -> null
    right < 0L && left > Long.MAX_VALUE + right -> null
    else -> left - right
}

class PurposeAccountsStateHolder(
    initialMonth: YearMonthKey,
    private val repository: AssetRepository,
    parentScope: CoroutineScope,
) {
    private val holderJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + holderJob)
    private val mutableState = MutableStateFlow(PurposeAccountsUiState(initialMonth))
    val state: StateFlow<PurposeAccountsUiState> = mutableState.asStateFlow()
    private var observation: Job? = null
    private var portfolio: AssetPortfolio? = null

    init {
        load()
    }

    fun close() = holderJob.cancel()

    fun onAction(action: PurposeAccountsAction) {
        when (action) {
            PurposeAccountsAction.PreviousMonth -> mutableState.value.month.plusMonthsOrNull(-1)?.let(::select)
            PurposeAccountsAction.NextMonth -> mutableState.value.month.plusMonthsOrNull(1)?.let(::select)
            is PurposeAccountsAction.SelectMonth -> select(action.month)
            PurposeAccountsAction.Retry -> load()
        }
    }

    private fun select(month: YearMonthKey) {
        portfolio?.let { mutableState.value = buildPurposeAccountsState(month, it) }
            ?: run { mutableState.value = mutableState.value.copy(month = month) }
    }

    private fun load() {
        observation?.cancel()
        mutableState.value = mutableState.value.copy(isLoading = true, loadError = null)
        observation = scope.launch {
            try {
                repository.observePortfolio().collect {
                    portfolio = it
                    mutableState.value = buildPurposeAccountsState(mutableState.value.month, it)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    loadError = "목적통장을 불러오지 못했어요. 다시 시도해 주세요",
                )
            }
        }
    }
}
