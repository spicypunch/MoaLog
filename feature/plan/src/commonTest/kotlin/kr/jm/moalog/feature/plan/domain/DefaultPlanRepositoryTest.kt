package kr.jm.moalog.feature.plan.domain

import kr.jm.moalog.core.database.ExpenseLocalDataSource
import kr.jm.moalog.core.database.ExpenseMonthSnapshot
import kr.jm.moalog.core.database.PlanLocalDataSource
import kr.jm.moalog.core.database.PlanCopyPreview
import kr.jm.moalog.core.database.PlanCopyRequest
import kr.jm.moalog.core.database.PlanCopyResult
import kr.jm.moalog.core.database.FixedCostApplyResult
import kr.jm.moalog.core.database.FixedCostPlanPreview
import kr.jm.moalog.core.database.ExistingPlanPolicy
import kr.jm.moalog.core.model.FixedCostCheckItem
import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultPlanRepositoryTest {
    @Test
    fun observeYearReadsAllTwelvePlanAndExpenseMonths() = runTest {
        val repository = DefaultPlanRepository(FakePlans, FakeExpenses)

        val year = repository.observeYear(2028).first()

        assertEquals((1..12).toList(), year.map { it.month.month })
        assertEquals(1_000L, year[0].items.single().amountWon)
        assertEquals(200L, year[1].variableExpenses.single().amountWon)
    }

    @Test
    fun observeMonthReportsMaxPlusOneVariableExpenseOverflowWithoutWrapping() = runTest {
        val repository = DefaultPlanRepository(FakePlans, OverflowExpenses)

        val month = repository.observeMonth(YearMonthKey(2028, 1)).first()

        assertEquals(0L, month.variableExpenseTotalWon)
        assertEquals(true, month.hasVariableExpenses)
        assertEquals(true, month.hasVariableExpenseOverflow)
    }

    private object FakePlans : PlanLocalDataSource {
        override fun observeMonth(month: YearMonthKey): Flow<List<MonthlyPlanItem>> = flowOf(
            if (month.month == 1) listOf(
                MonthlyPlanItem(
                    type = PlanItemType.Income,
                    attributionMonth = month,
                    name = "급여",
                    amountWon = 1_000,
                    category = "근로소득",
                    status = PlanItemStatus.Confirmed,
                    ownerMemberOrder = null,
                    memo = null,
                ),
            ) else emptyList(),
        )
        override suspend fun previewCopy(request: PlanCopyRequest): PlanCopyPreview = error("unused")
        override suspend fun copyToMonths(request: PlanCopyRequest): PlanCopyResult = error("unused")
        override suspend fun find(id: Long): MonthlyPlanItem? = null
        override suspend fun save(item: MonthlyPlanItem): Long = item.id
        override suspend fun delete(id: Long) = Unit
        override suspend fun previewFixedCosts(month: YearMonthKey, items: List<FixedCostCheckItem>): FixedCostPlanPreview = error("unused")
        override suspend fun applyFixedCosts(month: YearMonthKey, items: List<FixedCostCheckItem>, policy: ExistingPlanPolicy): FixedCostApplyResult = error("unused")
    }

    private object FakeExpenses : ExpenseLocalDataSource {
        override suspend fun ensureDefaultCategories() = Unit
        override fun observeMonth(month: YearMonthKey): Flow<ExpenseMonthSnapshot> = flowOf(
            ExpenseMonthSnapshot(
                emptyList(),
                if (month.month == 2) listOf(
                    ExpenseRecord(
                        categoryId = "food",
                        categoryName = "식비",
                        attributionMonth = month,
                        actualDate = null,
                        detail = null,
                        amountWon = 200,
                        overspent = false,
                    ),
                ) else emptyList(),
            ),
        )
        override suspend fun findRecord(id: Long): ExpenseRecord? = null
        override suspend fun saveRecord(record: ExpenseRecord): Long = record.id
        override suspend fun deleteRecord(id: Long) = Unit
        override suspend fun renameCategory(categoryId: String, name: String) = Unit
    }

    private object OverflowExpenses : ExpenseLocalDataSource {
        override suspend fun ensureDefaultCategories() = Unit
        override fun observeMonth(month: YearMonthKey): Flow<ExpenseMonthSnapshot> = flowOf(
            ExpenseMonthSnapshot(
                emptyList(),
                listOf(Long.MAX_VALUE, 1L).mapIndexed { index, amount ->
                    ExpenseRecord(
                        id = index.toLong() + 1,
                        categoryId = "food",
                        categoryName = "식비",
                        attributionMonth = month,
                        actualDate = null,
                        detail = null,
                        amountWon = amount,
                        overspent = false,
                    )
                },
            ),
        )
        override suspend fun findRecord(id: Long): ExpenseRecord? = null
        override suspend fun saveRecord(record: ExpenseRecord): Long = record.id
        override suspend fun deleteRecord(id: Long) = Unit
        override suspend fun renameCategory(categoryId: String, name: String) = Unit
    }
}
