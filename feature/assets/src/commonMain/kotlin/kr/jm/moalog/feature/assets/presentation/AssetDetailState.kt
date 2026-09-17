package kr.jm.moalog.feature.assets.presentation

import kr.jm.moalog.core.model.AssetGrowthRule
import kr.jm.moalog.core.model.AssetItem
import kr.jm.moalog.core.model.AssetPortfolio
import kr.jm.moalog.core.model.AssetValuation
import kr.jm.moalog.core.model.AssetValueStatus
import kr.jm.moalog.core.model.ResolvedAssetValue
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.core.model.resolveValue
import kr.jm.moalog.feature.assets.domain.AssetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AssetDetailArgs(val assetId: Long, val month: YearMonthKey)
data class AssetDetailPoint(val month: YearMonthKey, val value: ResolvedAssetValue)
enum class AssetDetailMutation { DeleteAsset, DeleteValuation, DeleteGrowthRule }

data class AssetDetailUiState(
    val args: AssetDetailArgs,
    val asset: AssetItem? = null,
    val value: ResolvedAssetValue = ResolvedAssetValue(null, AssetValueStatus.Missing),
    val previousMonthDeltaWon: Long? = null,
    val rule: AssetGrowthRule? = null,
    val chart: List<AssetDetailPoint> = emptyList(),
    val records: List<AssetDetailPoint> = emptyList(),
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val isMutating: Boolean = false,
    val mutationError: String? = null,
    val failedMutation: AssetDetailMutation? = null,
    val deleted: Boolean = false,
)

sealed interface AssetDetailAction {
    data object PreviousMonth : AssetDetailAction
    data object NextMonth : AssetDetailAction
    data class SelectMonth(val month: YearMonthKey) : AssetDetailAction
    data object DeleteAsset : AssetDetailAction
    data object DeleteSelectedValuation : AssetDetailAction
    data object DeleteGrowthRule : AssetDetailAction
    data object RetryMutation : AssetDetailAction
    data object DismissMutationError : AssetDetailAction
    data object Retry : AssetDetailAction
}

internal fun buildAssetDetailState(args: AssetDetailArgs, portfolio: AssetPortfolio): AssetDetailUiState {
    val asset = portfolio.assets.firstOrNull { it.id == args.assetId }
        ?: return AssetDetailUiState(args, isLoading = false, loadError = "자산 항목을 찾을 수 없어요")
    fun point(month: YearMonthKey) = AssetDetailPoint(month, portfolio.resolveValue(asset.id, month))
    val value = portfolio.resolveValue(asset.id, args.month)
    val previous = args.month.plusMonthsOrNull(-1)?.let { portfolio.resolveValue(asset.id, it) }
    val currentAmount = value.amountWon
    val previousAmount = previous?.amountWon
    return AssetDetailUiState(
        args = args,
        asset = asset,
        value = value,
        previousMonthDeltaWon = if (
            currentAmount != null && previousAmount != null &&
            !value.hasOverflow && !previous.hasOverflow
        ) currentAmount - previousAmount else null,
        rule = portfolio.growthRules.firstOrNull { it.assetId == asset.id },
        chart = (-2..2).mapNotNull { offset -> args.month.plusMonthsOrNull(offset)?.let(::point) },
        records = (2 downTo -2).mapNotNull { offset -> args.month.plusMonthsOrNull(offset)?.let(::point) },
        isLoading = false,
    )
}

class AssetDetailStateHolder(
    initialArgs: AssetDetailArgs,
    private val repository: AssetRepository,
    parentScope: CoroutineScope,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutableState = MutableStateFlow(AssetDetailUiState(initialArgs))
    val state: StateFlow<AssetDetailUiState> = mutableState.asStateFlow()
    private var portfolio: AssetPortfolio? = null
    private var observation: Job? = null
    init { load() }
    fun close() = job.cancel()
    fun onAction(action: AssetDetailAction) {
        when (action) {
            AssetDetailAction.PreviousMonth -> mutableState.value.args.month.plusMonthsOrNull(-1)?.let(::select)
            AssetDetailAction.NextMonth -> mutableState.value.args.month.plusMonthsOrNull(1)?.let(::select)
            is AssetDetailAction.SelectMonth -> select(action.month)
            AssetDetailAction.DeleteAsset -> mutate(AssetDetailMutation.DeleteAsset)
            AssetDetailAction.DeleteSelectedValuation -> mutate(AssetDetailMutation.DeleteValuation)
            AssetDetailAction.DeleteGrowthRule -> mutate(AssetDetailMutation.DeleteGrowthRule)
            AssetDetailAction.RetryMutation -> mutableState.value.failedMutation?.let(::mutate)
            AssetDetailAction.DismissMutationError -> mutableState.value = mutableState.value.copy(mutationError = null, failedMutation = null)
            AssetDetailAction.Retry -> load()
        }
    }
    private fun select(month: YearMonthKey) {
        val args = mutableState.value.args.copy(month = month)
        mutableState.value = portfolio?.let { buildAssetDetailState(args, it) } ?: mutableState.value.copy(args = args)
    }
    private fun load() {
        observation?.cancel()
        mutableState.value = mutableState.value.copy(isLoading = true, loadError = null)
        observation = scope.launch {
            try {
                repository.observePortfolio().collect {
                    portfolio = it
                    if (!mutableState.value.deleted) {
                        val rebuilt = buildAssetDetailState(mutableState.value.args, it)
                        mutableState.value = rebuilt.copy(
                            isMutating = mutableState.value.isMutating,
                            mutationError = mutableState.value.mutationError,
                            failedMutation = mutableState.value.failedMutation,
                        )
                    }
                }
            } catch (c: CancellationException) { throw c }
            catch (_: Throwable) { mutableState.value = mutableState.value.copy(isLoading = false, loadError = "자산 상세를 불러오지 못했어요") }
        }
    }
    private fun mutate(mutation: AssetDetailMutation) {
        val current = mutableState.value
        if (current.isLoading || current.isMutating || current.deleted) return
        if (mutation == AssetDetailMutation.DeleteValuation && current.value.status != AssetValueStatus.Confirmed) return
        if (mutation == AssetDetailMutation.DeleteGrowthRule && current.rule == null) return
        mutableState.value = current.copy(isMutating = true, mutationError = null, failedMutation = null)
        scope.launch {
            try {
                when (mutation) {
                    AssetDetailMutation.DeleteAsset -> repository.deleteAsset(current.args.assetId)
                    AssetDetailMutation.DeleteValuation -> repository.deleteValuation(current.args.assetId, current.args.month)
                    AssetDetailMutation.DeleteGrowthRule -> repository.deleteGrowthRule(current.args.assetId)
                }
                mutableState.value = mutableState.value.copy(
                    isMutating = false,
                    deleted = mutation == AssetDetailMutation.DeleteAsset,
                )
            } catch (c: CancellationException) { throw c }
            catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isMutating = false,
                    mutationError = "변경하지 못했어요. 다시 시도해 주세요",
                    failedMutation = mutation,
                )
            }
        }
    }
}
