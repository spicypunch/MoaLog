package kr.jm.moalog.di

import kr.jm.moalog.core.database.ExistingPlanPolicy
import kr.jm.moalog.core.database.FixedCostApplyResult
import kr.jm.moalog.core.database.FixedCostPlanPreview
import kr.jm.moalog.core.database.FixedCostPlanPreviewRow
import kr.jm.moalog.core.model.FixedCostCheckItem
import kr.jm.moalog.core.model.FixedCostCheckSheet
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.SalaryAllocationCategory
import kr.jm.moalog.core.model.SalaryAllocationChild
import kr.jm.moalog.core.model.SalaryAllocationGrandchild
import kr.jm.moalog.core.model.SalaryAllocationMethod
import kr.jm.moalog.core.model.SalaryAllocationSheet
import kr.jm.moalog.core.model.SalaryIncome
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.plan.domain.FixedCostCheckRepository
import kr.jm.moalog.feature.plan.domain.SalaryAllocationRepository
import kr.jm.moalog.feature.plan.presentation.FixedCostCheckArgs
import kr.jm.moalog.feature.plan.presentation.FixedCostCheckStateHolder
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationArgs
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationDetailArgs
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationDetailStateHolder
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationStateHolder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosStage2StoresTest {
    @Test
    fun salaryStoreAcceptsSupportedLowerBoundMonth() = runBlocking {
        val month = YearMonthKey(1900, 1)
        val repository = Stage2SalaryRepository(month)
        val holder = SalaryAllocationStateHolder(
            SalaryAllocationArgs(month, listOf(0, 1)),
            repository,
            this,
        )
        val store = IosSalaryAllocationStore(holder)
        settle()

        assertEquals(month, repository.observedMonth)
        store.close()
    }

    @Test
    fun fixedCostStoreAcceptsSupportedLowerBoundMonth() = runBlocking {
        val month = YearMonthKey(1900, 1)
        val source = FixedCostCheckItem(
            id = 1,
            attributionMonth = month,
            payerMemberOrder = null,
            name = "관리비",
            amountWon = 100_000,
        )
        val repository = Stage2FixedRepository(month, source)
        val holder = FixedCostCheckStateHolder(
            FixedCostCheckArgs(month, listOf(0, 1)),
            repository,
            this,
        )
        val store = IosFixedCostCheckStore(holder)
        settle()

        assertEquals(month, repository.observedMonth)
        store.close()
    }

    @Test
    fun salaryStoreUsesRealMemberOrderAndPersistsInputs() = runBlocking {
        val month = YearMonthKey(2026, 5)
        val repository = Stage2SalaryRepository(month, memberOrders = listOf(3, 7))
        val holder = SalaryAllocationStateHolder(
            SalaryAllocationArgs(month, listOf(3, 7)),
            repository,
            this,
        )
        val store = IosSalaryAllocationStore(holder)
        settle()

        store.openSalaryEditor()
        store.changeSalary(3, "3,100,000")
        store.changeSalary(7, "3,400,000")
        store.saveSalaries()
        settle()

        assertEquals(listOf(3, 7), repository.savedIncomes.map { it.memberOrder })
        assertEquals(listOf(3_100_000L, 3_400_000L), repository.savedIncomes.map { it.amountWon })
        store.close()
    }

    @Test
    fun detailStoreAddsAndEditsChildThroughSharedHolder() = runBlocking {
        val month = YearMonthKey(2026, 5)
        val repository = Stage2SalaryRepository(month)
        val holder = SalaryAllocationDetailStateHolder(
            SalaryAllocationDetailArgs(categoryId = 8, month = month),
            repository,
            this,
        )
        val store = IosSalaryAllocationDetailStore(holder)
        settle()

        store.openNewChildEditor()
        store.changeName("수아 ISA")
        store.changeAmount("400000")
        store.changeMemo("국민은행")
        store.save()
        settle()

        assertEquals("수아 ISA", repository.savedChild?.name)
        assertEquals(400_000L, repository.savedChild?.amountWon)
        assertEquals("국민은행", repository.savedChild?.memo)
        store.close()
    }

    @Test
    fun detailStoreEditsAndDeletesExistingChild() = runBlocking {
        val month = YearMonthKey(2026, 5)
        val repository = Stage2SalaryRepository(month)
        val holder = SalaryAllocationDetailStateHolder(
            SalaryAllocationDetailArgs(categoryId = 8, month = month),
            repository,
            this,
        )
        val store = IosSalaryAllocationDetailStore(holder)
        settle()
        val child = store.currentState.category?.children?.single()
        assertNotNull(child)

        store.openChildEditor(child)
        store.changeName("ISA 묶음")
        store.save()
        settle()
        assertEquals(18L, repository.savedChild?.id)
        assertEquals("ISA 묶음", repository.savedChild?.name)
        assertEquals(null, repository.savedChild?.amountWon)

        store.openChildEditor(child)
        store.delete()
        settle()
        assertEquals(18L, repository.deletedChildId)
        store.close()
    }

    @Test
    fun detailStorePersistsGrandchildAndExpandsItsParent() = runBlocking {
        val month = YearMonthKey(2026, 5)
        val repository = Stage2SalaryRepository(month)
        val holder = SalaryAllocationDetailStateHolder(
            SalaryAllocationDetailArgs(categoryId = 8, month = month),
            repository,
            this,
        )
        val store = IosSalaryAllocationDetailStore(holder)
        settle()

        store.openNewGrandchildEditor(childId = 18)
        store.changeGrandchildName("수아 ISA")
        store.changeGrandchildAmount("400000")
        store.changeGrandchildMemo("국민은행")
        store.saveGrandchild()
        settle()

        assertEquals("수아 ISA", repository.savedGrandchild?.name)
        assertEquals(400_000L, repository.savedGrandchild?.amountWon)
        assertTrue(18L in store.currentState.expandedChildIds)
        store.close()
    }

    @Test
    fun detailStoreDeletesGrandchildThroughSharedHolder() = runBlocking {
        val month = YearMonthKey(2026, 5)
        val repository = Stage2SalaryRepository(month)
        val holder = SalaryAllocationDetailStateHolder(
            SalaryAllocationDetailArgs(categoryId = 8, month = month),
            repository,
            this,
        )
        val store = IosSalaryAllocationDetailStore(holder)
        settle()
        val child = store.currentState.category?.children?.single()
        val grandchild = child?.grandchildren?.single()
        assertNotNull(child)
        assertNotNull(grandchild)

        store.openGrandchildEditor(childId = child.id, item = grandchild)
        store.deleteGrandchild()
        settle()

        assertEquals(grandchild.id, repository.deletedGrandchildId)
        store.close()
    }

    @Test
    fun fixedCostStoreExposesConflictRowsAndKeepsExistingOnExplicitChoice() = runBlocking {
        val month = YearMonthKey(2026, 5)
        val source = FixedCostCheckItem(
            id = 12,
            attributionMonth = month,
            payerMemberOrder = null,
            name = "아파트 관리비",
            amountWon = 250_000,
        )
        val repository = Stage2FixedRepository(month, source)
        val holder = FixedCostCheckStateHolder(
            FixedCostCheckArgs(month, listOf(0, 1)),
            repository,
            this,
        )
        val store = IosFixedCostCheckStore(holder)
        settle()

        store.requestApply()
        settle()
        val row = store.conflictRows().single()
        assertEquals(source.id, row.id)
        assertTrue(row.hasExistingItem)
        assertNotNull(store.currentState.conflictPreview)

        store.keepExisting()
        settle()
        assertEquals(ExistingPlanPolicy.KeepExisting, repository.policy)
        assertFalse(store.currentState.resultMessage.isNullOrBlank())
        store.close()
    }

    private suspend fun settle() { repeat(10) { yield() } }
}

