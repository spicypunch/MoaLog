package kr.jm.moalog.feature.plan.presentation

import kr.jm.moalog.core.model.*
import kr.jm.moalog.feature.plan.domain.SalaryAllocationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SalaryAllocationStateTest {
    @Test fun selectedMonthIsObservedAndSavedWithoutMixingOtherMonths() = runTest {
        val september=YearMonthKey(2026,9);val repo=FakeSalaryRepository(september);val holder=SalaryAllocationStateHolder(SalaryAllocationArgs(september),repo,this)
        holder.start();advanceUntilIdle()
        holder.onAction(SalaryAllocationAction.OpenSalaryEditor)
        holder.onAction(SalaryAllocationAction.ChangeSalary(0,"0"));holder.onAction(SalaryAllocationAction.ChangeSalary(1,"3,200,000"));holder.onAction(SalaryAllocationAction.SaveSalaries);advanceUntilIdle()
        assertEquals(september,repo.observedMonth)
        assertEquals(listOf(0L,3_200_000L),repo.savedIncomes.map{it.amountWon})
        assertEquals(listOf(september,september),repo.savedIncomes.map{it.attributionMonth})
        assertNull(repo.otherMonth.value.incomes.first().amountWon)
        holder.close()
    }

    @Test fun ratioInputStoresHundredthsOfPercent() = runTest {
        val month=YearMonthKey(2026,9);val repo=FakeSalaryRepository(month);val holder=SalaryAllocationStateHolder(SalaryAllocationArgs(month),repo,this)
        holder.onAction(SalaryAllocationAction.OpenCategoryEditor())
        holder.onAction(SalaryAllocationAction.ChangeCategoryName("투자"));holder.onAction(SalaryAllocationAction.ChangeCategoryMethod(SalaryAllocationMethod.SalaryRatio));holder.onAction(SalaryAllocationAction.ChangeCategoryValue("40.25"));holder.onAction(SalaryAllocationAction.SaveCategory);advanceUntilIdle()
        assertEquals(4_025,repo.savedCategory?.rateBasisPoints)
        assertNull(repo.savedCategory?.amountWon)
        holder.close()
    }

    @Test fun oversizedRatioInputCannotWrapIntoAValidPercentage() = runTest {
        val month = YearMonthKey(2026, 9)
        val repo = FakeSalaryRepository(month)
        val holder = SalaryAllocationStateHolder(SalaryAllocationArgs(month), repo, this)

        holder.onAction(SalaryAllocationAction.OpenCategoryEditor())
        holder.onAction(SalaryAllocationAction.ChangeCategoryName("투자"))
        holder.onAction(SalaryAllocationAction.ChangeCategoryMethod(SalaryAllocationMethod.SalaryRatio))
        holder.onAction(SalaryAllocationAction.ChangeCategoryValue("2147483600"))
        holder.onAction(SalaryAllocationAction.SaveCategory)
        advanceUntilIdle()

        assertNull(repo.savedCategory)
        assertEquals("비율은 0%부터 100%까지 입력해 주세요", holder.state.value.error)
        holder.close()
    }

    @Test fun salaryEditUsesMemberOrderRatherThanVisibleListIndex() = runTest {
        val month = YearMonthKey(2026, 9)
        val repo = FakeSalaryRepository(month)
        val holder = SalaryAllocationStateHolder(SalaryAllocationArgs(month, listOf(7, 11)), repo, this)

        holder.onAction(SalaryAllocationAction.OpenSalaryEditor)
        holder.onAction(SalaryAllocationAction.ChangeSalary(11, "2,100,000"))
        holder.onAction(SalaryAllocationAction.ChangeSalary(7, "3,400,000"))
        holder.onAction(SalaryAllocationAction.SaveSalaries)
        advanceUntilIdle()

        assertEquals(listOf(7, 11), repo.savedIncomes.map { it.memberOrder })
        assertEquals(listOf(3_400_000L, 2_100_000L), repo.savedIncomes.map { it.amountWon })
        holder.close()
    }

    @Test fun salaryTotalReportsOverflowWithoutWrapping() {
        val month = YearMonthKey(2026, 9)
        val state = SalaryAllocationUiState(
            month = month,
            sheet = SalaryAllocationSheet(
                month,
                listOf(SalaryIncome(month, 0, Long.MAX_VALUE), SalaryIncome(month, 1, 1)),
                emptyList(),
            ),
        )

        assertNull(state.totalSalaryWon)
        assertEquals(true, state.hasSalaryOverflow)
    }

    @Test fun allocationSummaryKeepsTargetAndChildDifferenceVisible() {
        val month = YearMonthKey(2026, 9)
        val investment = SalaryAllocationCategory(
            id = 4,
            attributionMonth = month,
            name = "투자",
            method = SalaryAllocationMethod.SalaryRatio,
            rateBasisPoints = 4_000,
            children = listOf(SalaryAllocationChild(1, 4, month, "적금", 2_500_000)),
        )
        val state = SalaryAllocationUiState(
            month = month,
            sheet = SalaryAllocationSheet(
                month,
                listOf(SalaryIncome(month, 0, 3_200_000), SalaryIncome(month, 1, 3_300_000)),
                listOf(investment),
            ),
        )

        assertEquals(2_600_000L, state.allocatedTotalWon)
        assertEquals(3_900_000L, state.allocationDifferenceWon)
        assertEquals(4_000, state.allocationProgressBasisPoints)
    }

    @Test fun detailTotalUsesValidGrandchildrenWhenParentAmountIsMissing() {
        val state = detailStateWithGrandchildAmounts(400_000, 400_000)

        assertEquals(800_000L, state.childTotalWon)
        assertEquals(false, state.hasChildTotalOverflow)
    }

    @Test fun detailTotalReportsGrandchildOverflowWhenParentAmountIsMissing() {
        val state = detailStateWithGrandchildAmounts(Long.MAX_VALUE, 1)

        assertEquals(true, state.category!!.children.single().hasSubtotalOverflow())
        assertNull(state.childTotalWon)
        assertEquals(true, state.hasChildTotalOverflow)
    }

    @Test fun detailTotalTreatsMissingGrandchildAmountAsMissingRatherThanOverflow() {
        val state = detailStateWithGrandchildAmounts(400_000, null)

        assertNull(state.childTotalWon)
        assertEquals(false, state.hasChildTotalOverflow)
    }

    @Test fun detailChildCanBeSavedAndDeletedInItsAttributionMonth() = runTest {
        val month = YearMonthKey(2026, 9)
        val category = SalaryAllocationCategory(
            id = 42,
            attributionMonth = month,
            name = "투자",
            method = SalaryAllocationMethod.ChildTotal,
        )
        val repo = FakeSalaryRepository(month).apply {
            current.value = current.value.copy(categories = listOf(category))
        }
        val holder = SalaryAllocationDetailStateHolder(SalaryAllocationDetailArgs(42, month), repo, this)
        holder.start(); advanceUntilIdle()
        holder.onAction(SalaryAllocationDetailAction.OpenChildEditor())
        holder.onAction(SalaryAllocationDetailAction.ChangeName("ETF"))
        holder.onAction(SalaryAllocationDetailAction.ChangeAmount("100000"))
        holder.onAction(SalaryAllocationDetailAction.Save)
        advanceUntilIdle()

        assertEquals(month, repo.savedChild?.attributionMonth)
        assertEquals(42L, repo.savedChild?.categoryId)
        assertEquals(100_000L, repo.savedChild?.amountWon)

        holder.onAction(SalaryAllocationDetailAction.OpenChildEditor(repo.savedChild!!.copy(id = 77)))
        holder.onAction(SalaryAllocationDetailAction.Delete)
        advanceUntilIdle()
        assertEquals(77L, repo.deletedChildId)
        holder.close()
    }

    @Test fun childWithGrandchildrenUsesDerivedSubtotalAndCannotResaveLegacyDirectAmount() = runTest {
        val month = YearMonthKey(2026, 9)
        val child = SalaryAllocationChild(
            id = 7,
            categoryId = 42,
            attributionMonth = month,
            name = "ISA",
            amountWon = 900_000,
            grandchildren = listOf(SalaryAllocationGrandchild(8, 7, month, "수아 ISA", 400_000)),
        )
        val category = SalaryAllocationCategory(
            id = 42,
            attributionMonth = month,
            name = "투자",
            method = SalaryAllocationMethod.ChildTotal,
            children = listOf(child),
        )
        val repo = FakeSalaryRepository(month).apply { current.value = current.value.copy(categories = listOf(category)) }
        val holder = SalaryAllocationDetailStateHolder(SalaryAllocationDetailArgs(42, month), repo, this)
        holder.start(); advanceUntilIdle()

        holder.onAction(SalaryAllocationDetailAction.OpenChildEditor(child))
        assertEquals(true, holder.state.value.draft?.usesGrandchildSubtotal)
        holder.onAction(SalaryAllocationDetailAction.ChangeAmount("123"))
        holder.onAction(SalaryAllocationDetailAction.Save)
        advanceUntilIdle()

        assertNull(repo.savedChild?.amountWon)
        holder.close()
    }

    @Test fun rapidCategorySaveTapsAreClaimedBeforeLaunchingCoroutine() = runTest {
        val month=YearMonthKey(2026,9);val repo=FakeSalaryRepository(month);val holder=SalaryAllocationStateHolder(SalaryAllocationArgs(month),repo,this)
        holder.onAction(SalaryAllocationAction.OpenCategoryEditor())
        holder.onAction(SalaryAllocationAction.ChangeCategoryName("생활비"))
        holder.onAction(SalaryAllocationAction.ChangeCategoryValue("1000"))
        holder.onAction(SalaryAllocationAction.SaveCategory)
        holder.onAction(SalaryAllocationAction.SaveCategory)
        advanceUntilIdle()
        assertEquals(1,repo.saveCategoryCalls)
        holder.close()
    }

    @Test fun remainingCategoryPersistsActualSelectedDeductionIds() = runTest {
        val month=YearMonthKey(2026,9)
        val fixedA=SalaryAllocationCategory(id=1,attributionMonth=month,name="생활비",method=SalaryAllocationMethod.FixedAmount,amountWon=1_000)
        val fixedB=SalaryAllocationCategory(id=2,attributionMonth=month,name="용돈",method=SalaryAllocationMethod.FixedAmount,amountWon=2_000)
        val repo=FakeSalaryRepository(month).apply{current.value=current.value.copy(categories=listOf(fixedA,fixedB))}
        val holder=SalaryAllocationStateHolder(SalaryAllocationArgs(month),repo,this)
        holder.start();advanceUntilIdle()
        holder.onAction(SalaryAllocationAction.OpenCategoryEditor())
        holder.onAction(SalaryAllocationAction.ChangeCategoryName("잔액"))
        holder.onAction(SalaryAllocationAction.ChangeCategoryMethod(SalaryAllocationMethod.RemainingFromSource))
        holder.onAction(SalaryAllocationAction.ToggleDeductedCategory(1))
        holder.onAction(SalaryAllocationAction.SaveCategory);advanceUntilIdle()
        assertEquals(setOf(2L),repo.savedCategory?.deductedCategoryIds)
        holder.close()
    }

    @Test fun grandchildCrudKeepsParentAndMonth() = runTest {
        val month=YearMonthKey(2026,9);val category=SalaryAllocationCategory(id=42,attributionMonth=month,name="투자",method=SalaryAllocationMethod.ChildTotal)
        val repo=FakeSalaryRepository(month).apply{current.value=current.value.copy(categories=listOf(category))}
        val holder=SalaryAllocationDetailStateHolder(SalaryAllocationDetailArgs(42,month),repo,this)
        holder.onAction(SalaryAllocationDetailAction.OpenGrandchildEditor(77))
        holder.onAction(SalaryAllocationDetailAction.ChangeGrandchildName("수아 ISA"))
        holder.onAction(SalaryAllocationDetailAction.ChangeGrandchildAmount("400000"))
        holder.onAction(SalaryAllocationDetailAction.SaveGrandchild)
        holder.onAction(SalaryAllocationDetailAction.SaveGrandchild)
        advanceUntilIdle()
        assertEquals(1,repo.saveGrandchildCalls)
        assertEquals(77,repo.savedGrandchild?.childId)
        assertEquals(month,repo.savedGrandchild?.attributionMonth)
        holder.close()
    }
}

