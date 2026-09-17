package kr.jm.moalog.feature.maintenance.presentation

import kr.jm.moalog.core.model.MaintenanceFeeItemKey
import kr.jm.moalog.core.model.MaintenanceFeeMonth
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.maintenance.domain.MaintenanceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class MaintenanceOverviewArgs(val initialMonth: YearMonthKey)

enum class MaintenanceOverviewTab { Detail, Comparison }

data class MaintenanceOverviewRow(
    val key: MaintenanceFeeItemKey,
    val label: String,
    val amountWon: Long?,
)

data class MaintenanceComparisonPoint(
    val month: YearMonthKey,
    val totalWon: Long?,
    val enteredCount: Int,
)

data class MaintenanceOverviewUiState(
    val month: YearMonthKey,
    val rows: List<MaintenanceOverviewRow> = emptyRows(),
    val totalWon: Long? = null,
    val enteredCount: Int = 0,
    val comparisonStart: YearMonthKey = month.plusMonths(-5),
    val comparisonEnd: YearMonthKey = month,
    val comparisonPoints: List<MaintenanceComparisonPoint> = emptyList(),
    val selectedTab: MaintenanceOverviewTab = MaintenanceOverviewTab.Detail,
    val isLoading: Boolean = true,
    val loadError: String? = null,
)

sealed interface MaintenanceOverviewAction {
    data object PreviousMonth : MaintenanceOverviewAction
    data object NextMonth : MaintenanceOverviewAction
    data class SelectTab(val tab: MaintenanceOverviewTab) : MaintenanceOverviewAction
    data class ComparisonRangeChanged(
        val startInclusive: YearMonthKey,
        val endInclusive: YearMonthKey,
    ) : MaintenanceOverviewAction
    data object Retry : MaintenanceOverviewAction
}

class MaintenanceOverviewStateHolder(
    args: MaintenanceOverviewArgs,
    private val repository: MaintenanceRepository,
    parentScope: CoroutineScope,
) {
    private val holderJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + holderJob)
    private val mutableState = MutableStateFlow(MaintenanceOverviewUiState(args.initialMonth))
    val state: StateFlow<MaintenanceOverviewUiState> = mutableState.asStateFlow()
    private var observation: Job? = null

    init {
        observe()
    }

    fun close() = holderJob.cancel()

    fun onAction(action: MaintenanceOverviewAction) {
        when (action) {
            MaintenanceOverviewAction.PreviousMonth -> selectMonth(mutableState.value.month.plusMonths(-1))
            MaintenanceOverviewAction.NextMonth -> selectMonth(mutableState.value.month.plusMonths(1))
            is MaintenanceOverviewAction.SelectTab -> {
                mutableState.value = mutableState.value.copy(selectedTab = action.tab)
            }
            is MaintenanceOverviewAction.ComparisonRangeChanged -> {
                if (action.startInclusive.monthIndex() > action.endInclusive.monthIndex()) {
                    mutableState.value = mutableState.value.copy(loadError = "비교 시작월은 종료월보다 늦을 수 없어요")
                } else {
                    mutableState.value = mutableState.value.copy(
                        comparisonStart = action.startInclusive,
                        comparisonEnd = action.endInclusive,
                        loadError = null,
                    )
                    observe()
                }
            }
            MaintenanceOverviewAction.Retry -> observe()
        }
    }

    private fun selectMonth(month: YearMonthKey) {
        mutableState.value = mutableState.value.copy(
            month = month,
            comparisonStart = month.plusMonths(-5),
            comparisonEnd = month,
        )
        observe()
    }

    private fun observe() {
        observation?.cancel()
        val request = mutableState.value
        mutableState.value = request.copy(isLoading = true, loadError = null)
        observation = scope.launch {
            try {
                combine(
                    repository.observeMonth(request.month),
                    repository.observeRange(request.comparisonStart, request.comparisonEnd),
                ) { month, comparison -> month to comparison }
                    .collect { (month, comparison) ->
                        mutableState.value = mutableState.value.copy(
                            rows = month.entries.map { MaintenanceOverviewRow(it.key, it.key.label, it.amountWon) },
                            totalWon = month.totalWon,
                            enteredCount = month.enteredCount,
                            comparisonPoints = comparison.map {
                                MaintenanceComparisonPoint(it.billMonth, it.totalWon, it.enteredCount)
                            },
                            isLoading = false,
                            loadError = null,
                        )
                    }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    loadError = "관리비를 불러오지 못했어요. 다시 시도해 주세요",
                )
            }
        }
    }
}

private fun emptyRows(): List<MaintenanceOverviewRow> = MaintenanceFeeItemKey.entries.map {
    MaintenanceOverviewRow(it, it.label, null)
}

private fun YearMonthKey.monthIndex(): Int = year * 12 + month - 1
