package kr.jm.moalog.core.model

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssetTest {
    @Test fun valuationRejectsNegativeWon() {
        assertFailsWith<IllegalArgumentException> { AssetValuation(1, YearMonthKey(2026, 5), -1) }
    }

    @Test fun assetDefaultsToOrdinaryAndCanBeMarkedAsPurposeAccount() {
        assertEquals(AssetKind.Ordinary, AssetItem(name = "현금", type = AssetType.Cash).kind)
        assertEquals(
            AssetKind.PurposeAccount,
            AssetItem(name = "여행 통장", type = AssetType.Deposit, kind = AssetKind.PurposeAccount).kind,
        )
    }
}

class AssetProjectionTest {
    private val may = YearMonthKey(2026, 5)
    private val asset = AssetItem(1, "적금", AssetType.Deposit)
    private val rule = AssetGrowthRule(1, may, 2, 1_000L, 100L)

    @Test fun projectionStartsWithOneIncreaseAndStopsAtDurationBoundary() {
        val portfolio = AssetPortfolio(listOf(asset), emptyList(), listOf(rule))
        assertEquals(1_000L, portfolio.resolveValue(1, may).amountWon)
        assertEquals(1_100L, portfolio.resolveValue(1, may.plusMonths(1)).amountWon)
        assertEquals(1_200L, portfolio.resolveValue(1, may.plusMonths(2)).amountWon)
        assertEquals(AssetValueStatus.Missing, portfolio.resolveValue(1, may.plusMonths(3)).status)
    }

    @Test fun explicitValuationOverridesProjection() {
        val portfolio = AssetPortfolio(listOf(asset), listOf(AssetValuation(1, may.plusMonths(1), 777L)), listOf(rule))
        assertEquals(ResolvedAssetValue(777L, AssetValueStatus.Confirmed), portfolio.resolveValue(1, may.plusMonths(1)))
    }

    @Test fun overflowingProjectionIsExposedWithoutWrapping() {
        val portfolio = AssetPortfolio(listOf(asset), emptyList(), listOf(AssetGrowthRule(1, may, 2, Long.MAX_VALUE, 1)))
        val value = portfolio.resolveValue(1, may.plusMonths(1))
        assertNull(value.amountWon)
        assertEquals(AssetValueStatus.Estimated, value.status)
        assertTrue(value.hasOverflow)
    }

    @Test fun signedMonthlyChangeProjectsDownwardAndChecksNegativeOverflow() {
        val downward = AssetPortfolio(
            listOf(asset),
            emptyList(),
            listOf(AssetGrowthRule(1, may, 2, 1_000L, -100L)),
        )
        assertEquals(900L, downward.resolveValue(1, may.plusMonths(1)).amountWon)
        assertEquals(800L, downward.resolveValue(1, may.plusMonths(2)).amountWon)

        val overflow = AssetPortfolio(
            listOf(asset),
            emptyList(),
            listOf(AssetGrowthRule(1, may, 2, 0L, Long.MIN_VALUE)),
        ).resolveValue(1, may.plusMonths(2))
        assertNull(overflow.amountWon)
        assertTrue(overflow.hasOverflow)
    }

    @Test fun latestConfirmedValuationRebasesRemainingProjectionWithinOriginalDuration() {
        val portfolio = AssetPortfolio(
            listOf(asset),
            listOf(
                AssetValuation(1, may.plusMonths(1), 1_500L),
                AssetValuation(1, may.plusMonths(2), 2_000L),
            ),
            listOf(AssetGrowthRule(1, may, 4, 1_000L, 100L)),
        )

        assertEquals(1_500L, portfolio.resolveValue(1, may.plusMonths(1)).amountWon)
        assertEquals(2_000L, portfolio.resolveValue(1, may.plusMonths(2)).amountWon)
        assertEquals(2_100L, portfolio.resolveValue(1, may.plusMonths(3)).amountWon)
        assertEquals(2_200L, portfolio.resolveValue(1, may.plusMonths(4)).amountWon)
        assertEquals(AssetValueStatus.Missing, portfolio.resolveValue(1, may.plusMonths(5)).status)
    }
}
