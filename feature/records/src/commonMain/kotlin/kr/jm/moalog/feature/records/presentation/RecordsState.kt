package kr.jm.moalog.feature.records.presentation

import kr.jm.moalog.core.model.ExpenseCategory
import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.LocalDateKey
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.core.model.sumWonOrNull
import kr.jm.moalog.feature.records.domain.CurrentDateProvider
import kr.jm.moalog.feature.records.domain.CurrentMonthProvider
import kr.jm.moalog.feature.records.domain.ExpenseMonth
import kr.jm.moalog.feature.records.domain.ExpenseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ExpenseSort { Latest, Oldest }

data class ExpenseFilters(
    val categoryIds: Set<String> = emptySet(),
    val overspentOnly: Boolean = false,
    val sort: ExpenseSort = ExpenseSort.Latest,
) {
    val hasSubtotalFilter: Boolean get() = categoryIds.isNotEmpty() || overspentOnly
}

data class RecordsPreferencesSnapshot(val month: YearMonthKey, val filters: ExpenseFilters)

enum class ExpenseComparisonPeriod { SameDayPriorMonth, FullPriorMonth }

data class ExpenseComparison(
    val period: ExpenseComparisonPeriod,
    val selectedPeriodTotalWon: Long = 0,
    val priorPeriodTotalWon: Long = 0,
    val differenceWon: Long = 0,
    val selectedSpentMore: Boolean = false,
    val isEqual: Boolean = false,
    val hasOverflow: Boolean = false,
)

data class RecordsUiState(
    val month: YearMonthKey,
    val categories: List<ExpenseCategory> = emptyList(),
    val records: List<ExpenseRecord> = emptyList(),
    val visibleRecords: List<ExpenseRecord> = emptyList(),
    val fullMonthTotalWon: Long = 0,
    val fullMonthOverspentWon: Long = 0,
    val filteredSubtotalWon: Long = 0,
    val categoryTotalsWon: Map<String, Long> = emptyMap(),
    val hasFullMonthTotalOverflow: Boolean = false,
    val hasFullMonthOverspentOverflow: Boolean = false,
    val hasFilteredSubtotalOverflow: Boolean = false,
    val categoryTotalOverflowIds: Set<String> = emptySet(),
    val plannedVariableExpenseWon: Long? = null,
    val budgetUsagePercent: Double? = null,
    val comparison: ExpenseComparison? = null,
    val filters: ExpenseFilters = ExpenseFilters(),
    val isLoading: Boolean = true,
    val loadError: String? = null,
) {
    fun toPreferencesSnapshot() = RecordsPreferencesSnapshot(month, filters)
}

sealed interface RecordsAction {
    data class SelectMonth(val month: YearMonthKey) : RecordsAction
    data object PreviousMonth : RecordsAction
    data object NextMonth : RecordsAction
    data class CategoryToggled(val categoryId: String) : RecordsAction
    data object ClearCategories : RecordsAction
    data object OverspentOnlyToggled : RecordsAction
    data object SortToggled : RecordsAction
    data class FiltersApplied(val filters: ExpenseFilters) : RecordsAction
    data object ClearFilters : RecordsAction
    data class RestorePreferences(val snapshot: RecordsPreferencesSnapshot) : RecordsAction
    data object RefreshCurrentDate : RecordsAction
    data object Retry : RecordsAction
}

