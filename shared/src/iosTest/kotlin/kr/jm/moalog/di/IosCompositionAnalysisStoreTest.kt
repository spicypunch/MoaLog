package kr.jm.moalog.di

import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.home.domain.HomeRepository
import kr.jm.moalog.feature.home.domain.HomeYearSnapshot
import kr.jm.moalog.feature.home.presentation.CompositionAnalysisStateHolder
import kr.jm.moalog.feature.home.presentation.CompositionTab
import kr.jm.moalog.feature.home.presentation.HomeAnnualMonthSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosCompositionAnalysisStoreTest {
    @Test
    fun exposesTabsPeriodsAndNegativeRowsWithoutSealedActions() = runBlocking {
        val month = YearMonthKey(2026, 5)
        val repository = FakeHomeRepository(
            HomeYearSnapshot(
                setup = null,
                months = (1..12).map { number ->
                    val snapshotMonth = YearMonthKey(2026, number)
                    HomeAnnualMonthSnapshot(
                        month = snapshotMonth,
                        items = listOf(
                            savings(snapshotMonth, "예금", 1_000_000),
                            savings(snapshotMonth, "출금", -100_000),
                        ),
                        variableExpenses = emptyList(),
                    )
                },
            ),
        )
        val store = IosCompositionAnalysisStore(
            CompositionAnalysisStateHolder(repository, month, CompositionTab.VariableExpense, this),
        )
        repeat(10) { yield() }

        store.previousPeriod()
        assertEquals(4, store.currentState.selectedMonth.month)
        store.selectAnnual()
        assertTrue(store.currentState.usesAnnualPeriod)
        assertEquals("2026년", store.currentState.periodLabel)
        store.selectMonthly()
        assertEquals("2026년 4월", store.currentState.periodLabel)
        store.selectTab(CompositionTab.Savings)
        assertEquals(CompositionTab.Savings, store.currentState.selectedTab)
        assertEquals(10_800_000, store.currentState.totalWon)
        assertTrue(store.currentState.rows.any { it.isNegative })

        store.close()
        store.selectTab(CompositionTab.FixedExpense)
        assertEquals(CompositionTab.Savings, store.currentState.selectedTab)
    }

    private class FakeHomeRepository(private val snapshot: HomeYearSnapshot) : HomeRepository {
        override fun observeYear(year: Int): Flow<HomeYearSnapshot> = flowOf(snapshot)
        override suspend fun updateAnnualSavingsTarget(targetWon: Long?) = Unit
    }

    private fun savings(month: YearMonthKey, name: String, amount: Long) = MonthlyPlanItem(
        type = PlanItemType.Savings,
        attributionMonth = month,
        name = name,
        amountWon = amount,
        category = "저축",
        status = PlanItemStatus.Confirmed,
        ownerMemberOrder = null,
        memo = null,
    )
}
