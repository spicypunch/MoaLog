package kr.jm.moalog.feature.plan.domain

import kr.jm.moalog.core.database.ExistingPlanPolicy
import kr.jm.moalog.core.database.FixedCostApplyResult
import kr.jm.moalog.core.database.FixedCostCheckLocalDataSource
import kr.jm.moalog.core.database.FixedCostPlanPreview
import kr.jm.moalog.core.database.PlanLocalDataSource
import kr.jm.moalog.core.model.FixedCostCheckItem
import kr.jm.moalog.core.model.FixedCostCheckSheet
import kr.jm.moalog.core.model.YearMonthKey
import kotlinx.coroutines.flow.Flow

interface FixedCostCheckRepository {
    fun observe(month: YearMonthKey): Flow<FixedCostCheckSheet>
    suspend fun find(id: Long): FixedCostCheckItem?
    suspend fun save(value: FixedCostCheckItem): Long
    suspend fun delete(id: Long)
    suspend fun preview(month: YearMonthKey, items: List<FixedCostCheckItem>): FixedCostPlanPreview
    suspend fun apply(month: YearMonthKey, items: List<FixedCostCheckItem>, policy: ExistingPlanPolicy): FixedCostApplyResult
}

internal class DefaultFixedCostCheckRepository(
    private val fixedCosts: FixedCostCheckLocalDataSource,
    private val plans: PlanLocalDataSource,
) : FixedCostCheckRepository {
    override fun observe(month: YearMonthKey) = fixedCosts.observe(month)
    override suspend fun find(id: Long) = fixedCosts.find(id)
    override suspend fun save(value: FixedCostCheckItem) = fixedCosts.save(value)
    override suspend fun delete(id: Long) = fixedCosts.delete(id)
    override suspend fun preview(month: YearMonthKey, items: List<FixedCostCheckItem>) = plans.previewFixedCosts(month, items)
    override suspend fun apply(month: YearMonthKey, items: List<FixedCostCheckItem>, policy: ExistingPlanPolicy) = plans.applyFixedCosts(month, items, policy)
}
