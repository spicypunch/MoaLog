package kr.jm.moalog.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MaintenanceFeeTest {
    private val month = YearMonthKey(2026, 1)

    @Test
    fun schemaHasExactCanonicalOrder() {
        assertEquals(21, MaintenanceFeeItemKey.entries.size)
        assertEquals("일반관리비", MaintenanceFeeItemKey.entries.first().label)
        assertEquals("생활폐기물수수료", MaintenanceFeeItemKey.entries.last().label)
    }

    @Test
    fun allMissingHasNullTotalWhileEnteredZeroIsZero() {
        assertNull(MaintenanceFeeMonth.empty(month).totalWon)
        assertEquals(0L, MaintenanceFeeMonth.fromAmounts(month, mapOf(MaintenanceFeeItemKey.Cleaning to 0L)).totalWon)
    }

    @Test
    fun deductionRetainsNegativeSign() {
        val fee = MaintenanceFeeMonth.fromAmounts(month, mapOf(
            MaintenanceFeeItemKey.GeneralManagement to 100_000L,
            MaintenanceFeeItemKey.Deduction to -4_020L,
        ))
        assertEquals(95_980L, fee.totalWon)
    }

    @Test
    fun negativeNonDeductionAndOverflowAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            MaintenanceFeeMonth.fromAmounts(month, mapOf(MaintenanceFeeItemKey.Cleaning to -1L))
        }
        assertFailsWith<IllegalArgumentException> {
            MaintenanceFeeMonth.fromAmounts(month, mapOf(
                MaintenanceFeeItemKey.GeneralManagement to Long.MAX_VALUE,
                MaintenanceFeeItemKey.Cleaning to 1L,
            )).totalWon
        }
    }
}