internal fun buildRecordsState(
    month: YearMonthKey,
    snapshot: ExpenseMonth,
    filters: ExpenseFilters,
    priorSnapshot: ExpenseMonth? = null,
    today: LocalDateKey? = null,
    isLoading: Boolean = false,
    loadError: String? = null,
): RecordsUiState {
    val normalizedFilters = filters.copy(
        categoryIds = filters.categoryIds.intersect(snapshot.categories.mapTo(mutableSetOf(), ExpenseCategory::id)),
    )
    val filtered = snapshot.records.filter { record ->
        (normalizedFilters.categoryIds.isEmpty() || record.categoryId in normalizedFilters.categoryIds) &&
            (!normalizedFilters.overspentOnly || record.overspent)
    }
    val fullTotal = snapshot.records.map(ExpenseRecord::amountWon).sumWonOrNull()
    val overspentTotal = snapshot.records.filter(ExpenseRecord::overspent).map(ExpenseRecord::amountWon).sumWonOrNull()
    val filteredTotal = filtered.map(ExpenseRecord::amountWon).sumWonOrNull()
    val categoryTotals = mutableMapOf<String, Long>()
    val categoryOverflows = mutableSetOf<String>()
    snapshot.categories.forEach { category ->
        val total = snapshot.records.filter { it.categoryId == category.id }.map(ExpenseRecord::amountWon).sumWonOrNull()
        categoryTotals[category.id] = total ?: 0L
        if (total == null) categoryOverflows += category.id
    }
    val planned = snapshot.plannedVariableExpenseWon?.takeIf { it >= 0 }
    val budgetUsage = if (fullTotal != null && planned != null && planned > 0L) {
        fullTotal.toDouble() / planned.toDouble() * 100.0
    } else null
    return RecordsUiState(
        month = month,
        categories = snapshot.categories,
        records = snapshot.records,
        visibleRecords = filtered.sortedWith(recordComparator(normalizedFilters.sort)),
        fullMonthTotalWon = fullTotal ?: 0L,
        fullMonthOverspentWon = overspentTotal ?: 0L,
        filteredSubtotalWon = filteredTotal ?: 0L,
        categoryTotalsWon = categoryTotals,
        hasFullMonthTotalOverflow = fullTotal == null,
        hasFullMonthOverspentOverflow = overspentTotal == null,
        hasFilteredSubtotalOverflow = filteredTotal == null,
        categoryTotalOverflowIds = categoryOverflows,
        plannedVariableExpenseWon = planned,
        budgetUsagePercent = budgetUsage,
        comparison = buildExpenseComparison(month, snapshot.records, priorSnapshot?.records, today),
        filters = normalizedFilters,
        isLoading = isLoading,
        loadError = loadError,
    )
}

fun expenseGroupTotalWon(records: List<ExpenseRecord>): Long? =
    records.map(ExpenseRecord::amountWon).sumWonOrNull()

internal fun buildExpenseComparison(
    selectedMonth: YearMonthKey,
    selectedRecords: List<ExpenseRecord>,
    priorRecords: List<ExpenseRecord>?,
    today: LocalDateKey?,
): ExpenseComparison? {
    if (priorRecords == null || today == null || selectedMonth.index() > today.yearMonth.index()) return null
    val current = selectedMonth == today.yearMonth
    val selectedComparable = if (current) selectedRecords.filter { record ->
        record.actualDate?.let { it.yearMonth == selectedMonth && it.day <= today.day } == true
    } else selectedRecords
    if (current && selectedComparable.isEmpty()) return null
    val priorMonth = selectedMonth.plusMonthsOrNull(-1) ?: return null
    val priorComparable = if (current) priorRecords.filter { record ->
        record.actualDate?.let { it.yearMonth == priorMonth && it.day <= today.day } == true
    } else priorRecords
    val selectedTotal = selectedComparable.map(ExpenseRecord::amountWon).sumWonOrNull()
    val priorTotal = priorComparable.map(ExpenseRecord::amountWon).sumWonOrNull()
    val period = if (current) ExpenseComparisonPeriod.SameDayPriorMonth else ExpenseComparisonPeriod.FullPriorMonth
    if (selectedTotal == null || priorTotal == null) return ExpenseComparison(period, hasOverflow = true)
    return ExpenseComparison(
        period = period,
        selectedPeriodTotalWon = selectedTotal,
        priorPeriodTotalWon = priorTotal,
        differenceWon = if (selectedTotal >= priorTotal) selectedTotal - priorTotal else priorTotal - selectedTotal,
        selectedSpentMore = selectedTotal > priorTotal,
        isEqual = selectedTotal == priorTotal,
    )
}

private fun YearMonthKey.index(): Long = year.toLong() * 12L + month

private fun recordComparator(sort: ExpenseSort): Comparator<ExpenseRecord> = Comparator { left, right ->
    val leftDate = left.actualDate
    val rightDate = right.actualDate
    when {
        leftDate == null && rightDate != null -> 1
        leftDate != null && rightDate == null -> -1
        leftDate != null && rightDate != null -> {
            val result = leftDate.compareTo(rightDate)
            if (result != 0) if (sort == ExpenseSort.Latest) -result else result
            else if (sort == ExpenseSort.Latest) right.id.compareTo(left.id) else left.id.compareTo(right.id)
        }
        else -> if (sort == ExpenseSort.Latest) right.id.compareTo(left.id) else left.id.compareTo(right.id)
    }
}

