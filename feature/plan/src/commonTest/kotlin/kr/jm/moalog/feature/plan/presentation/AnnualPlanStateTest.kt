package kr.jm.moalog.feature.plan.presentation

import kr.jm.moalog.core.database.PlanCopyPreview
import kr.jm.moalog.core.database.PlanCopyRequest
import kr.jm.moalog.core.database.PlanCopyResult
import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.plan.domain.PlanCurrentMonthProvider
import kr.jm.moalog.feature.plan.domain.PlanMonth
import kr.jm.moalog.feature.plan.domain.PlanRepository
import kr.jm.moalog.feature.plan.domain.PlanYearMonth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AnnualPlanStateTest {
    @Test
    fun groupsRenamedMonthlyRowsByStableCatalogId() {
        val january = YearMonthKey(2026, 1)
        val february = YearMonthKey(2026, 2)
        val (rows, _) = calculateAnnualPlan(
            2026,
            listOf(
                PlanYearMonth(january, listOf(item(january, PlanItemType.Income, "월급", 100, PlanItemStatus.Confirmed, catalogId = 7)), emptyList()),
                PlanYearMonth(february, listOf(item(february, PlanItemType.Income, "급여", 200, PlanItemStatus.Confirmed, catalogId = 7)), emptyList()),
            ),
        )
        val row = rows.getValue(AnnualPlanSection.Income).single()
        assertEquals("catalog|7", row.key)
        assertEquals(listOf(100L, 200L), row.cells.take(2).map { it.amountWon })
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun stateHolderLoadsYearAndKeepsViewSelectionInState() = runTest {
        val holder = AnnualPlanStateHolder(
            AnnualPlanArgs(2027),
            repository = FakeRepository,
            currentMonthProvider = PlanCurrentMonthProvider { YearMonthKey(2027, 4) },
            parentScope = this,
        )

        holder.start()
        advanceUntilIdle()
        holder.onAction(AnnualPlanAction.SelectSection(AnnualPlanSection.Savings))
        holder.onAction(AnnualPlanAction.SelectViewMode(AnnualPlanViewMode.MonthlyList))

        assertEquals(12, holder.state.value.months.size)
        assertEquals(AnnualPlanSection.Savings, holder.state.value.selectedSection)
        assertEquals(AnnualPlanViewMode.MonthlyList, holder.state.value.viewMode)
        holder.close()
    }

    @Test
    fun aggregatesActualPlanAndExpenseDataAndCalculatesBalance() {
        val january = YearMonthKey(2026, 1)
        val snapshots = listOf(
            PlanYearMonth(
                january,
                listOf(
                    item(january, PlanItemType.Income, "월급", 1_000_000, PlanItemStatus.Confirmed),
                    item(january, PlanItemType.FixedExpense, "월세", 300_000, PlanItemStatus.Confirmed),
                    item(january, PlanItemType.Savings, "적금", 200_000, PlanItemStatus.Estimated),
                ),
                listOf(expense(january, "식비", 100_000), expense(january, "식비", 50_000)),
            ),
        )

        val (rows, months) = calculateAnnualPlan(2026, snapshots)

        assertEquals(1_000_000, months.first().income.amountWon)
        assertEquals(150_000, months.first().variableExpense.amountWon)
        assertEquals(350_000, months.first().balanceWon)
        assertEquals(150_000, rows.getValue(AnnualPlanSection.VariableExpense).single().knownTotalWon)
        assertEquals(12, months.size)
        assertEquals(AnnualCellStatus.Missing, months[1].income.status)
        assertEquals(AnnualCellStatus.Zero, months[1].variableExpense.status)
    }

    @Test
    fun keepsMissingPartialAndEnteredZeroDistinct() {
        val january = YearMonthKey(2026, 1)
        val february = YearMonthKey(2026, 2)
        val (_, months) = calculateAnnualPlan(
            2026,
            listOf(
                PlanYearMonth(january, listOf(item(january, PlanItemType.Income, "월급", 0, PlanItemStatus.Confirmed)), emptyList()),
                PlanYearMonth(february, listOf(item(february, PlanItemType.Income, "월급", null, PlanItemStatus.Estimated)), emptyList()),
            ),
        )

        assertEquals(AnnualCellStatus.Zero, months[0].income.status)
        assertEquals(0, months[0].income.amountWon)
        assertEquals(AnnualCellStatus.Partial, months[1].income.status)
        assertNull(months[1].income.amountWon)
        assertEquals(AnnualCellStatus.Missing, months[2].income.status)
        assertFalse(months[2].income.isComplete)
    }

    @Test
    fun groupsSamePlanIdentityAndMarksMixedStatus() {
        val month = YearMonthKey(2026, 1)
        val (rows, _) = calculateAnnualPlan(
            2026,
            listOf(
                PlanYearMonth(
                    month,
                    listOf(
                        item(month, PlanItemType.Income, "급여", 100, PlanItemStatus.Confirmed),
                        item(month, PlanItemType.Income, "급여", 200, PlanItemStatus.Estimated),
                    ),
                    emptyList(),
                ),
            ),
        )

        val january = rows.getValue(AnnualPlanSection.Income).single().cells.first()
        assertEquals(300, january.amountWon)
        assertEquals(AnnualCellStatus.Mixed, january.status)
    }

    @Test
    fun preservesPersistedItemIdForAnnualCellEditing() {
        val month = YearMonthKey(2026, 8)
        val (rows, _) = calculateAnnualPlan(
            2026,
            listOf(PlanYearMonth(month, listOf(item(month, PlanItemType.FixedExpense, "통신비", 55_000, PlanItemStatus.Confirmed, id = 41)), emptyList())),
        )

        assertEquals(41, rows.getValue(AnnualPlanSection.FixedExpense).single().cells[7].sourceItemId)
        assertNull(rows.getValue(AnnualPlanSection.FixedExpense).single().cells[6].sourceItemId)
    }

    @Test
    fun calculateAnnualPlanThrowsOnAnnualTotalOverflow() {
        val snapshots = (1..12).map { month ->
            PlanYearMonth(
                YearMonthKey(2026, month),
                listOf(item(YearMonthKey(2026, month), PlanItemType.Income, "월급", Long.MAX_VALUE, PlanItemStatus.Confirmed)),
                emptyList(),
            )
        }

        assertFailsWith<ArithmeticException> {
            calculateAnnualPlan(2026, snapshots)
        }
    }

    @Test
    fun calculateAnnualPlanThrowsWhenMonthlyBalanceSubtractionOverflows() {
        val january = YearMonthKey(2026, 1)

        assertFailsWith<ArithmeticException> {
            calculateAnnualPlan(
                2026,
                listOf(
                    PlanYearMonth(
                        january,
                        listOf(
                            item(january, PlanItemType.Income, "월급", 0L, PlanItemStatus.Confirmed),
                            item(january, PlanItemType.FixedExpense, "고정비", Long.MAX_VALUE, PlanItemStatus.Confirmed),
                            item(january, PlanItemType.Savings, "저축", 0L, PlanItemStatus.Confirmed),
                        ),
                        listOf(expense(january, "변동비", Long.MAX_VALUE)),
                    ),
                ),
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun stateHolderReportsOverflowAsErrorDuringLoad() = runTest {
        val repository = OverflowingAnnualPlanRepository(
            (1..12).map { month ->
                PlanYearMonth(
                    YearMonthKey(2026, month),
                    listOf(item(YearMonthKey(2026, month), PlanItemType.Income, "월급", Long.MAX_VALUE, PlanItemStatus.Confirmed)),
                    emptyList(),
                )
            },
        )
        val holder = AnnualPlanStateHolder(
            AnnualPlanArgs(2026),
            repository = repository,
            currentMonthProvider = PlanCurrentMonthProvider { YearMonthKey(2026, 1) },
            parentScope = this,
        )

        holder.start()
        advanceUntilIdle()

        assertEquals("연간 계획을 불러오지 못했어요", holder.state.value.error)
        holder.close()
    }

    private fun item(
        month: YearMonthKey,
        type: PlanItemType,
        name: String,
        amountWon: Long?,
        status: PlanItemStatus,
        id: Long = 0,
        catalogId: Long = 0,
    ) = MonthlyPlanItem(
        id = id,
        type = type,
        attributionMonth = month,
        name = name,
        amountWon = amountWon,
        category = "기타",
        status = status,
        ownerMemberOrder = null,
        memo = null,
        catalogId = catalogId,
    )

    private fun expense(month: YearMonthKey, category: String, amount: Long) = ExpenseRecord(
        categoryId = category,
        categoryName = category,
        attributionMonth = month,
        actualDate = null,
        detail = null,
        amountWon = amount,
        overspent = false,
    )

    private object FakeRepository : PlanRepository {
        override fun observeMonth(month: YearMonthKey): Flow<PlanMonth> = flowOf(PlanMonth(emptyList(), 0))
        override fun observeYear(year: Int): Flow<List<PlanYearMonth>> = flowOf(
            listOf(PlanYearMonth(YearMonthKey(year, 1), emptyList(), emptyList())),
        )
        override suspend fun previewCopy(request: PlanCopyRequest): PlanCopyPreview = error("unused")
        override suspend fun copyToMonths(request: PlanCopyRequest): PlanCopyResult = error("unused")
        override suspend fun find(id: Long): MonthlyPlanItem? = null
        override suspend fun save(item: MonthlyPlanItem): Long = item.id
        override suspend fun delete(id: Long) = Unit
    }

    private class OverflowingAnnualPlanRepository(snapshots: List<PlanYearMonth>) : PlanRepository {
        private val snapshotsFlow = flowOf(snapshots)

        override fun observeMonth(month: YearMonthKey): Flow<PlanMonth> = flowOf(PlanMonth(emptyList(), 0))
        override fun observeYear(year: Int): Flow<List<PlanYearMonth>> = snapshotsFlow
        override suspend fun previewCopy(request: PlanCopyRequest): PlanCopyPreview = error("unused")
        override suspend fun copyToMonths(request: PlanCopyRequest): PlanCopyResult = error("unused")
        override suspend fun find(id: Long): MonthlyPlanItem? = null
        override suspend fun save(item: MonthlyPlanItem): Long = item.id
        override suspend fun delete(id: Long) = Unit
    }
}
