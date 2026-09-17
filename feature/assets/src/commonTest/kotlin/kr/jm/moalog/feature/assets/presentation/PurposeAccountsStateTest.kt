package kr.jm.moalog.feature.assets.presentation

import kr.jm.moalog.core.model.AssetGrowthRule
import kr.jm.moalog.core.model.AssetKind
import kr.jm.moalog.core.model.AssetItem
import kr.jm.moalog.core.model.AssetPortfolio
import kr.jm.moalog.core.model.AssetType
import kr.jm.moalog.core.model.AssetValuation
import kr.jm.moalog.core.model.AssetValueStatus
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.assets.domain.AssetRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PurposeAccountsStateTest {
    private val may = YearMonthKey(2026, 5)
    private val ordinary = AssetItem(1, "일반 예금", AssetType.Deposit)
    private val travel = AssetItem(2, "여행 통장", AssetType.Deposit, kind = AssetKind.PurposeAccount)
    private val emergency = AssetItem(3, "비상금", AssetType.Cash, kind = AssetKind.PurposeAccount)

    @Test
    fun filtersPurposeAccountsAndPreservesConfirmedEstimatedAndMissingValues() {
        val portfolio = AssetPortfolio(
            assets = listOf(ordinary, travel, emergency),
            valuations = listOf(
                AssetValuation(1, may, 99_000L),
                AssetValuation(2, may, 0L),
                AssetValuation(2, may.plusMonths(1), 200L),
            ),
            growthRules = listOf(AssetGrowthRule(3, may, 1, 1_000L, 100L)),
        )

        val state = buildPurposeAccountsState(may, portfolio)

        assertEquals(listOf(2L, 3L), state.rows.map { it.asset.id })
        assertEquals(1_000L, state.enteredTotalWon)
        assertEquals(AssetValueStatus.Confirmed, state.rows.first().value.status)
        assertEquals(AssetValueStatus.Estimated, state.rows.last().value.status)
        assertEquals(0L, state.rows.first().value.amountWon)
        assertEquals(200L, state.rows.first().monthlyChangeWon)
        assertEquals(AssetValueStatus.Confirmed, state.rows.first().monthlyChangeStatus)
        assertEquals(100L, state.rows.last().monthlyChangeWon)
        assertEquals(AssetValueStatus.Estimated, state.rows.last().monthlyChangeStatus)
    }

    @Test
    fun noEnteredPurposeAccountAmountRemainsMissingRatherThanZero() {
        val state = buildPurposeAccountsState(may, AssetPortfolio(listOf(travel), emptyList()))

        assertNull(state.enteredTotalWon)
        assertNull(state.previousMonthDeltaWon)
        assertNull(state.rows.single().value.amountWon)
        assertNull(state.rows.single().monthlyChangeWon)
    }

    @Test
    fun zeroIsAnEnteredTotalAndDeltaRequiresValuesInBothMonths() {
        val currentOnly = buildPurposeAccountsState(
            may,
            AssetPortfolio(listOf(travel), listOf(AssetValuation(2, may, 0L))),
        )
        assertEquals(0L, currentOnly.enteredTotalWon)
        assertNull(currentOnly.previousMonthDeltaWon)

        val withPrevious = buildPurposeAccountsState(
            may,
            AssetPortfolio(
                listOf(travel),
                listOf(AssetValuation(2, may.plusMonths(-1), 300L), AssetValuation(2, may, 0L)),
            ),
        )
        assertEquals(-300L, withPrevious.previousMonthDeltaWon)
    }

    @Test
    fun previousMonthDeltaIsHiddenWhenEitherMonthIsPartiallyMissing() {
        val portfolio = AssetPortfolio(
            assets = listOf(travel, emergency),
            valuations = listOf(
                AssetValuation(travel.id, may.plusMonths(-1), 500L),
                AssetValuation(emergency.id, may.plusMonths(-1), 200L),
                AssetValuation(travel.id, may, 600L),
            ),
        )

        val state = buildPurposeAccountsState(may, portfolio)

        assertEquals(600L, state.enteredTotalWon)
        assertNull(state.previousMonthDeltaWon)
        assertEquals(1, state.incompleteCount)
    }

    @Test
    fun overflowingEnteredTotalIsExplicitAndDoesNotWrap() {
        val state = buildPurposeAccountsState(
            may,
            AssetPortfolio(
                listOf(travel, emergency),
                listOf(AssetValuation(travel.id, may, Long.MAX_VALUE), AssetValuation(emergency.id, may, 1L)),
            ),
        )
        assertTrue(state.hasTotalOverflow)
        assertNull(state.enteredTotalWon)
    }

    @Test
    fun overflowingProjectionIsReportedSeparatelyFromMissingInput() {
        val state = buildPurposeAccountsState(
            may.plusMonths(1),
            AssetPortfolio(
                listOf(travel),
                emptyList(),
                listOf(AssetGrowthRule(travel.id, may, 1, Long.MAX_VALUE, 1L)),
            ),
        )

        assertTrue(state.hasTotalOverflow)
        assertTrue(state.rows.single().hasOverflow)
        assertEquals(0, state.incompleteCount)
        assertNull(state.enteredTotalWon)
    }

    @Test
    fun emptyStateDependsOnPurposeAccountsRatherThanOrdinaryAssets() {
        val state = buildPurposeAccountsState(may, AssetPortfolio(listOf(ordinary), emptyList()))
        assertTrue(state.isEmpty)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun monthActionsRecomputeFromObservedPortfolio() = runTest {
        val portfolio = AssetPortfolio(
            listOf(travel),
            listOf(
                AssetValuation(2, may.plusMonths(-1), 100L),
                AssetValuation(2, may, 300L),
            ),
        )
        val holder = PurposeAccountsStateHolder(may.plusMonths(-1), PurposeFakeRepository(portfolio), backgroundScope)
        runCurrent()
        assertEquals(100L, holder.state.value.enteredTotalWon)

        holder.onAction(PurposeAccountsAction.NextMonth)

        assertEquals(may, holder.state.value.month)
        assertEquals(300L, holder.state.value.enteredTotalWon)
        assertEquals(200L, holder.state.value.previousMonthDeltaWon)
        holder.close()
    }
}

private class PurposeFakeRepository(portfolio: AssetPortfolio) : AssetRepository {
    private val data = MutableStateFlow(portfolio)
    override fun observePortfolio(): Flow<AssetPortfolio> = data
    override suspend fun addAsset(asset: AssetItem, month: YearMonthKey, amountWon: Long): Long = 1L
    override suspend fun updateAsset(asset: AssetItem) = Unit
    override suspend fun updateAssetWithValuation(asset: AssetItem, valuation: AssetValuation) = Unit
    override suspend fun saveValuation(valuation: AssetValuation) = Unit
    override suspend fun saveGrowthRule(rule: AssetGrowthRule) = Unit
}
