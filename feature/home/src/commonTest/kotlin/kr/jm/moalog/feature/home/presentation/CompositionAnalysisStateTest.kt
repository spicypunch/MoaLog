package kr.jm.moalog.feature.home.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.home.domain.HomeRepository
import kr.jm.moalog.feature.home.domain.HomeYearSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class CompositionAnalysisStateTest {
    @Test
    fun negativeSavingsKeepsAlgebraicTotalAndSignedMagnitudePercent() {
        val months = (1..12).map { number ->
            val month = YearMonthKey(2026, number)
            HomeAnnualMonthSnapshot(
                month = month,
                items = listOf(
                    plan(month, "예금", 1_000_000),
                    plan(month, "주식", 500_000),
                    plan(month, "출금", if (number == 1) -350_000 else 0),
                ),
                variableExpenses = emptyList(),
            )
        }

        val result = calculateCompositionAnalysis(CompositionTab.Savings, YearMonthKey(2026, 5), 2026, months)

        assertEquals(17_650_000, result.totalWon)
        assertEquals(listOf("예금", "주식", "출금"), result.rows.map { it.title })
        assertTrue(result.rows.last().isNegative)
        assertTrue(requireNotNull(result.rows.last().percent) < 0.0)
        assertTrue(result.isComplete)
    }

    @Test
    fun variableRowsUseSelectedMonthAndCarryCategoryForRecordsFilter() {
        val may = YearMonthKey(2026, 5)
        val result = calculateCompositionAnalysis(
            CompositionTab.VariableExpense,
            may,
            2026,
            listOf(
                HomeAnnualMonthSnapshot(
                    may,
                    emptyList(),
                    listOf(
                        expense(may, "living", "생활비", 300_000),
                        expense(may, "living", "생활비", 60_000),
                        expense(may, "date", "데이트비", 140_000),
                    ),
                ),
                HomeAnnualMonthSnapshot(YearMonthKey(2026, 4), emptyList(), listOf(expense(YearMonthKey(2026, 4), "living", "생활비", 9_000_000))),
            ),
        )

        assertEquals(500_000, result.totalWon)
        assertEquals("living", result.rows.first().expenseCategoryId)
        assertEquals(72.0, result.rows.first().percent)
    }

    @Test
    fun variableRowsAggregateAnnualPeriodOnlyForSelectedYear() {
        val may = YearMonthKey(2026, 5)
        val result = calculateCompositionAnalysis(
            CompositionTab.VariableExpense,
            may,
            2026,
            listOf(
                HomeAnnualMonthSnapshot(
                    YearMonthKey(2026, 4),
                    emptyList(),
                    listOf(expense(YearMonthKey(2026, 4), "living", "생활비", 100_000)),
                ),
                HomeAnnualMonthSnapshot(
                    YearMonthKey(2026, 6),
                    emptyList(),
                    listOf(expense(YearMonthKey(2026, 6), "living", "생활비", 200_000)),
                ),
                HomeAnnualMonthSnapshot(
                    YearMonthKey(2027, 5),
                    emptyList(),
                    listOf(expense(YearMonthKey(2027, 5), "living", "생활비", 300_000)),
                ),
            ),
            period = CompositionPeriod.Annual,
        )

        assertEquals(1, result.rows.size)
        assertEquals("생활비", result.rows.first().title)
        assertEquals(300_000L, result.totalWon)
    }

    @Test
    fun fixedAndSavingsRowsUseFullPlanIdentityIncludingCategoryAndFlags() {
        val month = YearMonthKey(2026, 5)
        val result = calculateCompositionAnalysis(
            CompositionTab.Savings,
            month,
            2026,
            listOf(
                HomeAnnualMonthSnapshot(
                    month,
                    listOf(
                        planItem(
                            month,
                            "적금",
                            100_000,
                            PlanItemType.Savings,
                            category = "주거",
                            ownerOrder = 0,
                            includePurposeAccount = false,
                            includeNetSavings = false,
                        ),
                        planItem(
                            month,
                            "적금",
                            200_000,
                            PlanItemType.Savings,
                            category = "식비",
                            ownerOrder = 0,
                            includePurposeAccount = false,
                            includeNetSavings = false,
                        ),
                        planItem(
                            month,
                            "적금",
                            300_000,
                            PlanItemType.Savings,
                            category = "주거",
                            ownerOrder = 0,
                            includePurposeAccount = true,
                            includeNetSavings = false,
                        ),
                        planItem(
                            month,
                            "적금",
                            400_000,
                            PlanItemType.Savings,
                            category = "주거",
                            ownerOrder = 1,
                            includePurposeAccount = false,
                            includeNetSavings = false,
                        ),
                    ),
                    emptyList(),
                ),
            ),
        )

        assertEquals(4, result.rows.size)
        assertEquals(4, result.rows.map { it.id }.distinct().size)
    }

    @Test
    fun annualRowsGroupByCatalogIdWhenDisplayMetadataChanges() {
        val january = YearMonthKey(2026, 1)
        val february = YearMonthKey(2026, 2)
        val result = calculateCompositionAnalysis(
            CompositionTab.Savings,
            january,
            2026,
            listOf(
                HomeAnnualMonthSnapshot(january, listOf(plan(january, "주택 적금", 100_000).copy(catalogId = 42)), emptyList()),
                HomeAnnualMonthSnapshot(february, listOf(plan(february, "신혼집 적금", 200_000).copy(catalogId = 42)), emptyList()),
            ),
        )

        assertEquals(1, result.rows.size)
        assertEquals("catalog|42", result.rows.single().id)
        assertEquals(300_000L, result.rows.single().amountWon)
    }

    @Test
    fun checkedMoneyAggregationRejectsLongOverflowInsteadOfWrapping() {
        val may = YearMonthKey(2026, 5)
        assertFailsWith<ArithmeticException> {
            calculateCompositionAnalysis(
                CompositionTab.VariableExpense,
                may,
                2026,
                listOf(
                    HomeAnnualMonthSnapshot(
                        may,
                        emptyList(),
                        listOf(
                            expense(may, "living", "생활비", Long.MAX_VALUE),
                            expense(may, "living", "생활비", 1L),
                        ),
                    ),
                ),
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun stateHolderShowsLoadErrorForVariableAnnualOverflow() = runTest {
        val snapshots = (1..12).map { month ->
            HomeAnnualMonthSnapshot(
                YearMonthKey(2026, month),
                emptyList(),
                listOf(
                    expense(
                        YearMonthKey(2026, month),
                        "living",
                        "생활비",
                        if (month == 5) 1L else Long.MAX_VALUE,
                    ),
                ),
            )
        }

        val holder = CompositionAnalysisStateHolder(
            repository = FakeHomeRepository(
                HomeYearSnapshot(
                    setup = LedgerSetup(
                        ledgerName = "테스트 가계부",
                        members = listOf(LedgerMember("준호", 0), LedgerMember("민지", 1)),
                        baseYear = 2026,
                        annualSavingsTargetWon = null,
                    ),
                    months = snapshots,
                ),
            ),
            initialMonth = YearMonthKey(2026, 5),
            initialTab = CompositionTab.VariableExpense,
            scope = backgroundScope,
        )

        runCurrent()
        holder.onAction(CompositionAnalysisAction.SelectPeriod(CompositionPeriod.Annual))
        runCurrent()

        assertEquals("금액 합계가 허용 범위를 넘었어요", holder.state.value.loadError)

        holder.onAction(CompositionAnalysisAction.SelectPeriod(CompositionPeriod.Monthly))

        assertEquals(null, holder.state.value.loadError)
        assertEquals(1L, holder.state.value.totalWon)
        holder.close()
    }

    private fun plan(month: YearMonthKey, name: String, amount: Long) = MonthlyPlanItem(
        type = PlanItemType.Savings,
        attributionMonth = month,
        name = name,
        amountWon = amount,
        category = "저축",
        status = PlanItemStatus.Confirmed,
        ownerMemberOrder = 0,
        memo = null,
    )

    private fun planItem(
        month: YearMonthKey,
        name: String,
        amountWon: Long,
        type: PlanItemType,
        category: String,
        ownerOrder: Int,
        includePurposeAccount: Boolean,
        includeNetSavings: Boolean,
    ) = MonthlyPlanItem(
        type = type,
        attributionMonth = month,
        name = name,
        amountWon = amountWon,
        category = category,
        status = PlanItemStatus.Confirmed,
        ownerMemberOrder = ownerOrder,
        memo = null,
        includePurposeAccount = includePurposeAccount,
        includeNetSavings = includeNetSavings,
    )

    private fun expense(month: YearMonthKey, id: String, name: String, amount: Long) = ExpenseRecord(
        categoryId = id,
        categoryName = name,
        attributionMonth = month,
        actualDate = null,
        detail = null,
        amountWon = amount,
        overspent = false,
    )

    private class FakeHomeRepository(private val snapshot: HomeYearSnapshot) : HomeRepository {
        override fun observeYear(year: Int): Flow<HomeYearSnapshot> = flowOf(snapshot)

        override suspend fun updateAnnualSavingsTarget(targetWon: Long?) {
            throw IllegalStateException("Unexpected goal update call: target=$targetWon")
        }

        override suspend fun updateAnnualSavingsTarget(year: Int, targetWon: Long?) {
            throw IllegalStateException("Unexpected goal update call: year=$year target=$targetWon")
        }
    }
}
