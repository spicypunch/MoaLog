package kr.jm.moalog.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class YearMonthKeyTest {
    @Test
    fun localDateSupportsTheSameMinimumYearAsYearMonth() {
        assertEquals(LocalDateKey(YearMonthKey.MIN_YEAR, 1, 1), LocalDateKey(1900, 1, 1))
        assertFailsWith<IllegalArgumentException> { LocalDateKey(1899, 12, 31) }
    }
    @Test
    fun formatsAccountingMonthWithLeadingZero() {
        assertEquals("2026-05", YearMonthKey(2026, 5).toString())
    }

    @Test
    fun rejectsMonthOutsideCalendarRange() {
        assertFailsWith<IllegalArgumentException> { YearMonthKey(2026, 13) }
    }

    @Test
    fun monthNavigationStopsAtSupportedBoundaries() {
        assertNull(YearMonthKey(1900, 1).plusMonthsOrNull(-1))
        assertEquals(YearMonthKey(1900, 2), YearMonthKey(1900, 1).plusMonthsOrNull(1))
        assertEquals(YearMonthKey(2027, 1), YearMonthKey(2026, 12).plusMonthsOrNull(1))
        assertEquals(YearMonthKey(2026, 12), YearMonthKey(2027, 1).plusMonthsOrNull(-1))
        assertNull(YearMonthKey(9999, 12).plusMonthsOrNull(1))
    }

    @Test
    fun acceptsTheFullProductYearRange() {
        assertEquals(1900, YearMonthKey(1900, 1).year)
        assertEquals(9999, YearMonthKey(9999, 12).year)
        assertFailsWith<IllegalArgumentException> { YearMonthKey(1899, 12) }
    }
}