class RecordsStateHolder(
    private val repository: ExpenseRepository,
    currentMonthProvider: CurrentMonthProvider,
    private val currentDateProvider: CurrentDateProvider,
    parentScope: CoroutineScope,
) {
    constructor(repository: ExpenseRepository, currentMonthProvider: CurrentMonthProvider, parentScope: CoroutineScope) :
        this(repository, currentMonthProvider, CurrentDateProvider {
            val month = currentMonthProvider.currentMonth()
            LocalDateKey(month.year, month.month, 1)
        }, parentScope)

    private val holderJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + holderJob)
    private val mutableState = MutableStateFlow(RecordsUiState(month = currentMonthProvider.currentMonth()))
    val state: StateFlow<RecordsUiState> = mutableState.asStateFlow()
    private var monthJob: Job? = null
    private var latestPriorSnapshot: ExpenseMonth? = null
    private var latestToday: LocalDateKey? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        loadMonth(mutableState.value.month)
    }

    fun close() {
        monthJob?.cancel()
        holderJob.cancel()
    }

    fun pause() {
        monthJob?.cancel()
        monthJob = null
        started = false
    }

    fun onAction(action: RecordsAction) {
        when (action) {
            is RecordsAction.SelectMonth -> loadMonth(action.month)
            RecordsAction.PreviousMonth -> mutableState.value.month.plusMonthsOrNull(-1)?.let(::loadMonth)
            RecordsAction.NextMonth -> mutableState.value.month.plusMonthsOrNull(1)?.let(::loadMonth)
            is RecordsAction.CategoryToggled -> updateFilters {
                copy(categoryIds = if (action.categoryId in categoryIds) categoryIds - action.categoryId else categoryIds + action.categoryId)
            }
            RecordsAction.ClearCategories -> updateFilters { copy(categoryIds = emptySet()) }
            RecordsAction.OverspentOnlyToggled -> updateFilters { copy(overspentOnly = !overspentOnly) }
            RecordsAction.SortToggled -> updateFilters { copy(sort = if (sort == ExpenseSort.Latest) ExpenseSort.Oldest else ExpenseSort.Latest) }
            is RecordsAction.FiltersApplied -> updateFilters { action.filters }
            RecordsAction.ClearFilters -> updateFilters { ExpenseFilters() }
            is RecordsAction.RestorePreferences -> {
                mutableState.update { it.copy(filters = action.snapshot.filters) }
                loadMonth(action.snapshot.month)
            }
            RecordsAction.RefreshCurrentDate -> refreshCurrentDate()
            RecordsAction.Retry -> loadMonth(mutableState.value.month)
        }
    }

    private fun refreshCurrentDate() {
        val current = mutableState.value
        if (current.isLoading || current.loadError != null) return
        latestToday = currentDateProvider.currentDate()
        mutableState.value = buildRecordsState(
            current.month,
            ExpenseMonth(current.categories, current.records, current.plannedVariableExpenseWon),
            current.filters,
            latestPriorSnapshot,
            latestToday,
        )
    }

    private fun updateFilters(transform: ExpenseFilters.() -> ExpenseFilters) {
        mutableState.update { current ->
            val updatedFilters = current.filters.transform()
            if (current.isLoading) current.copy(filters = updatedFilters)
            else buildRecordsState(
                    month = current.month,
                    snapshot = ExpenseMonth(current.categories, current.records, current.plannedVariableExpenseWon),
                    filters = updatedFilters,
                    priorSnapshot = latestPriorSnapshot,
                    today = latestToday,
                    isLoading = false,
                    loadError = current.loadError,
                )
        }
    }

    private fun loadMonth(month: YearMonthKey) {
        monthJob?.cancel()
        latestPriorSnapshot = null
        val filters = mutableState.value.filters
        mutableState.value = RecordsUiState(month = month, filters = filters, isLoading = true)
        monthJob = scope.launch {
            try {
                repository.prepareCategories()
                val priorFlow = month.plusMonthsOrNull(-1)?.let(repository::observeMonth) ?: flowOf(null)
                combine(repository.observeMonth(month), priorFlow) { selected, prior -> selected to prior }.collect { (selected, prior) ->
                    latestPriorSnapshot = prior
                    latestToday = currentDateProvider.currentDate()
                    mutableState.value = buildRecordsState(month, selected, mutableState.value.filters, prior, latestToday)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.update { it.copy(isLoading = false, loadError = "기록을 불러오지 못했어요. 다시 시도해 주세요") }
            }
        }
    }
}
