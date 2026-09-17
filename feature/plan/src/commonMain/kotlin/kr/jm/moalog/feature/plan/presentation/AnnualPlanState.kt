package kr.jm.moalog.feature.plan.presentation

import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.plan.domain.PlanCurrentMonthProvider
import kr.jm.moalog.feature.plan.domain.PlanRepository
import kr.jm.moalog.feature.plan.domain.PlanYearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AnnualPlanArgs(val year: Int)

enum class AnnualPlanSection { Income, FixedExpense, VariableExpense, Savings }
enum class AnnualPlanViewMode { Table, MonthlyList }

enum class AnnualCellStatus { Confirmed, Estimated, Mixed, Missing, Partial, Zero }

data class AnnualCell(
    val month: YearMonthKey,
    val amountWon: Long?,
    val status: AnnualCellStatus,
    val sourceItemId: Long? = null,
) {
    val isComplete: Boolean get() = status !in setOf(AnnualCellStatus.Missing, AnnualCellStatus.Partial)
}

data class AnnualPlanRow(
    val key: String,
    val name: String,
    val category: String,
    val ownerMemberOrder: Int?,
    val planType: PlanItemType?,
    val cells: List<AnnualCell>,
) {
    val knownTotalWon: Long get() = cells.map { it.amountWon ?: 0L }.checkedAnnualMoneySum()
    val isComplete: Boolean get() = cells.all(AnnualCell::isComplete)
}

data class AnnualMonthSummary(
    val month: YearMonthKey,
    val income: AnnualCell,
    val fixedExpense: AnnualCell,
    val variableExpense: AnnualCell,
    val savings: AnnualCell,
    val balanceWon: Long?,
)

data class AnnualPlanUiState(
    val year: Int,
    val currentMonth: YearMonthKey,
    val selectedSection: AnnualPlanSection = AnnualPlanSection.Income,
    val viewMode: AnnualPlanViewMode = AnnualPlanViewMode.Table,
    val rowsBySection: Map<AnnualPlanSection, List<AnnualPlanRow>> = emptyMap(),
    val months: List<AnnualMonthSummary> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val rows: List<AnnualPlanRow> get() = rowsBySection[selectedSection].orEmpty()
    val selectedCells: List<AnnualCell> get() = months.map { month ->
        when (selectedSection) {
            AnnualPlanSection.Income -> month.income
            AnnualPlanSection.FixedExpense -> month.fixedExpense
            AnnualPlanSection.VariableExpense -> month.variableExpense
            AnnualPlanSection.Savings -> month.savings
        }
    }
    val knownTotalWon: Long get() = selectedCells.map { it.amountWon ?: 0L }.checkedAnnualMoneySum()
    val hasKnownAmounts: Boolean get() = selectedCells.any { it.amountWon != null }
    val isYearComplete: Boolean get() = selectedCells.size == 12 && selectedCells.all(AnnualCell::isComplete)
    val monthlyAverageWon: Long? get() = knownTotalWon.takeIf { isYearComplete && hasKnownAmounts }?.div(12)
}

sealed interface AnnualPlanAction {
    data class SelectSection(val section: AnnualPlanSection) : AnnualPlanAction
    data class SelectViewMode(val mode: AnnualPlanViewMode) : AnnualPlanAction
    data object Retry : AnnualPlanAction
}

