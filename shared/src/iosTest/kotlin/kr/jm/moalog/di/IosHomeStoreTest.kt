package kr.jm.moalog.di

import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.plan.domain.PlanYearMonth
import kr.jm.moalog.feature.records.presentation.RecordsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosHomeStoreTest {
    @Test
    fun mapsTheSameFullMonthSummaryUsedByAndroidHome() {
        val month = YearMonthKey(2026, 9)
        val setup = LedgerSetup(
            ledgerName = "모아로그",
            members = listOf(LedgerMember("민지", 0), LedgerMember("준호", 1)),
            baseYear = 2026,
            annualSavingsTargetWon = 12_000_000,
        )
        val records = listOf(
            expense(id = 1, month = month, amountWon = 80_000, overspent = true),
            expense(id = 2, month = month, amountWon = 20_000, overspent = false),
            expense(id = 3, month = month, amountWon = 5_000, overspent = true),
        )

        val state = buildIosHomeState(
            setup = setup,
            selectedYear = 2026,
            currentMonthNumber = 9,
            annualPlan = listOf(
                PlanYearMonth(
                    month = month,
                    items = listOf(planItem(month, PlanItemType.Income, 10_000_000)),
                    variableExpenses = records,
                ),
            ),
            annualPlanLoading = false,
            annualPlanLoadError = null,
            records = RecordsUiState(
                month = month,
                records = records,
                fullMonthTotalWon = 105_000,
                fullMonthOverspentWon = 85_000,
                isLoading = true,
                loadError = "기록을 불러오지 못했어요",
            ),
        )

        assertEquals(2026, state.monthlySummary.year)
        assertEquals(9, state.monthlySummary.month)
        assertEquals(105_000, state.monthlySummary.expenseTotalWon)
        assertEquals(85_000, state.monthlySummary.overspentTotalWon)
        assertEquals(2, state.monthlySummary.overspentCount)
        assertTrue(state.monthlySummary.isLoading)
        assertEquals("기록을 불러오지 못했어요", state.monthlySummary.loadError)
        assertEquals(2026, state.selectedYear)
        assertEquals(10_000_000, state.annualSummary.knownIncomeWon)
        assertEquals(105_000, state.annualSummary.variableExpenseWon)
    }

    @Test
    fun doesNotExposeRecordsFromAStaleSelectedYear() {
        val setup = LedgerSetup(
            ledgerName = "모아로그",
            members = listOf(LedgerMember("민지", 0), LedgerMember("준호", 1)),
            baseYear = 2026,
            annualSavingsTargetWon = 12_000_000,
        )

        val state = buildIosHomeState(
            setup = setup,
            selectedYear = 2027,
            currentMonthNumber = 9,
            annualPlan = emptyList(),
            annualPlanLoading = true,
            annualPlanLoadError = null,
            records = RecordsUiState(
                month = YearMonthKey(2026, 9),
                fullMonthTotalWon = 999_000,
                isLoading = false,
            ),
        )

        assertEquals(2027, state.monthlySummary.year)
        assertEquals(9, state.monthlySummary.month)
        assertEquals(0, state.monthlySummary.expenseTotalWon)
        assertTrue(state.monthlySummary.isLoading)
        assertEquals(null, state.annualSavingsTargetWon)
        assertEquals(null, state.annualSummary.goalProgressRate)
        assertEquals(null, state.annualSummary.goalRemainingWon)
    }

    private fun expense(
        id: Long,
        month: YearMonthKey,
        amountWon: Long,
        overspent: Boolean,
    ) = ExpenseRecord(
        id = id,
        categoryId = "living",
        categoryName = "생활비",
        attributionMonth = month,
        actualDate = null,
        detail = null,
        amountWon = amountWon,
        overspent = overspent,
    )

    private fun planItem(
        month: YearMonthKey,
        type: PlanItemType,
        amountWon: Long,
    ) = MonthlyPlanItem(
        type = type,
        attributionMonth = month,
        name = type.name,
        amountWon = amountWon,
        category = "테스트",
        status = PlanItemStatus.Estimated,
        ownerMemberOrder = null,
        memo = null,
    )
}