private class Stage2SalaryRepository(
    private val month: YearMonthKey,
    memberOrders: List<Int> = listOf(0, 1),
) : SalaryAllocationRepository {
    private val category = SalaryAllocationCategory(
        id = 8,
        attributionMonth = month,
        name = "투자",
        method = SalaryAllocationMethod.FixedAmount,
        amountWon = 2_600_000,
        children = listOf(
            SalaryAllocationChild(
                id = 18,
                categoryId = 8,
                attributionMonth = month,
                name = "ISA",
                amountWon = 999_999,
                grandchildren = listOf(
                    SalaryAllocationGrandchild(
                        id = 19,
                        childId = 18,
                        attributionMonth = month,
                        name = "수아 ISA",
                        amountWon = 400_000,
                    ),
                ),
            ),
        ),
    )
    private val mutable = MutableStateFlow(
        SalaryAllocationSheet(
            month,
            memberOrders.map { SalaryIncome(month, it, null) },
            listOf(category),
        ),
    )
    var savedIncomes: List<SalaryIncome> = emptyList()
    var savedChild: SalaryAllocationChild? = null
    var savedGrandchild: SalaryAllocationGrandchild? = null
    var deletedChildId: Long? = null
    var deletedGrandchildId: Long? = null
    var observedMonth: YearMonthKey? = null

    override fun observe(month: YearMonthKey): Flow<SalaryAllocationSheet> {
        observedMonth = month
        return mutable
    }
    override suspend fun saveIncomes(values: List<SalaryIncome>) { savedIncomes = values }
    override suspend fun findCategory(id: Long): SalaryAllocationCategory? = category.takeIf { it.id == id }
    override suspend fun saveCategory(value: SalaryAllocationCategory): Long = value.id.takeIf { it != 0L } ?: 9L
    override suspend fun deleteCategory(id: Long) = Unit
    override suspend fun findChild(id: Long): SalaryAllocationChild? =
        savedChild?.takeIf { it.id == id } ?: category.children.firstOrNull { it.id == id }
    override suspend fun saveChild(value: SalaryAllocationChild): Long {
        savedChild = value
        return value.id.takeIf { it != 0L } ?: 20L
    }
    override suspend fun deleteChild(id: Long) { deletedChildId = id }
    override suspend fun findGrandchild(id: Long): SalaryAllocationGrandchild? =
        savedGrandchild?.takeIf { it.id == id }
            ?: category.children.flatMap { it.grandchildren }.firstOrNull { it.id == id }
    override suspend fun saveGrandchild(value: SalaryAllocationGrandchild): Long {
        savedGrandchild = value
        return value.id.takeIf { it != 0L } ?: 21L
    }
    override suspend fun deleteGrandchild(id: Long) { deletedGrandchildId = id }
}