fun calculateAnnualPlan(
    year: Int,
    snapshots: List<PlanYearMonth>,
): Pair<Map<AnnualPlanSection, List<AnnualPlanRow>>, List<AnnualMonthSummary>> {
    val byMonth = snapshots.associateBy { it.month.month }
    val completeSnapshots = (1..12).map { month ->
        byMonth[month] ?: PlanYearMonth(YearMonthKey(year, month), emptyList(), emptyList())
    }
    val rows = mapOf(
        AnnualPlanSection.Income to planRows(completeSnapshots, PlanItemType.Income),
        AnnualPlanSection.FixedExpense to planRows(completeSnapshots, PlanItemType.FixedExpense),
        AnnualPlanSection.VariableExpense to variableExpenseRows(completeSnapshots),
        AnnualPlanSection.Savings to planRows(completeSnapshots, PlanItemType.Savings),
    )
    val summaries = completeSnapshots.map { snapshot ->
        val income = aggregatePlanCell(snapshot.month, snapshot.items.filter { it.type == PlanItemType.Income })
        val fixed = aggregatePlanCell(snapshot.month, snapshot.items.filter { it.type == PlanItemType.FixedExpense })
        val savings = aggregatePlanCell(snapshot.month, snapshot.items.filter { it.type == PlanItemType.Savings })
        val variable = AnnualCell(
            month = snapshot.month,
            amountWon = snapshot.variableExpenses.map { it.amountWon }.checkedAnnualMoneySum(),
            status = if (snapshot.variableExpenses.isEmpty()) AnnualCellStatus.Zero else AnnualCellStatus.Confirmed,
        )
        val balance = if (income.isComplete && fixed.isComplete && savings.isComplete) {
            listOf(fixed.amountWon!!, variable.amountWon!!, savings.amountWon!!)
                .fold(income.amountWon!!, ::checkedAnnualMoneySubtract)
        } else null
        AnnualMonthSummary(snapshot.month, income, fixed, variable, savings, balance)
    }
    rows.values.flatten().forEach { it.knownTotalWon }
    AnnualPlanSection.entries.forEach { section ->
        summaries.map { month ->
            when (section) {
                AnnualPlanSection.Income -> month.income.amountWon
                AnnualPlanSection.FixedExpense -> month.fixedExpense.amountWon
                AnnualPlanSection.VariableExpense -> month.variableExpense.amountWon
                AnnualPlanSection.Savings -> month.savings.amountWon
            } ?: 0L
        }.checkedAnnualMoneySum()
    }
    return rows to summaries
}

private data class PlanRowKey(
    val catalogId: Long,
    val type: PlanItemType,
    val name: String,
    val category: String,
    val ownerMemberOrder: Int?,
    val includePurposeAccount: Boolean,
    val includeNetSavings: Boolean,
)

private fun planRows(snapshots: List<PlanYearMonth>, type: PlanItemType): List<AnnualPlanRow> {
    val keys = snapshots.flatMap { it.items }.filter { it.type == type }.map { item ->
        PlanRowKey(item.catalogId, item.type, item.name, item.category, item.ownerMemberOrder, item.includePurposeAccount, item.includeNetSavings)
    }.distinctBy { key ->
        if (key.catalogId > 0) "catalog|${key.catalogId}"
        else listOf(key.type, key.name, key.category, key.ownerMemberOrder, key.includePurposeAccount, key.includeNetSavings).joinToString("|")
    }
    return keys.map { key ->
        AnnualPlanRow(
            key = if (key.catalogId > 0) "catalog|${key.catalogId}"
            else listOf(key.type.name, key.name, key.category, key.ownerMemberOrder, key.includePurposeAccount, key.includeNetSavings).joinToString("|"),
            name = key.name,
            category = key.category,
            ownerMemberOrder = key.ownerMemberOrder,
            planType = key.type,
            cells = snapshots.map { snapshot ->
                aggregatePlanCell(snapshot.month, snapshot.items.filter { item ->
                    if (key.catalogId > 0) item.catalogId == key.catalogId else
                    item.type == key.type && item.name == key.name && item.category == key.category &&
                        item.ownerMemberOrder == key.ownerMemberOrder &&
                        item.includePurposeAccount == key.includePurposeAccount &&
                        item.includeNetSavings == key.includeNetSavings
                })
            },
        )
    }
}

