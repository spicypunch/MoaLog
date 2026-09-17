package kr.jm.moalog.feature.plan.presentation

import kr.jm.moalog.core.model.DefaultSavingsClassifications
import kr.jm.moalog.core.model.ExpenseCategory
import kr.jm.moalog.core.model.PlanCatalogItem
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.feature.plan.domain.ItemManagementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ItemManagementStateTest {
    @Test
    fun archiveKeepsItemAndRestoreReturnsItToActiveList() = runTest {
        val repository = FakeRepository()
        repository.categories.value = listOf(ExpenseCategory("food", "생활비", 0))
        val holder = ItemManagementStateHolder(repository, this)
        holder.start()
        advanceUntilIdle()

        holder.onAction(ItemManagementAction.RequestArchive("expense:food"))
        holder.onAction(ItemManagementAction.ConfirmArchive)
        advanceUntilIdle()
        assertTrue(repository.categories.value.single().archived)
        assertTrue(holder.state.value.items.isEmpty())
        assertTrue(holder.state.value.resultAnnouncement.orEmpty().contains("기존 기록"))

        holder.onAction(ItemManagementAction.ShowArchived(true))
        holder.onAction(ItemManagementAction.Restore("expense:food"))
        advanceUntilIdle()
        assertFalse(repository.categories.value.single().archived)
        assertTrue(holder.state.value.items.isEmpty())
        holder.close()
    }

    @Test
    fun savingsEditorExposesSixClassificationsAndPersistsFlags() = runTest {
        val repository = FakeRepository()
        val holder = ItemManagementStateHolder(repository, this)
        holder.start()
        advanceUntilIdle()
        holder.onAction(ItemManagementAction.SelectType(ManagedItemType.Savings))
        holder.onAction(ItemManagementAction.Add)
        holder.onAction(ItemManagementAction.NameChanged("여행 적금"))
        holder.onAction(ItemManagementAction.ClassificationChanged("예적금"))
        holder.onAction(ItemManagementAction.PurposeChanged(true))
        holder.onAction(ItemManagementAction.NetSavingsChanged(true))
        holder.onAction(ItemManagementAction.Save)
        advanceUntilIdle()

        assertEquals(DefaultSavingsClassifications, holder.state.value.savingsClassifications)
        assertEquals(6, holder.state.value.savingsClassifications.size)
        assertTrue(repository.plans.value.single().includePurposeAccount)
        assertTrue(repository.plans.value.single().includeNetSavings)
        holder.close()
    }

    @Test
    fun duplicateNameIsRejectedAndReorderIsAtomic() = runTest {
        val repository = FakeRepository()
        repository.categories.value = listOf(
            ExpenseCategory("a", "생활비", 0),
            ExpenseCategory("b", "데이트비", 1),
        )
        val holder = ItemManagementStateHolder(repository, this)
        holder.start()
        advanceUntilIdle()
        holder.onAction(ItemManagementAction.Add)
        holder.onAction(ItemManagementAction.NameChanged(" 생활비 "))
        holder.onAction(ItemManagementAction.Save)
        assertTrue(holder.state.value.editorError.orEmpty().contains("같은 이름"))

        holder.onAction(ItemManagementAction.DismissEditor)
        holder.onAction(ItemManagementAction.Move("expense:b", -1))
        advanceUntilIdle()
        assertEquals(listOf("b", "a"), repository.categories.value.sortedBy { it.displayOrder }.map { it.id })
        holder.close()
    }

    @Test
    fun migratedDuplicateCanKeepItsNameWhileOtherPropertiesChange() = runTest {
        val repository = FakeRepository()
        repository.plans.value = listOf(
            PlanCatalogItem(1, type = PlanItemType.Income, classification = "급여", name = "월급", ownerMemberOrder = 0),
            PlanCatalogItem(2, type = PlanItemType.Income, classification = "급여", name = "월급", ownerMemberOrder = 1),
        )
        val holder = ItemManagementStateHolder(repository, this)
        holder.start()
        advanceUntilIdle()
        holder.onAction(ItemManagementAction.SelectType(ManagedItemType.Income))
        holder.onAction(ItemManagementAction.Edit("plan:1"))
        holder.onAction(ItemManagementAction.ClassificationChanged("근로소득"))
        holder.onAction(ItemManagementAction.Save)
        advanceUntilIdle()

        assertEquals("근로소득", repository.plans.value.single { it.id == 1L }.classification)
        assertEquals(null, holder.state.value.editorError)
        holder.close()
    }

    private class FakeRepository : ItemManagementRepository {
        val plans = MutableStateFlow<List<PlanCatalogItem>>(emptyList())
        val categories = MutableStateFlow<List<ExpenseCategory>>(emptyList())
        override fun observePlanCatalog() = plans
        override fun observeExpenseCategories() = categories
        override suspend fun savePlanItem(item: PlanCatalogItem): Long {
            val id = item.id.takeIf { it > 0 } ?: ((plans.value.maxOfOrNull { it.id } ?: 0) + 1)
            plans.value = plans.value.filterNot { it.id == id } + item.copy(id = id)
            return id
        }
        override suspend fun createExpenseCategory(name: String): ExpenseCategory {
            val item = ExpenseCategory("custom", name, categories.value.size)
            categories.value = categories.value + item
            return item
        }
        override suspend fun renameExpenseCategory(id: String, name: String) {
            categories.value = categories.value.map { if (it.id == id) it.copy(name = name) else it }
        }
        override suspend fun setPlanItemArchived(id: Long, archived: Boolean) {
            plans.value = plans.value.map { if (it.id == id) it.copy(archived = archived) else it }
        }
        override suspend fun setExpenseCategoryArchived(id: String, archived: Boolean) {
            categories.value = categories.value.map { if (it.id == id) it.copy(archived = archived) else it }
        }
        override suspend fun reorderPlanItems(orderedIds: List<Long>) {
            plans.value = plans.value.map { it.copy(displayOrder = orderedIds.indexOf(it.id)) }
        }
        override suspend fun reorderExpenseCategories(orderedIds: List<String>) {
            categories.value = categories.value.map { it.copy(displayOrder = orderedIds.indexOf(it.id)) }
        }
    }
}