private class Stage2FixedRepository(
    private val month: YearMonthKey,
    private val source: FixedCostCheckItem,
) : FixedCostCheckRepository {
    private val mutable = MutableStateFlow(FixedCostCheckSheet(month, listOf(source)))
    var policy: ExistingPlanPolicy? = null
    var observedMonth: YearMonthKey? = null

    override fun observe(month: YearMonthKey): Flow<FixedCostCheckSheet> {
        observedMonth = month
        return mutable
    }
    override suspend fun find(id: Long): FixedCostCheckItem? = source.takeIf { it.id == id }
    override suspend fun save(value: FixedCostCheckItem): Long = value.id.takeIf { it != 0L } ?: 30L
    override suspend fun delete(id: Long) = Unit
    override suspend fun preview(
        month: YearMonthKey,
        items: List<FixedCostCheckItem>,
    ): FixedCostPlanPreview = FixedCostPlanPreview(
        month,
        items.map { item ->
            FixedCostPlanPreviewRow(
                item,
                MonthlyPlanItem(
                    id = 99,
                    type = PlanItemType.FixedExpense,
                    attributionMonth = month,
                    name = item.name,
                    amountWon = 200_000,
                    category = "고정비",
                    status = PlanItemStatus.Estimated,
                    ownerMemberOrder = item.payerMemberOrder,
                    memo = null,
                ),
            )
        },
    )

    override suspend fun apply(
        month: YearMonthKey,
        items: List<FixedCostCheckItem>,
        policy: ExistingPlanPolicy,
    ): FixedCostApplyResult {
        this.policy = policy
        return FixedCostApplyResult(
            insertedItemCount = if (policy == ExistingPlanPolicy.Overwrite) items.size else 0,
            overwrittenItemCount = if (policy == ExistingPlanPolicy.Overwrite) items.size else 0,
            skippedConflictCount = if (policy == ExistingPlanPolicy.KeepExisting) items.size else 0,
        )
    }
}