private fun variableExpenseRows(snapshots: List<PlanYearMonth>): List<AnnualPlanRow> {
    val categories = snapshots.flatMap { it.variableExpenses }
        .associate { it.categoryId to it.categoryName }
        .toList()
    return categories.map { (categoryId, categoryName) ->
        AnnualPlanRow(
            key = "expense|$categoryId",
            name = categoryName,
            category = "변동지출",
            ownerMemberOrder = null,
            planType = null,
            cells = snapshots.map { snapshot ->
                val amount = snapshot.variableExpenses.filter { it.categoryId == categoryId }
                    .map { it.amountWon }
                    .checkedAnnualMoneySum()
                AnnualCell(snapshot.month, amount, if (amount == 0L) AnnualCellStatus.Zero else AnnualCellStatus.Confirmed)
            },
        )
    }
}

private fun aggregatePlanCell(month: YearMonthKey, items: List<kr.jm.moalog.core.model.MonthlyPlanItem>): AnnualCell {
    if (items.isEmpty()) return AnnualCell(month, null, AnnualCellStatus.Missing)
    val sourceItemId = items.singleOrNull()?.id?.takeIf { it > 0 }
    val known = items.mapNotNull { it.amountWon }
    if (items.any { it.amountWon == null }) {
        return AnnualCell(month, known.takeIf { it.isNotEmpty() }?.checkedAnnualMoneySum(), AnnualCellStatus.Partial, sourceItemId)
    }
    val total = known.checkedAnnualMoneySum()
    if (total == 0L) return AnnualCell(month, 0, AnnualCellStatus.Zero, sourceItemId)
    val statuses = items.map { it.status }.distinct()
    return AnnualCell(
        month,
        total,
        when {
            statuses.size > 1 -> AnnualCellStatus.Mixed
            statuses.single() == PlanItemStatus.Confirmed -> AnnualCellStatus.Confirmed
            else -> AnnualCellStatus.Estimated
        },
        sourceItemId,
    )
}

private fun Iterable<Long>.checkedAnnualMoneySum(): Long {
    var total = 0L
    for (amount in this) {
        if (amount > 0L && total > Long.MAX_VALUE - amount) throw ArithmeticException("Money total overflow")
        if (amount < 0L && total < Long.MIN_VALUE - amount) throw ArithmeticException("Money total overflow")
        total += amount
    }
    return total
}

private fun checkedAnnualMoneySubtract(left: Long, right: Long): Long {
    if (right > 0L && left < Long.MIN_VALUE + right) throw ArithmeticException("Money subtraction overflow")
    if (right < 0L && left > Long.MAX_VALUE + right) throw ArithmeticException("Money subtraction overflow")
    return left - right
}

class AnnualPlanStateHolder(
    private val args: AnnualPlanArgs,
    private val repository: PlanRepository,
    currentMonthProvider: PlanCurrentMonthProvider,
    parentScope: CoroutineScope,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutableState = MutableStateFlow(AnnualPlanUiState(args.year, currentMonthProvider.currentMonth()))
    val state: StateFlow<AnnualPlanUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        load()
    }

    fun close() = job.cancel()

    fun onAction(action: AnnualPlanAction) {
        when (action) {
            is AnnualPlanAction.SelectSection -> mutableState.value = mutableState.value.copy(selectedSection = action.section)
            is AnnualPlanAction.SelectViewMode -> mutableState.value = mutableState.value.copy(viewMode = action.mode)
            AnnualPlanAction.Retry -> load()
        }
    }

    private fun load() {
        loadJob?.cancel()
        mutableState.value = mutableState.value.copy(isLoading = true, error = null)
        loadJob = scope.launch {
            try {
                repository.observeYear(args.year).collect { snapshots ->
                    val (rows, months) = calculateAnnualPlan(args.year, snapshots)
                    mutableState.value = mutableState.value.copy(rowsBySection = rows, months = months, isLoading = false, error = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(isLoading = false, error = "연간 계획을 불러오지 못했어요")
            }
        }
    }
}
