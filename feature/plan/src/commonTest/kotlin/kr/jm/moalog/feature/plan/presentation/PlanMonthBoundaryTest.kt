package kr.jm.moalog.feature.plan.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.plan.domain.PlanCurrentMonthProvider
import kr.jm.moalog.feature.plan.domain.PlanMonth
import kr.jm.moalog.feature.plan.domain.PlanRepository
import kr.jm.moalog.feature.plan.domain.PlanYearMonth
import kr.jm.moalog.core.database.PlanCopyPreview
import kr.jm.moalog.core.database.PlanCopyRequest
import kr.jm.moalog.core.database.PlanCopyResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

class PlanMonthBoundaryTest {
    @Test
    fun previousMonthAtMinimumIsIgnored() = runTest {
        val holder = holder(YearMonthKey(YearMonthKey.MIN_YEAR, 1))
        holder.onAction(PlanAction.PreviousMonth)
        assertEquals(YearMonthKey(YearMonthKey.MIN_YEAR, 1), holder.state.value.month)
        holder.close()
    }

    @Test
    fun nextMonthAtMaximumIsIgnored() = runTest {
        val holder = holder(YearMonthKey(9999, 12))
        holder.onAction(PlanAction.NextMonth)
        assertEquals(YearMonthKey(9999, 12), holder.state.value.month)
        holder.close()
    }

    private fun kotlinx.coroutines.test.TestScope.holder(month: YearMonthKey) = PlanStateHolder(
        repository = EmptyPlanRepository,
        currentMonthProvider = PlanCurrentMonthProvider { month },
        parentScope = this,
    )

    private object EmptyPlanRepository : PlanRepository {
        override fun observeMonth(month: YearMonthKey): Flow<PlanMonth> = flowOf(PlanMonth(emptyList(), 0))
        override fun observeYear(year: Int): Flow<List<PlanYearMonth>> = flowOf(emptyList())
        override suspend fun previewCopy(request: PlanCopyRequest): PlanCopyPreview = error("unused")
        override suspend fun copyToMonths(request: PlanCopyRequest): PlanCopyResult = error("unused")
        override suspend fun find(id: Long): MonthlyPlanItem? = null
        override suspend fun save(item: MonthlyPlanItem): Long = 1
        override suspend fun delete(id: Long) = Unit
    }
}
