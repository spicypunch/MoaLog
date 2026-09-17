package kr.jm.moalog.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SalaryAllocationTest {
    private val month = YearMonthKey(2026, 9)
    private val incomes = listOf(SalaryIncome(month, 0, 3_200_000), SalaryIncome(month, 1, 3_300_000))

    @Test fun ratioUsesBasisPointsWithoutFloatingPoint() {
        assertEquals(2_600_000, calculateRatioAmount(6_500_000, 4_000))
        assertEquals(1, calculateRatioAmount(3, 3_333))
        assertEquals(Long.MAX_VALUE, calculateRatioAmount(Long.MAX_VALUE, 10_000))
    }

    @Test fun fourMethodsAreCalculatedAndMissingRemainsMissing() {
        val fixed = category(1, SalaryAllocationMethod.FixedAmount, amount = 100)
        val ratio = category(2, SalaryAllocationMethod.SalaryRatio, rate = 1_000)
        val childTotal = category(3, SalaryAllocationMethod.ChildTotal, children = listOf(SalaryAllocationChild(1, 3, month, "A", 20), SalaryAllocationChild(2, 3, month, "B", 30)))
        val remaining = category(4, SalaryAllocationMethod.RemainingFromSource)
        val targets = SalaryAllocationSheet(month, incomes, listOf(fixed, ratio, childTotal, remaining)).categoryTargets()
        assertEquals(100, targets[1])
        assertEquals(650_000, targets[2])
        assertEquals(50, targets[3])
        assertEquals(5_849_850, targets[4])
        assertNull(category(5, SalaryAllocationMethod.ChildTotal).targetAmount(incomes))
        assertNull(category(6, SalaryAllocationMethod.SalaryRatio, rate = 1_000).targetAmount(listOf(incomes.first())))
    }

    @Test fun totalsReturnMissingInsteadOfWrappingWhenWonOverflows() {
        assertNull(listOf(Long.MAX_VALUE, 1L).sumWonOrNull())
        assertNull(
            category(
                id = 7,
                method = SalaryAllocationMethod.ChildTotal,
                children = listOf(
                    SalaryAllocationChild(1, 7, month, "A", Long.MAX_VALUE),
                    SalaryAllocationChild(2, 7, month, "B", 1),
                ),
            ).childTotal(),
        )
        assertNull(
            category(8, SalaryAllocationMethod.SalaryRatio, rate = 5_000)
                .targetAmount(listOf(SalaryIncome(month, 0, Long.MAX_VALUE), SalaryIncome(month, 1, 1))),
        )
    }

    @Test fun threeLevelHierarchyUsesGrandchildTotals() {
        val isa = SalaryAllocationChild(
            id=10,categoryId=3,attributionMonth=month,name="ISA",amountWon=null,
            grandchildren=listOf(
                SalaryAllocationGrandchild(1,10,month,"수아 ISA",400_000),
                SalaryAllocationGrandchild(2,10,month,"종민 ISA",600_000),
            ),
        )
        assertEquals(1_000_000,isa.subtotal())
        assertEquals(1_000_000,category(3,SalaryAllocationMethod.ChildTotal,children=listOf(isa)).childTotal())
    }

    @Test fun remainingSubtractsOnlyPersistedSelectedCategoryIdsAndLegacyRowsSubtractAll() {
        val first=category(1,SalaryAllocationMethod.FixedAmount,amount=1_000)
        val second=category(2,SalaryAllocationMethod.FixedAmount,amount=2_000)
        val selected=category(3,SalaryAllocationMethod.RemainingFromSource).copy(deductedCategoryIds=setOf(2))
        val legacy=category(4,SalaryAllocationMethod.RemainingFromSource)
        val selectedTargets=SalaryAllocationSheet(month,incomes,listOf(first,second,selected)).categoryTargets()
        assertEquals(6_498_000,selectedTargets[3])
        val legacyTargets=SalaryAllocationSheet(month,incomes,listOf(first,second,legacy)).categoryTargets()
        assertEquals(6_497_000,legacyTargets[4])
    }

    private fun category(id:Long,method:SalaryAllocationMethod,amount:Long?=null,rate:Int?=null,children:List<SalaryAllocationChild> = emptyList()) = SalaryAllocationCategory(id,1,month,"항목$id",null,method,amount,rate,null,id.toInt(),children)
}
