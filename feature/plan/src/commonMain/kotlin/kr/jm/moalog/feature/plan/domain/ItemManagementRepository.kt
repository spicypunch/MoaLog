package kr.jm.moalog.feature.plan.domain

import kr.jm.moalog.core.database.ExpenseLocalDataSource
import kr.jm.moalog.core.database.PlanLocalDataSource
import kr.jm.moalog.core.model.ExpenseCategory
import kr.jm.moalog.core.model.PlanCatalogItem
import kotlinx.coroutines.flow.Flow

interface ItemManagementRepository {
    fun observePlanCatalog(): Flow<List<PlanCatalogItem>>
    fun observeExpenseCategories(): Flow<List<ExpenseCategory>>
    suspend fun savePlanItem(item: PlanCatalogItem): Long
    suspend fun createExpenseCategory(name: String): ExpenseCategory
    suspend fun renameExpenseCategory(id: String, name: String)
    suspend fun setPlanItemArchived(id: Long, archived: Boolean)
    suspend fun setExpenseCategoryArchived(id: String, archived: Boolean)
    suspend fun reorderPlanItems(orderedIds: List<Long>)
    suspend fun reorderExpenseCategories(orderedIds: List<String>)
}

internal class DefaultItemManagementRepository(
    private val plans: PlanLocalDataSource,
    private val expenses: ExpenseLocalDataSource,
) : ItemManagementRepository {
    override fun observePlanCatalog() = plans.observeCatalog()
    override fun observeExpenseCategories() = expenses.observeAllCategories()
    override suspend fun savePlanItem(item: PlanCatalogItem) = plans.saveCatalog(item)
    override suspend fun createExpenseCategory(name: String) = expenses.createCategory(name)
    override suspend fun renameExpenseCategory(id: String, name: String) = expenses.renameCategory(id, name)
    override suspend fun setPlanItemArchived(id: Long, archived: Boolean) = plans.setCatalogArchived(id, archived)
    override suspend fun setExpenseCategoryArchived(id: String, archived: Boolean) = expenses.setCategoryArchived(id, archived)
    override suspend fun reorderPlanItems(orderedIds: List<Long>) = plans.reorderCatalog(orderedIds)
    override suspend fun reorderExpenseCategories(orderedIds: List<String>) = expenses.reorderCategories(orderedIds)
}
