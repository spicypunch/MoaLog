package kr.jm.moalog.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MonthlyPlanItemTest {
    @Test
    fun savingsCatalogUsesTheSixApprovedClassifications() {
        assertEquals(listOf("예적금", "청약", "주식", "부동산", "대출원금", "퇴직금"), DefaultSavingsClassifications)
        assertEquals(6, DefaultSavingsClassifications.distinct().size)
    }

    @Test
    fun nonSavingsCatalogRejectsSavingsOnlyFlags() {
        assertFailsWith<IllegalArgumentException> {
            PlanCatalogItem(
                type = PlanItemType.Income,
                classification = "급여",
                name = "월급",
                ownerMemberOrder = 0,
                includeNetSavings = true,
            )
        }
    }
    @Test fun distinguishesMissingAndZeroAmounts() {
        assertNull(item(PlanItemType.FixedExpense, null).amountWon)
        assertEquals(0, item(PlanItemType.FixedExpense, 0).amountWon)
    }

    @Test fun savingsAllowsNegativeButOtherTypesRejectIt() {
        assertEquals(-10_000, item(PlanItemType.Savings, -10_000).amountWon)
        assertFailsWith<IllegalArgumentException> { item(PlanItemType.Income, -1) }
        assertFailsWith<IllegalArgumentException> { item(PlanItemType.FixedExpense, -1) }
    }

    private fun item(type: PlanItemType, amount: Long?) = MonthlyPlanItem(
        type = type, attributionMonth = YearMonthKey(2026, 5), name = "항목", amountWon = amount,
        category = "기타", status = PlanItemStatus.Estimated, ownerMemberOrder = null, memo = null,
        includePurposeAccount = type == PlanItemType.Savings, includeNetSavings = type == PlanItemType.Savings,
    )
}
