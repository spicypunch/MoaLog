package kr.jm.moalog.feature.plan.domain

import kr.jm.moalog.core.database.ExpenseLocalDataSource
import kr.jm.moalog.core.database.PlanLocalDataSource
import kr.jm.moalog.core.database.PlanCopyPreview
import kr.jm.moalog.core.database.PlanCopyRequest
import kr.jm.moalog.core.database.PlanCopyResult
import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanCatalogItem
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.core.model.sumWonOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class PlanMonth(
    val items: List<MonthlyPlanItem>,
    val variableExpenseTotalWon: Long,
    val hasVariableExpenses: Boolean = variableExpenseTotalWon != 0L,
    val hasVariableExpenseOverflow: Boolean = false,
)
data class PlanYearMonth(
    val month: YearMonthKey,
    val items: List<MonthlyPlanItem>,
    val variableExpenses: List<ExpenseRecord>,
)

interface PlanRepository {
    fun observeMonth(month: YearMonthKey): Flow<PlanMonth>
    fun observeYear(year: Int): Flow<List<PlanYearMonth>>
    suspend fun previewCopy(request: PlanCopyRequest): PlanCopyPreview
    suspend fun copyToMonths(request: PlanCopyRequest): PlanCopyResult
    suspend fun find(id: Long): MonthlyPlanItem?
    suspend fun save(item: MonthlyPlanItem): Long
    suspend fun delete(id: Long)
    fun observeCatalog(): Flow<List<PlanCatalogItem>> = kotlinx.coroutines.flow.flowOf(emptyList())
}

internal class DefaultPlanRepository(
    private val plans: PlanLocalDataSource,
    private val expenses: ExpenseLocalDataSource,
) : PlanRepository {
    override fun observeMonth(month: YearMonthKey): Flow<PlanMonth> = combine(
        plans.observeMonth(month), expenses.observeMonth(month),
    ) { items, expense ->
        val variableExpenseTotal = expense.records.map { it.amountWon }.sumWonOrNull()
        PlanMonth(
            items = items,
            variableExpenseTotalWon = variableExpenseTotal ?: 0L,
            hasVariableExpenses = expense.records.isNotEmpty(),
            hasVariableExpenseOverflow = variableExpenseTotal == null,
        )
    }

    override fun observeYear(year: Int): Flow<List<PlanYearMonth>> = combine(
        (1..12).map { monthNumber ->
            val month = YearMonthKey(year, monthNumber)
            combine(plans.observeMonth(month), expenses.observeMonth(month)) { items, expense ->
                PlanYearMonth(month, items, expense.records)
            }
        },
    ) { snapshots -> snapshots.toList() }
    override suspend fun previewCopy(request: PlanCopyRequest) = plans.previewCopy(request)
    override suspend fun copyToMonths(request: PlanCopyRequest) = plans.copyToMonths(request)
    override suspend fun find(id: Long) = plans.find(id)
    override suspend fun save(item: MonthlyPlanItem) = plans.save(item)
    override suspend fun delete(id: Long) = plans.delete(id)
    override fun observeCatalog() = plans.observeCatalog()
}
