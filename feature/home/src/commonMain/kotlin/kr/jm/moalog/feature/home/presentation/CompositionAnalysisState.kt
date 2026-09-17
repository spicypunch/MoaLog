package kr.jm.moalog.feature.home.presentation

import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.home.domain.HomeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CompositionTab { FixedExpense, VariableExpense, Savings }
enum class CompositionPeriod { Monthly, Annual }

data class CompositionAnalysisRow(
    val id: String,
    val title: String,
    val supportingText: String?,
    val amountWon: Long,
    val percent: Double?,
    val isNegative: Boolean = amountWon < 0L,
    val expenseCategoryId: String? = null,
)

data class CompositionAnalysisUiState(
    val selectedTab: CompositionTab,
    val selectedMonth: YearMonthKey,
    val selectedYear: Int = selectedMonth.year,
    val selectedPeriod: CompositionPeriod = if (selectedTab == CompositionTab.VariableExpense) CompositionPeriod.Monthly else CompositionPeriod.Annual,
    val memberNames: String = "",
    val rows: List<CompositionAnalysisRow> = emptyList(),
    val totalWon: Long = 0L,
    val isComplete: Boolean = false,
    val isLoading: Boolean = true,
    val loadError: String? = null,
) {
    val usesAnnualPeriod: Boolean get() = selectedTab != CompositionTab.VariableExpense || selectedPeriod == CompositionPeriod.Annual
    val periodLabel: String get() = if (usesAnnualPeriod) "${selectedYear}년" else "${selectedMonth.year}년 ${selectedMonth.month}월"
}

sealed interface CompositionAnalysisAction {
    data class SelectTab(val tab: CompositionTab) : CompositionAnalysisAction
    data class SelectPeriod(val period: CompositionPeriod) : CompositionAnalysisAction
    data object PreviousPeriod : CompositionAnalysisAction
    data object NextPeriod : CompositionAnalysisAction
    data object Retry : CompositionAnalysisAction
}

