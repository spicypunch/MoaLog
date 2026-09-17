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
import kr.jm.moalog.feature.plan.presentation.AnnualPlanArgs
import kr.jm.moalog.feature.plan.presentation.AnnualPlanSection
import kr.jm.moalog.feature.plan.presentation.AnnualPlanStateHolder
import kr.jm.moalog.feature.plan.presentation.AnnualPlanViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class IosAnnualPlanStoreTest {
    @Test
    fun exposesAnnualSelectionAndViewModeWithoutSealedActions() = runBlocking {
        val holder = AnnualPlanStateHolder(
            args = AnnualPlanArgs(2026),
            repository = EmptyPlanRepository,
            currentMonthProvider = PlanCurrentMonthProvider { YearMonthKey(2026, 9) },
            parentScope = this,
        )
        val store = IosAnnualPlanStore(holder)
        repeat(10) { yield() }

        assertFalse(store.currentState.isLoading)
        assertEquals(12, store.currentState.months.size)
        store.selectSection(AnnualPlanSection.Savings)
        assertEquals(AnnualPlanSection.Savings, store.currentState.selectedSection)
        store.showMonthlyList()
        assertEquals(AnnualPlanViewMode.MonthlyList, store.currentState.viewMode)
        store.showTable()
        assertEquals(AnnualPlanViewMode.Table, store.currentState.viewMode)

        store.close()
        store.selectSection(AnnualPlanSection.Income)
        assertEquals(AnnualPlanSection.Savings, store.currentState.selectedSection)
    }

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
