package kr.jm.moalog.feature.plan.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kr.jm.moalog.core.database.PlanCopyPreview
import kr.jm.moalog.core.database.PlanCopyRequest
import kr.jm.moalog.core.database.PlanCopyResult
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.plan.domain.PlanCurrentMonthProvider
import kr.jm.moalog.feature.plan.domain.PlanMonth
import kr.jm.moalog.feature.plan.domain.PlanRepository
import kr.jm.moalog.feature.plan.domain.PlanYearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PlanSummaryTest {
    @Test fun calculatesTotalsAndUsesEverySavingsItemForTheSavingsRate() {
        val items = listOf(
            item(PlanItemType.Income, 1_000_000),
            item(PlanItemType.FixedExpense, 300_000),
            item(PlanItemType.Savings, 150_000, purpose = false, net = true),
            item(PlanItemType.Savings, 50_000, purpose = false, net = false),
        )
        val summary = calculatePlanSummary(items, 100_000)
        assertEquals(400_000, summary.totalExpenseWon)
        assertEquals(200_000, summary.savingsWon)
        assertEquals(150_000, summary.netSavingsWon)
        assertEquals(40.0, summary.spendingRate)
        assertEquals(20.0, summary.savingsRate)
        assertEquals(15.0, summary.netSavingsRate)
        assertEquals(false, summary.hasMissingAmounts)
    }

    @Test fun partialFixedInputPreservesKnownExpenseTotalButNotRate() {
        val summary = calculatePlanSummary(
            listOf(
                item(PlanItemType.Income, 1_000_000),
                item(PlanItemType.FixedExpense, 1_240_000),
                item(PlanItemType.FixedExpense, null),
            ),
            100_000,
        )
        assertEquals(1_000_000, summary.incomeWon)
        assertEquals(1_240_000, summary.fixedExpenseWon)
        assertEquals(1_340_000, summary.totalExpenseWon)
        assertNull(summary.spendingRate)
    }

    @Test fun missingSavingsAmountMakesSavingsTotalsAndRatesUnavailable() {
        val summary = calculatePlanSummary(
            listOf(
                item(PlanItemType.Income, 1_000_000),
                item(PlanItemType.FixedExpense, 300_000),
                item(PlanItemType.Savings, 150_000, net = true),
                item(PlanItemType.Savings, null),
            ),
            100_000,
        )

        assertEquals(150_000, summary.savingsWon)
        assertEquals(150_000, summary.netSavingsWon)
        assertNull(summary.savingsRate)
        assertNull(summary.netSavingsRate)
    }

    @Test fun allNullItemsRemainMissingWhileKnownVariableExpenseStillContributesToTotal() {
        val summary = calculatePlanSummary(
            items = listOf(item(PlanItemType.FixedExpense, null)),
            variableExpenseWon = 100_000,
            hasVariableExpenses = true,
        )

        assertNull(summary.fixedExpenseWon)
        assertEquals(100_000, summary.totalExpenseWon)
        assertEquals(true, summary.hasMissingAmounts)
    }

    @Test fun zeroWonVariableRecordIsDifferentFromACompletelyEmptyMonth() {
        val emptyMonth = calculatePlanSummary(emptyList(), variableExpenseWon = 0, hasVariableExpenses = false)
        val zeroWonRecordMonth = calculatePlanSummary(emptyList(), variableExpenseWon = 0, hasVariableExpenses = true)

        assertNull(emptyMonth.totalExpenseWon)
        assertEquals(0, zeroWonRecordMonth.totalExpenseWon)
        assertEquals(false, emptyMonth.hasVariableExpenses)
        assertEquals(true, zeroWonRecordMonth.hasVariableExpenses)
        assertEquals(true, emptyMonth.hasMissingAmounts)
        assertEquals(true, zeroWonRecordMonth.hasMissingAmounts)
    }

    @Test fun absentWholeCategoryIsMissingEvenWithoutNullItems() {
        val summary = calculatePlanSummary(
            items = listOf(
                item(PlanItemType.Income, 1_000_000),
                item(PlanItemType.FixedExpense, 300_000),
            ),
            variableExpenseWon = 0,
            hasVariableExpenses = true,
        )

        assertNull(summary.savingsWon)
        assertEquals(true, summary.hasMissingAmounts)
    }

    @Test fun explicitZeroVariableRecordCompletesSummaryWhenPlanCategoriesArePresent() {
        val summary = calculatePlanSummary(
            items = listOf(
                item(PlanItemType.Income, 1_000_000),
                item(PlanItemType.FixedExpense, 300_000),
                item(PlanItemType.Savings, 200_000),
            ),
            variableExpenseWon = 0,
            hasVariableExpenses = true,
        )

        assertEquals(0, summary.variableExpenseWon)
        assertEquals(300_000, summary.totalExpenseWon)
        assertEquals(false, summary.hasMissingAmounts)
    }

    @Test fun monthlyPlanTotalsReportMaxPlusOneAsOverflowWithoutWrapping() {
        val incomeOverflow = calculatePlanSummary(
            items = listOf(
                item(PlanItemType.Income, Long.MAX_VALUE),
                item(PlanItemType.Income, 1),
            ),
            variableExpenseWon = 0,
            hasVariableExpenses = true,
        )
        val expenseOverflow = calculatePlanSummary(
            items = listOf(item(PlanItemType.FixedExpense, Long.MAX_VALUE)),
            variableExpenseWon = 1,
            hasVariableExpenses = true,
        )
        val savingsOverflow = calculatePlanSummary(
            items = listOf(
                item(PlanItemType.Savings, Long.MAX_VALUE, net = true),
                item(PlanItemType.Savings, 1, net = true),
            ),
            variableExpenseWon = 0,
            hasVariableExpenses = true,
        )

        assertNull(incomeOverflow.incomeWon)
        assertEquals(true, incomeOverflow.hasIncomeOverflow)
        assertNull(expenseOverflow.totalExpenseWon)
        assertEquals(true, expenseOverflow.hasTotalExpenseOverflow)
        assertNull(savingsOverflow.savingsWon)
        assertNull(savingsOverflow.netSavingsWon)
        assertEquals(true, savingsOverflow.hasSavingsOverflow)
        assertEquals(true, savingsOverflow.hasNetSavingsOverflow)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun repositoryVariableExpenseOverflowPropagatesToPlanSummary() = runTest {
        val repository = object : PlanRepository {
            override fun observeMonth(month: YearMonthKey): Flow<PlanMonth> = flowOf(
                PlanMonth(emptyList(), 0, hasVariableExpenses = true, hasVariableExpenseOverflow = true),
            )
            override fun observeYear(year: Int): Flow<List<PlanYearMonth>> = emptyFlow()
            override suspend fun previewCopy(request: PlanCopyRequest): PlanCopyPreview = error("unused")
            override suspend fun copyToMonths(request: PlanCopyRequest): PlanCopyResult = error("unused")
            override suspend fun find(id: Long): MonthlyPlanItem? = null
            override suspend fun save(item: MonthlyPlanItem): Long = error("unused")
            override suspend fun delete(id: Long) = Unit
        }
        val holder = PlanStateHolder(repository, PlanCurrentMonthProvider { YearMonthKey(2026, 5) }, this)

        holder.start()
        runCurrent()

        assertEquals(true, holder.state.value.hasVariableExpenseOverflow)
        assertEquals(true, holder.state.value.summary.hasVariableExpenseOverflow)
        assertEquals(true, holder.state.value.summary.hasTotalExpenseOverflow)
        assertNull(holder.state.value.summary.totalExpenseWon)
        holder.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun movingMonthClearsDerivedValuesBeforeTheNextSnapshotArrives() = runTest {
        val holder = PlanStateHolder(
            repository = DelayedNextMonthRepository,
            currentMonthProvider = PlanCurrentMonthProvider { YearMonthKey(2026, 5) },
            parentScope = this,
        )
        holder.start()
        runCurrent()
        assertEquals(900_000, holder.state.value.variableExpenseTotalWon)
        assertEquals(1_200_000, holder.state.value.summary.totalExpenseWon)

        holder.onAction(PlanAction.NextMonth)

        assertEquals(YearMonthKey(2026, 6), holder.state.value.month)
        assertEquals(0, holder.state.value.variableExpenseTotalWon)
        assertEquals(false, holder.state.value.hasVariableExpenses)
        assertNull(holder.state.value.summary.totalExpenseWon)
        assertEquals(true, holder.state.value.items.isEmpty())
        assertEquals(true, holder.state.value.isLoading)
        holder.close()
    }

    private fun item(type: PlanItemType, amount: Long?, purpose: Boolean = false, net: Boolean = false) = MonthlyPlanItem(
        type = type, attributionMonth = YearMonthKey(2026, 5), name = "항목", amountWon = amount,
        category = "기타", status = PlanItemStatus.Estimated, ownerMemberOrder = null, memo = null,
        includePurposeAccount = purpose, includeNetSavings = net,
    )

    private object DelayedNextMonthRepository : PlanRepository {
        override fun observeMonth(month: YearMonthKey): Flow<PlanMonth> = if (month.month == 5) {
            flowOf(
                PlanMonth(
                    items = listOf(
                        item(PlanItemType.Income, 1_000_000),
                        item(PlanItemType.FixedExpense, 300_000),
                        item(PlanItemType.Savings, 200_000),
                    ),
                    variableExpenseTotalWon = 900_000,
                    hasVariableExpenses = true,
                ),
            )
        } else {
            emptyFlow()
        }

        override fun observeYear(year: Int): Flow<List<PlanYearMonth>> = emptyFlow()
        override suspend fun previewCopy(request: PlanCopyRequest): PlanCopyPreview = error("unused")
        override suspend fun copyToMonths(request: PlanCopyRequest): PlanCopyResult = error("unused")
        override suspend fun find(id: Long): MonthlyPlanItem? = null
        override suspend fun save(item: MonthlyPlanItem): Long = error("unused")
        override suspend fun delete(id: Long) = Unit

        private fun item(type: PlanItemType, amount: Long) = MonthlyPlanItem(
            type = type,
            attributionMonth = YearMonthKey(2026, 5),
            name = "항목",
            amountWon = amount,
            category = "기타",
            status = PlanItemStatus.Estimated,
            ownerMemberOrder = null,
            memo = null,
        )
    }
}
