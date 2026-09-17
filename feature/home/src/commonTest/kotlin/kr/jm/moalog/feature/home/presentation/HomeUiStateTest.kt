package kr.jm.moalog.feature.home.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey

class HomeUiStateTest {
    @Test
    fun preservesMemberOrderAndMissingTarget() {
        val state = LedgerSetup(
            ledgerName = "모아로그",
            members = listOf(LedgerMember("종민", 1), LedgerMember("수아", 0)),
            baseYear = 2026,
            annualSavingsTargetWon = null,
        ).toHomeUiState(HomeMonthlySummary(2026, 9, expenseTotalWon = 350_000, overspentCount = 1))

        assertEquals("수아 · 종민", state.memberNames)
        assertNull(state.annualSavingsTargetWon)
        assertEquals(350_000, state.monthlySummary.expenseTotalWon)
        assertEquals(1, state.monthlySummary.overspentCount)
        assertEquals(2026, state.selectedYear)
    }

    @Test
    fun calculatesCompleteAnnualPlanFromAllTwelveMonthsAndAllSavings() {
        val setup = setup(targetWon = 12_000_000)
        val months = completeYear(2026) { month ->
            listOf(
                planItem(PlanItemType.Income, month, 5_000_000, PlanItemStatus.Estimated),
                planItem(PlanItemType.FixedExpense, month, 1_500_000, PlanItemStatus.Confirmed),
                planItem(PlanItemType.Savings, month, 1_000_000, PlanItemStatus.Estimated, ownerOrder = 0, net = true),
            ) + if (month.month == 1) {
                listOf(planItem(PlanItemType.Savings, month, 2_000_000, PlanItemStatus.Confirmed, ownerOrder = 1))
            } else emptyList()
        }

        val summary = calculateHomeAnnualSummary(setup, 2026, months)

        assertEquals(60_000_000, summary.knownIncomeWon)
        assertEquals(18_000_000, summary.knownFixedExpenseWon)
        assertEquals(6_000_000, summary.variableExpenseWon)
        assertEquals(24_000_000, summary.knownTotalExpenseWon)
        assertEquals(14_000_000, summary.knownSavingsWon)
        assertEquals(14_000_000, summary.knownPurposeAccountSavingsWon)
        assertEquals(12_000_000, summary.knownNetSavingsWon)
        assertEquals(23.333333333333332, summary.savingsRate)
        assertEquals(20.0, summary.netSavingsRate)
        assertEquals(116.66666666666667, summary.goalProgressRate)
        assertEquals(-2_000_000, summary.goalRemainingWon)
        assertEquals("민지 85.7% · 준호 14.3%", summary.memberContributionLabel)
        assertTrue(summary.incomeIsComplete)
        assertTrue(summary.fixedExpenseIsComplete)
        assertTrue(summary.savingsIsComplete)
        assertTrue(summary.composition.isComplete)
        assertFalse(summary.isPartial)
    }

    @Test
    fun keepsKnownTotalsButMarksSavingsDependentValuesUnknownForMissingAmount() {
        val months = completeYear(2026) { month ->
            listOf(
                planItem(PlanItemType.Income, month, 5_000_000, PlanItemStatus.Confirmed),
                planItem(PlanItemType.FixedExpense, month, 1_000_000, PlanItemStatus.Confirmed),
                planItem(
                    PlanItemType.Savings,
                    month,
                    if (month.month == 12) null else 1_000_000,
                    PlanItemStatus.Estimated,
                    ownerOrder = 0,
                    net = true,
                ),
            )
        }

        val summary = calculateHomeAnnualSummary(setup(10_000_000), 2026, months)

        assertEquals(11_000_000, summary.knownSavingsWon)
        assertEquals(11_000_000, summary.knownPurposeAccountSavingsWon)
        assertFalse(summary.savingsIsComplete)
        assertTrue(summary.isPartial)
        assertNull(summary.savingsRate)
        assertNull(summary.netSavingsRate)
        assertNull(summary.goalProgressRate)
        assertNull(summary.goalRemainingWon)
        assertNull(summary.memberContributionLabel)
        assertNull(summary.composition.savingsPercent)
        assertFalse(summary.composition.isComplete)
    }

