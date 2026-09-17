package kr.jm.moalog.feature.plan.presentation

import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.plan.domain.PlanCurrentMonthProvider
import kr.jm.moalog.feature.plan.domain.PlanMonth
import kr.jm.moalog.feature.plan.domain.PlanRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PlanTab { Income, FixedExpense, VariableExpense, Savings }

data class PlanSummary(
    val incomeWon: Long?,
    val fixedExpenseWon: Long?,
    val variableExpenseWon: Long,
    val hasVariableExpenses: Boolean,
    val totalExpenseWon: Long?,
    val savingsWon: Long?,
    val netSavingsWon: Long?,
    val spendingRate: Double?,
    val savingsRate: Double?,
    val netSavingsRate: Double?,
    val hasMissingAmounts: Boolean,
    val hasIncomeOverflow: Boolean,
    val hasFixedExpenseOverflow: Boolean,
    val hasVariableExpenseOverflow: Boolean,
    val hasTotalExpenseOverflow: Boolean,
    val hasSavingsOverflow: Boolean,
    val hasNetSavingsOverflow: Boolean,
) {
    val hasAnyOverflow: Boolean get() =
        hasIncomeOverflow || hasFixedExpenseOverflow || hasVariableExpenseOverflow ||
            hasTotalExpenseOverflow || hasSavingsOverflow || hasNetSavingsOverflow
}

data class PlanUiState(
    val month: YearMonthKey,
    val selectedTab: PlanTab = PlanTab.Income,
    val items: List<MonthlyPlanItem> = emptyList(),
    val variableExpenseTotalWon: Long = 0,
    val hasVariableExpenses: Boolean = false,
    val hasVariableExpenseOverflow: Boolean = false,
    val summary: PlanSummary = calculatePlanSummary(emptyList(), 0, hasVariableExpenses = false),
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val visibleItems: List<MonthlyPlanItem> get() = items.filter { item ->
        when (selectedTab) {
            PlanTab.Income -> item.type == PlanItemType.Income
            PlanTab.FixedExpense -> item.type == PlanItemType.FixedExpense
            PlanTab.Savings -> item.type == PlanItemType.Savings
            PlanTab.VariableExpense -> false
        }
    }
}

sealed interface PlanAction {
    data object PreviousMonth : PlanAction
    data object NextMonth : PlanAction
    data class SelectTab(val tab: PlanTab) : PlanAction
    data class SelectMonth(val month: YearMonthKey) : PlanAction
    data object Retry : PlanAction
}

fun calculatePlanSummary(
    items: List<MonthlyPlanItem>,
    variableExpenseWon: Long,
    hasVariableExpenses: Boolean = variableExpenseWon != 0L,
    hasVariableExpenseOverflow: Boolean = false,
): PlanSummary {
    fun values(type: PlanItemType) = items.filter { it.type == type }
    fun knownTotal(type: PlanItemType): SafePlanTotal = safePlanTotal(values(type).mapNotNull { it.amountWon })
    val incomeItems = values(PlanItemType.Income)
    val fixedItems = values(PlanItemType.FixedExpense)
    val savingsItems = values(PlanItemType.Savings)
    val netSavingsItems = savingsItems.filter { it.includeNetSavings }
    val incomeTotal = knownTotal(PlanItemType.Income)
    val fixedTotal = knownTotal(PlanItemType.FixedExpense)
    val savingsTotal = knownTotal(PlanItemType.Savings)
    val netSavingsTotal = when {
        savingsItems.isEmpty() -> SafePlanTotal(null, false)
        netSavingsItems.isEmpty() -> SafePlanTotal(0L, false)
        else -> safePlanTotal(netSavingsItems.mapNotNull { it.amountWon })
    }
    val income = incomeTotal.value
    val fixed = fixedTotal.value
    val savings = savingsTotal.value
    val netSavings = netSavingsTotal.value
    val hasAnyMonthData = items.isNotEmpty() || hasVariableExpenses
    val totalExpense = when {
        !hasAnyMonthData -> SafePlanTotal(null, false)
        fixedTotal.overflow || hasVariableExpenseOverflow -> SafePlanTotal(null, true)
        else -> safeAddPlanAmounts(fixed ?: 0L, variableExpenseWon)
    }
    val hasMissing = items.any { it.amountWon == null } ||
        incomeItems.isEmpty() || fixedItems.isEmpty() || savingsItems.isEmpty() || !hasVariableExpenses ||
        incomeTotal.overflow || fixedTotal.overflow || savingsTotal.overflow || netSavingsTotal.overflow ||
        hasVariableExpenseOverflow || totalExpense.overflow
    val completeIncome = income != null && income > 0 && incomeItems.none { it.amountWon == null } && !incomeTotal.overflow
    val completeFixed = fixedItems.isNotEmpty() && fixedItems.none { it.amountWon == null } && !fixedTotal.overflow && !hasVariableExpenseOverflow && !totalExpense.overflow
    val completeSavings = savingsItems.isNotEmpty() && savingsItems.none { it.amountWon == null } && !savingsTotal.overflow && !netSavingsTotal.overflow
    val spendingRate = if (completeIncome && completeFixed) totalExpense.value!! * 100.0 / income else null
    val savingsRate = if (completeIncome && completeSavings) savings!! * 100.0 / income else null
    val netSavingsRate = if (completeIncome && completeSavings) netSavings!! * 100.0 / income else null
    return PlanSummary(
        incomeWon = income,
        fixedExpenseWon = fixed,
        variableExpenseWon = variableExpenseWon,
        hasVariableExpenses = hasVariableExpenses,
        totalExpenseWon = totalExpense.value,
        savingsWon = savings,
        netSavingsWon = netSavings,
        spendingRate = spendingRate,
        savingsRate = savingsRate,
        netSavingsRate = netSavingsRate,
        hasMissingAmounts = hasMissing,
        hasIncomeOverflow = incomeTotal.overflow,
        hasFixedExpenseOverflow = fixedTotal.overflow,
        hasVariableExpenseOverflow = hasVariableExpenseOverflow,
        hasTotalExpenseOverflow = totalExpense.overflow,
        hasSavingsOverflow = savingsTotal.overflow,
        hasNetSavingsOverflow = netSavingsTotal.overflow,
    )
}

