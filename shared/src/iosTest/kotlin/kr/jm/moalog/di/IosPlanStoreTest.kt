package kr.jm.moalog.di

import kr.jm.moalog.core.database.PlanCopyPreview
import kr.jm.moalog.core.database.PlanCopyRequest
import kr.jm.moalog.core.database.PlanCopyResult
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.plan.domain.PlanCurrentMonthProvider
import kr.jm.moalog.feature.plan.domain.PlanMonth
import kr.jm.moalog.feature.plan.domain.PlanRepository
import kr.jm.moalog.feature.plan.domain.PlanYearMonth
import kr.jm.moalog.feature.plan.presentation.PlanStateHolder
import kr.jm.moalog.feature.plan.presentation.PlanTab
import kr.jm.moalog.feature.plan.presentation.PlanUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosPlanStoreTest {
    @Test
    fun exposesMonthBoundariesTabSelectionAndCloseWithoutSealedActions() = runBlocking {
        val store = store(YearMonthKey(YearMonthKey.MIN_YEAR, 1))

        store.previousMonth()
        assertEquals(YearMonthKey(YearMonthKey.MIN_YEAR, 1), store.currentState.month)

        store.selectTab(PlanTab.Savings)
        assertEquals(PlanTab.Savings, store.currentState.selectedTab)

        store.selectMonth(9999, 12)
        store.nextMonth()
        assertEquals(YearMonthKey(9999, 12), store.currentState.month)

        store.selectMonth(YearMonthKey.MIN_YEAR - 1, 13)
        assertEquals(YearMonthKey(9999, 12), store.currentState.month)

        store.close()
        store.close()
        store.previousMonth()
        assertEquals(YearMonthKey(9999, 12), store.currentState.month)
    }

    @Test
    fun observationCanBeCancelledAndStoreCloseStopsFurtherActions() = runBlocking {
        val store = store(YearMonthKey(2026, 5))
        val observed = mutableListOf<PlanUiState>()
        val observation = store.observe { observed += it }
        repeat(10) { yield() }

        assertTrue(observed.isNotEmpty())
        observation.cancel()
        val observedCount = observed.size
        store.selectTab(PlanTab.Savings)
        repeat(10) { yield() }
        assertEquals(observedCount, observed.size)

        store.close()
        store.selectTab(PlanTab.Income)
        assertEquals(PlanTab.Savings, store.currentState.selectedTab)
    }

    @Test
    fun completionSelectionCanRestoreSourceMonthAndTabTogether() = runBlocking {
        val store = store(YearMonthKey(2026, 9))

        store.selectMonthAndTab(2027, 2, PlanTab.FixedExpense)

        assertEquals(YearMonthKey(2027, 2), store.currentState.month)
        assertEquals(PlanTab.FixedExpense, store.currentState.selectedTab)

        store.selectMonthAndTab(YearMonthKey.MIN_YEAR - 1, 1, PlanTab.Savings)
        assertEquals(YearMonthKey(2027, 2), store.currentState.month)
        assertEquals(PlanTab.FixedExpense, store.currentState.selectedTab)
        store.close()
    }

    private fun kotlinx.coroutines.CoroutineScope.store(month: YearMonthKey): IosPlanStore =
        IosPlanStore(
            PlanStateHolder(
                repository = EmptyPlanRepository,
                currentMonthProvider = PlanCurrentMonthProvider { month },
                parentScope = this,
            ),
        )

    private object EmptyPlanRepository : PlanRepository {
        override fun observeMonth(month: YearMonthKey): Flow<PlanMonth> = flowOf(PlanMonth(emptyList(), 0))
        override fun observeYear(year: Int): Flow<List<PlanYearMonth>> = flowOf(emptyList())
        override suspend fun previewCopy(request: PlanCopyRequest): PlanCopyPreview = error("unused")
        override suspend fun copyToMonths(request: PlanCopyRequest): PlanCopyResult = error("unused")
        override suspend fun find(id: Long): MonthlyPlanItem? = null
        override suspend fun save(item: MonthlyPlanItem): Long = error("unused")
        override suspend fun delete(id: Long) = Unit
    }
}