class CompositionAnalysisStateHolder(
    private val repository: HomeRepository,
    initialMonth: YearMonthKey,
    initialTab: CompositionTab = CompositionTab.VariableExpense,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(CompositionAnalysisUiState(initialTab, initialMonth))
    val state: StateFlow<CompositionAnalysisUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init {
        load(initialMonth.year)
    }

    fun onAction(action: CompositionAnalysisAction) {
        when (action) {
            is CompositionAnalysisAction.SelectTab -> {
                if (action.tab != mutableState.value.selectedTab) {
                    mutableState.value = mutableState.value.copy(
                        selectedTab = action.tab,
                        selectedPeriod = if (action.tab == CompositionTab.VariableExpense) mutableState.value.selectedPeriod else CompositionPeriod.Annual,
                    )
                    rebuildForCurrentSnapshot()
                }
            }
            is CompositionAnalysisAction.SelectPeriod -> {
                if (mutableState.value.selectedTab == CompositionTab.VariableExpense && action.period != mutableState.value.selectedPeriod) {
                    mutableState.value = mutableState.value.copy(selectedPeriod = action.period)
                    rebuildForCurrentSnapshot()
                }
            }
            CompositionAnalysisAction.PreviousPeriod -> movePeriod(-1)
            CompositionAnalysisAction.NextPeriod -> movePeriod(1)
            CompositionAnalysisAction.Retry -> load(mutableState.value.selectedYear)
        }
    }

    fun close() = loadJob?.cancel()

    private var latestMonths: List<HomeAnnualMonthSnapshot> = emptyList()

    private fun movePeriod(delta: Int) {
        val state = mutableState.value
        if (state.usesAnnualPeriod) {
            val year = state.selectedYear + delta
            if (year in 1900..9999) {
                mutableState.value = state.copy(selectedYear = year, selectedMonth = YearMonthKey(year, state.selectedMonth.month))
                load(year)
            }
        } else {
            val month = state.selectedMonth.plusMonths(delta)
            mutableState.value = state.copy(selectedMonth = month, selectedYear = month.year)
            if (month.year != state.selectedYear) load(month.year) else rebuildForCurrentSnapshot()
        }
    }

    private fun load(year: Int) {
        loadJob?.cancel()
        mutableState.value = mutableState.value.copy(isLoading = true, loadError = null)
        loadJob = scope.launch {
            try {
                repository.observeYear(year).collect { snapshot ->
                    latestMonths = snapshot.months
                    val names = snapshot.setup?.members?.sortedBy { it.order }?.joinToString(" · ") { it.displayName }.orEmpty()
                    mutableState.value = mutableState.value.copy(memberNames = names, isLoading = false, loadError = null)
                    rebuildForCurrentSnapshot()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(isLoading = false, loadError = "구성 분석을 불러오지 못했어요")
            }
        }
    }

    private fun rebuildForCurrentSnapshot() {
        val current = mutableState.value
        if (current.isLoading) return
        try {
            val result = calculateCompositionAnalysis(current.selectedTab, current.selectedMonth, current.selectedYear, latestMonths, current.selectedPeriod)
            mutableState.value = current.copy(rows = result.rows, totalWon = result.totalWon, isComplete = result.isComplete, loadError = null)
        } catch (_: ArithmeticException) {
            mutableState.value = current.copy(isLoading = false, loadError = "금액 합계가 허용 범위를 넘었어요")
        }
    }
}

data class CompositionAnalysisResult(
    val rows: List<CompositionAnalysisRow>,
    val totalWon: Long,
    val isComplete: Boolean,
)

fun calculateCompositionAnalysis(
    tab: CompositionTab,
    selectedMonth: YearMonthKey,
    selectedYear: Int,
    months: List<HomeAnnualMonthSnapshot>,
    period: CompositionPeriod = if (tab == CompositionTab.VariableExpense) CompositionPeriod.Monthly else CompositionPeriod.Annual,
): CompositionAnalysisResult {
    return when (tab) {
        CompositionTab.VariableExpense -> {
            val expenses = if (period == CompositionPeriod.Annual) {
                months.filter { it.month.year == selectedYear }.flatMap { it.variableExpenses }.filter { it.attributionMonth.year == selectedYear }
            } else {
                months.firstOrNull { it.month == selectedMonth }?.variableExpenses.orEmpty()
            }
            val grouped = expenses.groupBy { it.categoryId to it.categoryName }
                .map { (category, records) ->
                    CompositionRowSeed(
                        id = "expense:${category.first.length}:${category.first}|${category.second.length}:${category.second}",
                        title = category.second,
                        supportingText = records.mapNotNull { it.detail?.takeIf(String::isNotBlank) }.distinct().take(2).joinToString(", ").ifBlank { null },
                        amountWon = records.map { it.amountWon }.checkedMoneySum(),
                        expenseCategoryId = category.first,
                    )
                }
            grouped.toResult(isComplete = true)
        }
        CompositionTab.FixedExpense -> planRows(PlanItemType.FixedExpense, selectedYear, months)
        CompositionTab.Savings -> planRows(PlanItemType.Savings, selectedYear, months)
    }
}

private fun planRows(
    type: PlanItemType,
    year: Int,
    months: List<HomeAnnualMonthSnapshot>,
): CompositionAnalysisResult {
    val yearMonths = months.filter { it.month.year == year }
    val items = yearMonths.flatMap { it.items }.filter { it.type == type && it.attributionMonth.year == year }
    val complete = yearMonths.map { it.month.month }.toSet() == (1..12).toSet() &&
        (1..12).all { month ->
            val monthItems = items.filter { it.attributionMonth.month == month }
            monthItems.isNotEmpty() && monthItems.none { it.amountWon == null }
        }
    val grouped = items.groupBy(MonthlyPlanItem::analysisIdentity).map { (identity, matches) ->
        CompositionRowSeed(
            id = identity,
            title = matches.first().name,
            supportingText = matches.map { it.category }.filter(String::isNotBlank).distinct().joinToString(", ").ifBlank { null },
            amountWon = matches.map { it.amountWon ?: 0L }.checkedMoneySum(),
        )
    }
    return grouped.toResult(complete)
}

private fun MonthlyPlanItem.analysisIdentity(): String {
    if (catalogId > 0) return "catalog|$catalogId"
    return listOf(
        type.name,
        name.trim(),
        category.trim(),
        ownerMemberOrder?.toString().orEmpty(),
        includePurposeAccount.toString(),
        includeNetSavings.toString(),
    ).joinToString(separator = "|") { value -> "${value.length}:$value" }
}

private data class CompositionRowSeed(
    val id: String,
    val title: String,
    val supportingText: String?,
    val amountWon: Long,
    val expenseCategoryId: String? = null,
)

private fun List<CompositionRowSeed>.toResult(isComplete: Boolean): CompositionAnalysisResult {
    val total = map { it.amountWon }.checkedMoneySum()
    val magnitudes = map { value ->
        if (value.amountWon == Long.MIN_VALUE) throw ArithmeticException("금액 합계가 허용 범위를 넘었어요")
        kotlin.math.abs(value.amountWon)
    }
    val denominator = magnitudes.checkedMoneySum()
    val rows = sortedWith(compareByDescending<CompositionRowSeed> {
        if (it.amountWon == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(it.amountWon)
    }.thenBy { it.title }).map { row ->
        CompositionAnalysisRow(
            id = row.id,
            title = row.title,
            supportingText = row.supportingText,
            amountWon = row.amountWon,
            percent = if (denominator > 0L) row.amountWon.toDouble() * 100.0 / denominator.toDouble() else null,
            expenseCategoryId = row.expenseCategoryId,
        )
    }
    return CompositionAnalysisResult(rows, total, isComplete)
}