    @Test
    fun missingRequiredTypeInOneMonthKeepsAnnualSummaryPartial() {
        val months = completeYear(2026) { month ->
            listOf(
                planItem(PlanItemType.Income, month, 5_000_000, PlanItemStatus.Confirmed),
                planItem(PlanItemType.Savings, month, 1_000_000, PlanItemStatus.Confirmed, ownerOrder = 0),
            ) + if (month.month == 12) emptyList() else {
                listOf(planItem(PlanItemType.FixedExpense, month, 1_000_000, PlanItemStatus.Confirmed))
            }
        }

        val summary = calculateHomeAnnualSummary(setup(null), 2026, months)

        assertTrue(summary.incomeIsComplete)
        assertFalse(summary.fixedExpenseIsComplete)
        assertTrue(summary.savingsIsComplete)
        assertTrue(summary.isPartial)
        assertFalse(summary.composition.isComplete)
    }

    @Test
    fun targetAppliesToSelectedYearOrFallsBackToBaseYear() {
        val setup = setup(
            targetWon = null,
            annualTargets = mapOf(
                2026 to 12_000_000L,
                2027 to 0L,
            ),
        )

        val state2027 = setup.toHomeUiState(HomeMonthlySummary(2027, 9), selectedYear = 2027)
        val state2026 = setup.toHomeUiState(HomeMonthlySummary(2026, 9), selectedYear = 2026)

        assertEquals(0L, state2027.annualSavingsTargetWon)
        assertEquals(12_000_000L, state2026.annualSavingsTargetWon)
    }

    @Test
    fun targetIsNullWhenYearHasNoConfiguredValue() {
        val state = setup(targetWon = 8_000_000).toHomeUiState(HomeMonthlySummary(2027, 9), selectedYear = 2027)

        assertNull(state.annualSavingsTargetWon)
    }

    @Test
    fun excludesDataFromOtherYears() {
        val month2026 = YearMonthKey(2026, 1)
        val month2027 = YearMonthKey(2027, 1)
        val summary = calculateHomeAnnualSummary(
            setup = setup(targetWon = null),
            year = 2026,
            months = listOf(
                annualMonth(month2026, listOf(planItem(PlanItemType.Income, month2026, 10_000_000, PlanItemStatus.Confirmed))),
                annualMonth(
                    month2027,
                    listOf(planItem(PlanItemType.Income, month2027, 90_000_000, PlanItemStatus.Confirmed)),
                    variableExpenseWon = 9_000_000,
                ),
            ),
        )

        assertEquals(10_000_000, summary.knownIncomeWon)
        assertEquals(500_000, summary.variableExpenseWon)
    }

    private fun completeYear(
        year: Int,
        items: (YearMonthKey) -> List<MonthlyPlanItem>,
    ): List<HomeAnnualMonthSnapshot> = (1..12).map { monthNumber ->
        val month = YearMonthKey(year, monthNumber)
        annualMonth(month, items(month))
    }

    private fun annualMonth(
        month: YearMonthKey,
        items: List<MonthlyPlanItem>,
        variableExpenseWon: Long = 500_000,
    ) = HomeAnnualMonthSnapshot(
        month = month,
        items = items,
        variableExpenses = listOf(expense(month, variableExpenseWon)),
    )

    private fun setup(
        targetWon: Long?,
        annualTargets: Map<Int, Long> = targetWon?.let { mapOf(2026 to it) } ?: emptyMap(),
    ) = LedgerSetup(
        ledgerName = "모아로그",
        members = listOf(LedgerMember("민지", 0), LedgerMember("준호", 1)),
        baseYear = 2026,
        annualSavingsTargetWon = annualTargets[2026],
        annualSavingsTargetsWon = annualTargets,
    )

    private fun planItem(
        type: PlanItemType,
        month: YearMonthKey,
        amountWon: Long?,
        status: PlanItemStatus,
        ownerOrder: Int? = null,
        net: Boolean = false,
    ) = MonthlyPlanItem(
        type = type,
        attributionMonth = month,
        name = type.name,
        amountWon = amountWon,
        category = "테스트",
        status = status,
        ownerMemberOrder = ownerOrder,
        memo = null,
        includeNetSavings = net,
    )

    private fun expense(month: YearMonthKey, amountWon: Long) = ExpenseRecord(
        categoryId = "living",
        categoryName = "생활비",
        attributionMonth = month,
        actualDate = null,
        detail = null,
        amountWon = amountWon,
        overspent = false,
    )
}
