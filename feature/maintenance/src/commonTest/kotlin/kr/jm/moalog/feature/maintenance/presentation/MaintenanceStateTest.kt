package kr.jm.moalog.feature.maintenance.presentation

import kr.jm.moalog.core.model.MaintenanceFeeItemKey
import kr.jm.moalog.core.model.MaintenanceFeeMonth
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.maintenance.domain.MaintenanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaintenanceStateTest {
    @Test
    fun overviewExposesOrderedRowsAndSixCrossYearComparisonPoints() = runTest {
        val selected = YearMonthKey(2026, 2)
        val repository = FakeRepository().apply {
            months[selected] = fee(selected, 100_000, -2_000)
        }
        val holder = MaintenanceOverviewStateHolder(MaintenanceOverviewArgs(selected), repository, this)
        advanceUntilIdle()

        val state = holder.state.value
        assertFalse(state.isLoading)
        assertEquals(98_000L, state.totalWon)
        assertEquals(MaintenanceFeeItemKey.entries, state.rows.map { it.key })
        assertEquals(YearMonthKey(2025, 9), state.comparisonPoints.first().month)
        assertEquals(YearMonthKey(2026, 2), state.comparisonPoints.last().month)
        assertEquals(6, state.comparisonPoints.size)
        holder.close()
    }

    @Test
    fun overviewDistinguishesMissingMonthFromEnteredZeroAndMovesAcrossYear() = runTest {
        val december = YearMonthKey(2025, 12)
        val january = YearMonthKey(2026, 1)
        val repository = FakeRepository().apply {
            months[january] = MaintenanceFeeMonth.fromAmounts(
                january,
                mapOf(MaintenanceFeeItemKey.Cleaning to 0L),
            )
        }
        val holder = MaintenanceOverviewStateHolder(MaintenanceOverviewArgs(december), repository, this)
        advanceUntilIdle()
        assertNull(holder.state.value.totalWon)

        holder.onAction(MaintenanceOverviewAction.NextMonth)
        advanceUntilIdle()
        assertEquals(january, holder.state.value.month)
        assertEquals(0L, holder.state.value.totalWon)
        holder.close()
    }

    @Test
    fun editorValidatesSignsCalculatesTotalAndSavesAllFieldsIncludingNulls() = runTest {
        val month = YearMonthKey(2026, 5)
        val repository = FakeRepository()
        val holder = MaintenanceEditorStateHolder(MaintenanceEditorArgs(month), repository, this)
        advanceUntilIdle()

        holder.onAction(MaintenanceEditorAction.AmountChanged(MaintenanceFeeItemKey.Cleaning, "-1"))
        assertNotNull(holder.state.value.fields.single { it.key == MaintenanceFeeItemKey.Cleaning }.error)
        assertNull(holder.state.value.calculatedTotalWon)

        holder.onAction(MaintenanceEditorAction.AmountChanged(MaintenanceFeeItemKey.Cleaning, "100,000"))
        holder.onAction(MaintenanceEditorAction.AmountChanged(MaintenanceFeeItemKey.Deduction, "-4,020"))
        assertEquals(95_980L, holder.state.value.calculatedTotalWon)
        assertEquals(2, holder.state.value.enteredCount)

        holder.onAction(MaintenanceEditorAction.Save)
        advanceUntilIdle()
        assertTrue(holder.state.value.completed)
        val saved = requireNotNull(repository.saved)
        assertEquals(21, saved.entries.size)
        assertEquals(100_000L, saved.amountOf(MaintenanceFeeItemKey.Cleaning))
        assertEquals(-4_020L, saved.amountOf(MaintenanceFeeItemKey.Deduction))
        assertNull(saved.amountOf(MaintenanceFeeItemKey.Disinfection))
        holder.close()
    }

    private fun fee(month: YearMonthKey, general: Long, deduction: Long) =
        MaintenanceFeeMonth.fromAmounts(month, mapOf(
            MaintenanceFeeItemKey.GeneralManagement to general,
            MaintenanceFeeItemKey.Deduction to deduction,
        ))

    private class FakeRepository : MaintenanceRepository {
        val months = mutableMapOf<YearMonthKey, MaintenanceFeeMonth>()
        var saved: MaintenanceFeeMonth? = null

        override fun observeMonth(month: YearMonthKey): Flow<MaintenanceFeeMonth> =
            flowOf(months[month] ?: MaintenanceFeeMonth.empty(month))

        override fun observeRange(startInclusive: YearMonthKey, endInclusive: YearMonthKey): Flow<List<MaintenanceFeeMonth>> =
            flowOf(buildList {
                var month = startInclusive
                while (true) {
                    add(months[month] ?: MaintenanceFeeMonth.empty(month))
                    if (month == endInclusive) break
                    month = month.plusMonths(1)
                }
            })

        override suspend fun saveMonth(month: MaintenanceFeeMonth) {
            saved = month
            months[month.billMonth] = month
        }
    }
}