private data class SafePlanTotal(val value: Long?, val overflow: Boolean)

private fun safePlanTotal(values: List<Long>): SafePlanTotal {
    if (values.isEmpty()) return SafePlanTotal(null, false)
    var total = 0L
    for (value in values) {
        val next = safeAddPlanAmounts(total, value)
        if (next.overflow) return next
        total = next.value!!
    }
    return SafePlanTotal(total, false)
}

private fun safeAddPlanAmounts(left: Long, right: Long): SafePlanTotal {
    val sum = left + right
    val overflow = ((left xor sum) and (right xor sum)) < 0L
    return if (overflow) SafePlanTotal(null, true) else SafePlanTotal(sum, false)
}

class PlanStateHolder(
    private val repository: PlanRepository,
    currentMonthProvider: PlanCurrentMonthProvider,
    parentScope: CoroutineScope,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutableState = MutableStateFlow(PlanUiState(currentMonthProvider.currentMonth()))
    val state: StateFlow<PlanUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var started = false

    fun start() { if (!started) { started = true; load(mutableState.value.month) } }
    fun pause() { loadJob?.cancel(); loadJob = null; started = false }
    fun close() = job.cancel()
    fun onAction(action: PlanAction) = when (action) {
        PlanAction.PreviousMonth -> mutableState.value.month
            .takeUnless { it.year == YearMonthKey.MIN_YEAR && it.month == 1 }
            ?.let { load(it.plusMonths(-1)) }
            ?: Unit
        PlanAction.NextMonth -> mutableState.value.month
            .takeUnless { it.year == 9999 && it.month == 12 }
            ?.let { load(it.plusMonths(1)) }
            ?: Unit
        is PlanAction.SelectTab -> mutableState.value = mutableState.value.copy(selectedTab = action.tab)
        is PlanAction.SelectMonth -> load(action.month)
        PlanAction.Retry -> load(mutableState.value.month)
    }

    private fun load(month: YearMonthKey) {
        loadJob?.cancel()
        mutableState.value = mutableState.value.copy(
            month = month,
            items = emptyList(),
            variableExpenseTotalWon = 0,
            hasVariableExpenses = false,
            hasVariableExpenseOverflow = false,
            summary = calculatePlanSummary(emptyList(), 0, hasVariableExpenses = false),
            isLoading = true,
            error = null,
        )
        loadJob = scope.launch {
            try {
                repository.observeMonth(month).collect { snapshot -> mutableState.value = mutableState.value.from(snapshot) }
            } catch (e: CancellationException) { throw e }
            catch (_: Throwable) { mutableState.value = mutableState.value.copy(isLoading = false, error = "월별 계획을 불러오지 못했어요") }
        }
    }
}

private fun PlanUiState.from(month: PlanMonth) = copy(
    items = month.items,
    variableExpenseTotalWon = month.variableExpenseTotalWon,
    hasVariableExpenses = month.hasVariableExpenses,
    hasVariableExpenseOverflow = month.hasVariableExpenseOverflow,
    summary = calculatePlanSummary(
        items = month.items,
        variableExpenseWon = month.variableExpenseTotalWon,
        hasVariableExpenses = month.hasVariableExpenses,
        hasVariableExpenseOverflow = month.hasVariableExpenseOverflow,
    ),
    isLoading = false,
    error = null,
)