private fun detailStateWithGrandchildAmounts(first: Long?, second: Long?): SalaryAllocationDetailUiState {
    val month = YearMonthKey(2026, 9)
    val child = SalaryAllocationChild(
        id = 7,
        categoryId = 4,
        attributionMonth = month,
        name = "ISA",
        amountWon = null,
        grandchildren = listOf(
            SalaryAllocationGrandchild(11, 7, month, "수아 ISA", first),
            SalaryAllocationGrandchild(12, 7, month, "종민 ISA", second),
        ),
    )
    return SalaryAllocationDetailUiState(
        month = month,
        category = SalaryAllocationCategory(
            id = 4,
            attributionMonth = month,
            name = "투자",
            method = SalaryAllocationMethod.ChildTotal,
            children = listOf(child),
        ),
    )
}

private class FakeSalaryRepository(private val month:YearMonthKey):SalaryAllocationRepository{
    val current=MutableStateFlow(SalaryAllocationSheet(month,listOf(SalaryIncome(month,0,null),SalaryIncome(month,1,null)),emptyList()))
    val otherMonth=MutableStateFlow(SalaryAllocationSheet(YearMonthKey(2026,10),listOf(SalaryIncome(YearMonthKey(2026,10),0,null)),emptyList()))
    var observedMonth:YearMonthKey?=null;var savedIncomes:List<SalaryIncome> = emptyList();var savedCategory:SalaryAllocationCategory?=null
    var savedChild: SalaryAllocationChild? = null
    var savedGrandchild:SalaryAllocationGrandchild?=null
    var saveCategoryCalls=0
    var saveGrandchildCalls=0
    var deletedChildId: Long? = null
    override fun observe(month:YearMonthKey):Flow<SalaryAllocationSheet>{observedMonth=month;return if(month==this.month)current else otherMonth}
    override suspend fun saveIncomes(values:List<SalaryIncome>){savedIncomes=values}
    override suspend fun findCategory(id:Long)=savedCategory
    override suspend fun saveCategory(value:SalaryAllocationCategory):Long{saveCategoryCalls++;savedCategory=value;return 1}
    override suspend fun deleteCategory(id:Long){}
    override suspend fun findChild(id:Long):SalaryAllocationChild?=savedChild?.takeIf { it.id == id }
    override suspend fun saveChild(value:SalaryAllocationChild):Long{savedChild=value;return if(value.id==0L)77L else value.id}
    override suspend fun deleteChild(id:Long){deletedChildId=id}
    override suspend fun findGrandchild(id:Long):SalaryAllocationGrandchild?=null
    override suspend fun saveGrandchild(value:SalaryAllocationGrandchild):Long{saveGrandchildCalls++;savedGrandchild=value;return if(value.id==0L)88L else value.id}
    override suspend fun deleteGrandchild(id:Long)=Unit
}
