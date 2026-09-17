package kr.jm.moalog.feature.assets.presentation

import kr.jm.moalog.core.model.AssetItem
import kr.jm.moalog.core.model.AssetKind
import kr.jm.moalog.core.model.AssetPortfolio
import kr.jm.moalog.core.model.AssetValueStatus
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

enum class AssetTrendStatus { Confirmed, Estimated, Mixed, Missing }
enum class AssetSort { AmountDescending, Name }

data class AssetRow(
    val asset: AssetItem,
    val amountWon: Long?,
    val status: AssetTrendStatus = AssetTrendStatus.Missing,
    val hasOverflow: Boolean = false,
)

data class AssetTrendPoint(
    val month: YearMonthKey,
    val amountWon: Long?,
    val status: AssetTrendStatus,
    val hasOverflow: Boolean = false,
)

data class AssetsUiState(
    val month: YearMonthKey,
    val rows: List<AssetRow> = emptyList(),
    val totalWon: Long? = null,
    val totalStatus: AssetTrendStatus = AssetTrendStatus.Missing,
    val hasTotalOverflow: Boolean = false,
    val missingValueCount: Int = 0,
    val previousMonthDeltaWon: Long? = null,
    val trend: List<AssetTrendPoint> = emptyList(),
    val sort: AssetSort = AssetSort.AmountDescending,
    val purposeAccountTotalWon: Long? = null,
    val purposeAccountMissingCount: Int = 0,
    val hasPurposeAccountOverflow: Boolean = false,
    val isLoading: Boolean = true,
    val loadError: String? = null,
) {
    val isEmpty: Boolean get() = !isLoading && rows.isEmpty()
}

sealed interface AssetsAction {
    data object PreviousMonth : AssetsAction
    data object NextMonth : AssetsAction
    data class SelectMonth(val month: YearMonthKey) : AssetsAction
    data object ToggleSort : AssetsAction
    data object Retry : AssetsAction
}

private data class AssetTotal(
    val amountWon: Long?,
    val status: AssetTrendStatus,
    val missingCount: Int,
    val hasOverflow: Boolean,
)

internal fun buildAssetsState(
    month: YearMonthKey,
    portfolio: AssetPortfolio,
    sort: AssetSort = AssetSort.AmountDescending,
): AssetsUiState {
    val ordinaryAssets = portfolio.assets.filter { it.kind == AssetKind.Ordinary }
    fun totalAt(key: YearMonthKey, assets: List<AssetItem>): AssetTotal {
        if (assets.isEmpty()) return AssetTotal(null, AssetTrendStatus.Missing, 0, false)
        val values = assets.map { portfolio.resolveValue(it.id, key) }
        val overflow = values.any { it.hasOverflow }
        val amounts = values.mapNotNull { it.amountWon }
        val total = if (overflow) null else amounts.sumWonOrNull()
        val totalOverflow = overflow || (amounts.isNotEmpty() && total == null)
        val status = when {
            amounts.isEmpty() && !totalOverflow -> AssetTrendStatus.Missing
            values.all { it.amountWon != null && it.status == AssetValueStatus.Confirmed } -> AssetTrendStatus.Confirmed
            values.all { it.amountWon != null && it.status == AssetValueStatus.Estimated } -> AssetTrendStatus.Estimated
            else -> AssetTrendStatus.Mixed
        }
        return AssetTotal(total, status, values.count { it.amountWon == null && !it.hasOverflow }, totalOverflow)
    }

    val current = totalAt(month, ordinaryAssets)
    val previous = month.plusMonthsOrNull(-1)?.let { totalAt(it, ordinaryAssets) }
    val rows = ordinaryAssets.map { asset ->
        val resolved = portfolio.resolveValue(asset.id, month)
        AssetRow(asset, resolved.amountWon, resolved.status.toTrendStatus(), resolved.hasOverflow)
    }.let { unsorted ->
        when (sort) {
            AssetSort.AmountDescending -> unsorted.sortedWith(
                compareByDescending<AssetRow> { it.amountWon != null }
                    .thenByDescending { it.amountWon }
                    .thenBy { it.asset.id },
            )
            AssetSort.Name -> unsorted.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.asset.name })
        }
    }
    val purpose = totalAt(month, portfolio.assets.filter { it.kind == AssetKind.PurposeAccount })
    return AssetsUiState(
        month = month,
        rows = rows,
        totalWon = current.amountWon,
        totalStatus = current.status,
        hasTotalOverflow = current.hasOverflow,
        missingValueCount = current.missingCount,
        previousMonthDeltaWon = if (
            current.amountWon != null && previous?.amountWon != null &&
            current.missingCount == 0 && previous.missingCount == 0 &&
            !current.hasOverflow && !previous.hasOverflow
        ) current.amountWon - previous.amountWon else null,
        trend = (5 downTo 0).mapNotNull { offset ->
            month.plusMonthsOrNull(-offset)?.let { key ->
                val total = totalAt(key, ordinaryAssets)
                AssetTrendPoint(key, total.amountWon, total.status, total.hasOverflow)
            }
        },
        sort = sort,
        purposeAccountTotalWon = purpose.amountWon,
        purposeAccountMissingCount = purpose.missingCount,
        hasPurposeAccountOverflow = purpose.hasOverflow,
        isLoading = false,
    )
}

private fun AssetValueStatus.toTrendStatus() = when (this) {
    AssetValueStatus.Confirmed -> AssetTrendStatus.Confirmed
    AssetValueStatus.Estimated -> AssetTrendStatus.Estimated
    AssetValueStatus.Missing -> AssetTrendStatus.Missing
}

class AssetsStateHolder(initialMonth: YearMonthKey, private val repository: AssetRepository, parentScope: CoroutineScope) {
    private val holderJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + holderJob)
    private val mutableState = MutableStateFlow(AssetsUiState(initialMonth))
    val state: StateFlow<AssetsUiState> = mutableState.asStateFlow()
    private var observation: Job? = null
    private var portfolio: AssetPortfolio? = null

    init { load() }
    fun close() = holderJob.cancel()
    fun onAction(action: AssetsAction) {
        when (action) {
            AssetsAction.PreviousMonth -> mutableState.value.month.plusMonthsOrNull(-1)?.let(::select)
            AssetsAction.NextMonth -> mutableState.value.month.plusMonthsOrNull(1)?.let(::select)
            is AssetsAction.SelectMonth -> select(action.month)
            AssetsAction.ToggleSort -> {
                val sort = if (mutableState.value.sort == AssetSort.AmountDescending) AssetSort.Name else AssetSort.AmountDescending
                mutableState.value = portfolio?.let { buildAssetsState(mutableState.value.month, it, sort) }
                    ?: mutableState.value.copy(sort = sort)
            }
            AssetsAction.Retry -> load()
        }
    }
    private fun select(month: YearMonthKey) {
        portfolio?.let { mutableState.value = buildAssetsState(month, it, mutableState.value.sort) }
            ?: run { mutableState.value = mutableState.value.copy(month = month) }
    }
    private fun load() {
        observation?.cancel()
        mutableState.value = mutableState.value.copy(isLoading = true, loadError = null)
        observation = scope.launch {
            try {
                repository.observePortfolio().collect {
                    portfolio = it
                    mutableState.value = buildAssetsState(mutableState.value.month, it, mutableState.value.sort)
                }
            } catch (c: CancellationException) { throw c }
            catch (_: Throwable) { mutableState.value = mutableState.value.copy(isLoading = false, loadError = "자산을 불러오지 못했어요. 다시 시도해 주세요") }
        }
    }
}
