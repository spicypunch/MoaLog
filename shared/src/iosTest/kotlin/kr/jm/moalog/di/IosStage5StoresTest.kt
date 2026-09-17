package kr.jm.moalog.di

import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.core.model.ExpenseCategory
import kr.jm.moalog.core.model.PlanCatalogItem
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.feature.plan.domain.ItemManagementRepository
import kr.jm.moalog.feature.plan.presentation.ItemManagementStateHolder
import kr.jm.moalog.feature.plan.presentation.ManagedItemType
import kr.jm.moalog.feature.setup.domain.SetupRepository
import kr.jm.moalog.feature.setup.presentation.LedgerSettingsStateHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosStage5StoresTest {
    @Test
    fun ledgerSettingsAdapterRestoresDraftAndPreservesTargetsOnSave() = runBlocking {
        val original = LedgerSetup(
            ledgerName = "우리 가계부",
            members = listOf(LedgerMember("수아", 0), LedgerMember("종민", 1)),
            baseYear = 2026,
            annualSavingsTargetWon = 2_000_000,
            annualSavingsTargetsWon = mapOf(2025 to 1_000_000, 2026 to 2_000_000),
        )
        val repository = FakeIosSettingsRepository(original)
        val store = IosLedgerSettingsStore(LedgerSettingsStateHolder(repository, this))
        settle()

        assertEquals("우리 가계부", store.currentState.ledgerName)
        store.restoreDraft("둘의 기록", "새수아", "새종민")
        assertTrue(store.currentState.isDirty)
        store.save()
        settle()

        val saved = repository.saved.single()
        assertEquals("둘의 기록", saved.ledgerName)
        assertEquals(listOf("새수아", "새종민"), saved.members.sortedBy { it.order }.map { it.displayName })
        assertEquals(original.baseYear, saved.baseYear)
        assertEquals(original.annualSavingsTargetWon, saved.annualSavingsTargetWon)
        assertEquals(original.annualSavingsTargetsWon, saved.annualSavingsTargetsWon)
        assertTrue(store.currentState.saved)
        assertFalse(store.currentState.isDirty)
        store.close()
    }

    @Test
    fun itemManagementAdapterCreatesArchivesRestoresAndMovesCategories() = runBlocking {
        val repository = FakeIosItemManagementRepository(
            categories = listOf(
                ExpenseCategory("living", "생활비", 0),
                ExpenseCategory("date", "데이트비", 1),
            ),
        )
        val store = IosItemManagementStore(ItemManagementStateHolder(repository, this))
        settle()
        assertEquals(ManagedItemType.VariableExpense, store.currentState.selectedType)

        store.add()
        store.changeName("여행비")
        store.save()
        settle()
        assertTrue(repository.categories.value.any { it.name == "여행비" })

        store.requestArchive("expense:living")
        store.confirmArchive()
        settle()
        assertTrue(repository.categories.value.first { it.id == "living" }.archived)
        store.showArchived(true)
        assertEquals(listOf("생활비"), store.currentState.items.map { it.name })
        store.restore("expense:living")
        settle()
        assertFalse(repository.categories.value.first { it.id == "living" }.archived)

        store.showArchived(false)
        store.moveDown("expense:living")
        settle()
        assertEquals(listOf("date", "living", "created-1"), repository.categories.value.sortedBy { it.displayOrder }.map { it.id })
        store.close()
    }

    private suspend fun settle() { repeat(30) { yield() } }
}

private class FakeIosItemManagementRepository(
    plans: List<PlanCatalogItem> = listOf(
        PlanCatalogItem(type = PlanItemType.Income, classification = "급여", name = "급여", ownerMemberOrder = 0),
    ),
    categories: List<ExpenseCategory>,
) : ItemManagementRepository {
    val plans = MutableStateFlow(plans)
    val categories = MutableStateFlow(categories)
    override fun observePlanCatalog(): Flow<List<PlanCatalogItem>> = plans
    override fun observeExpenseCategories(): Flow<List<ExpenseCategory>> = categories
    override suspend fun savePlanItem(item: PlanCatalogItem): Long {
        val id = if (item.id == 0L) (plans.value.maxOfOrNull { it.id } ?: 0L) + 1L else item.id
        plans.value = plans.value.filterNot { it.id == id } + item.copy(id = id)
        return id
    }
    override suspend fun createExpenseCategory(name: String): ExpenseCategory {
        val item = ExpenseCategory("created-1", name, categories.value.size)
        categories.value += item
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
        val order = orderedIds.withIndex().associate { it.value to it.index }
        plans.value = plans.value.map { it.copy(displayOrder = order[it.id] ?: it.displayOrder) }
    }
    override suspend fun reorderExpenseCategories(orderedIds: List<String>) {
        val order = orderedIds.withIndex().associate { it.value to it.index }
        categories.value = categories.value.map { it.copy(displayOrder = order[it.id] ?: it.displayOrder) }
    }
}

private class FakeIosSettingsRepository(initial: LedgerSetup) : SetupRepository {
    private val data = MutableStateFlow<LedgerSetup?>(initial)
    val saved = mutableListOf<LedgerSetup>()
    override fun observeSetup(): Flow<LedgerSetup?> = data
    override suspend fun saveSetup(setup: LedgerSetup) {
        saved += setup
        data.value = setup
    }
}
