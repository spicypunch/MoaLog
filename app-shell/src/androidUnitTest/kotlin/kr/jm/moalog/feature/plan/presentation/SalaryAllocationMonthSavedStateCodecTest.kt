package kr.jm.moalog.feature.plan.presentation

import kr.jm.moalog.core.model.YearMonthKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SalaryAllocationMonthSavedStateCodecTest {
    @Test
    fun restoredValueKeepsTheSelectedMonth() {
        listOf(YearMonthKey(1900,1),YearMonthKey(2026,5),YearMonthKey(9999,12)).forEach { month ->
            assertEquals(month,SalaryAllocationMonthSavedStateCodec.decode(SalaryAllocationMonthSavedStateCodec.encode(month)))
        }
    }

    @Test
    fun malformedOrUnsupportedValuesAreRejected() {
        listOf("","2026","2026:0","2026:13","1899:12","10000:1","year:5","2026:5:1").forEach {
            assertNull(SalaryAllocationMonthSavedStateCodec.decode(it))
        }
    }
}
