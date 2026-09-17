package kr.jm.moalog.feature.assets.presentation

import kr.jm.moalog.core.model.*
import kr.jm.moalog.feature.assets.domain.AssetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.*

class AssetsStateTest {
    private val may = YearMonthKey(2026, 5)
    private val assets = listOf(
        AssetItem(1, "현금", AssetType.Cash),
        AssetItem(2, "투자", AssetType.Investment, ownerMemberOrder = 1),
    )

    @Test fun aggregatesEnteredValuationsAndMarksPartialMonthMixed() {
        val state = buildAssetsState(may, AssetPortfolio(assets, listOf(AssetValuation(1, may, 100L))))
        assertEquals(100L, state.totalWon)
        assertEquals(AssetTrendStatus.Mixed, state.trend.last().status)
        assertEquals(1, state.missingValueCount)
        assertNull(state.rows.last().amountWon)
    }

    @Test fun missingPreviousMonthDoesNotBecomeZeroDelta() {
        val state = buildAssetsState(may, AssetPortfolio(assets, listOf(AssetValuation(1, may, 100L), AssetValuation(2, may, 200L))))
        assertEquals(300L, state.totalWon)
        assertNull(state.previousMonthDeltaWon)
        assertEquals(AssetTrendStatus.Missing, state.trend[state.trend.lastIndex - 1].status)
    }

    @Test fun ordersPresentAmountsDescendingAndMissingLast() {
        val state = buildAssetsState(may, AssetPortfolio(assets, listOf(AssetValuation(1, may, 50L), AssetValuation(2, may, 300L))))
        assertEquals(listOf(2L, 1L), state.rows.map { it.asset.id })
    }

    @Test fun emptyPortfolioProducesExactEmptyStateCondition() {
        val state = buildAssetsState(may, AssetPortfolio(emptyList(), emptyList()))
        assertTrue(state.isEmpty)
        assertNull(state.totalWon)
        assertTrue(state.trend.all { it.status == AssetTrendStatus.Missing })
    }

    @Test fun purposeAccountsAreExcludedFromOrdinaryAssetRowsAndTotals() {
        val purposeAccount = AssetItem(3, "여행 통장", AssetType.Deposit, kind = AssetKind.PurposeAccount)
        val state = buildAssetsState(
            may,
            AssetPortfolio(
                assets + purposeAccount,
                listOf(
                    AssetValuation(1, may, 100L),
                    AssetValuation(2, may, 200L),
                    AssetValuation(3, may, 9_000L),
                ),
            ),
        )

        assertEquals(listOf(2L, 1L), state.rows.map { it.asset.id })
        assertEquals(300L, state.totalWon)
    }

    @Test fun overflowingPortfolioTotalIsExplicitInsteadOfWrappingNegative() {
        val state = buildAssetsState(
                may,
                AssetPortfolio(
                    assets,
                    listOf(AssetValuation(1, may, Long.MAX_VALUE), AssetValuation(2, may, 1L)),
                ),
            )
        assertTrue(state.hasTotalOverflow)
        assertNull(state.totalWon)
    }

    @Test fun sortCanSwitchBetweenAmountAndNameAndBoundaryTrendIsSafe() {
        val portfolio = AssetPortfolio(assets, listOf(AssetValuation(1, YearMonthKey(1900, 1), 50L)))
        val state = buildAssetsState(YearMonthKey(1900, 1), portfolio, AssetSort.Name)
        assertEquals(listOf(2L, 1L), state.rows.map { it.asset.id })
        assertEquals(1, state.trend.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun monthSwitchRecomputesRowsTotalAndDelta() = runTest {
        val portfolio = AssetPortfolio(assets, listOf(
            AssetValuation(1, YearMonthKey(2026, 4), 50L), AssetValuation(2, YearMonthKey(2026, 4), 100L),
            AssetValuation(1, may, 100L), AssetValuation(2, may, 200L),
        ))
        val holder = AssetsStateHolder(YearMonthKey(2026, 4), FakeRepository(portfolio), backgroundScope)
        runCurrent()
        assertEquals(150L, holder.state.value.totalWon)
        holder.onAction(AssetsAction.NextMonth)
        assertEquals(may, holder.state.value.month)
        assertEquals(300L, holder.state.value.totalWon)
        assertEquals(150L, holder.state.value.previousMonthDeltaWon)
        holder.close()
    }
}

private class FakeRepository(portfolio: AssetPortfolio) : AssetRepository {
    private val data = MutableStateFlow(portfolio)
    override fun observePortfolio(): Flow<AssetPortfolio> = data
    override suspend fun addAsset(asset: AssetItem, month: YearMonthKey, amountWon: Long): Long = 1L
    override suspend fun updateAsset(asset: AssetItem) = Unit
    override suspend fun updateAssetWithValuation(asset: AssetItem, valuation: AssetValuation) = Unit
    override suspend fun saveValuation(valuation: AssetValuation) = Unit
    override suspend fun saveGrowthRule(rule: AssetGrowthRule) = Unit
}
