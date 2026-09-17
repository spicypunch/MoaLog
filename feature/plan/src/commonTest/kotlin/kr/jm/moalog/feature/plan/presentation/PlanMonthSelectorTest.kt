package kr.jm.moalog.feature.plan.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kr.jm.moalog.core.model.YearMonthKey

class PlanMonthSelectorTest {
    @Test
    fun monthButtonsStopAtSupportedYearBoundaries() {
        assertNull(previousPlanEditorMonth(YearMonthKey(YearMonthKey.MIN_YEAR, 1)))
        assertNull(nextPlanEditorMonth(YearMonthKey(9999, 12)))
    }

    @Test
    fun monthButtonsCrossYearInBothDirections() {
        assertEquals(YearMonthKey(2025, 12), previousPlanEditorMonth(YearMonthKey(2026, 1)))
        assertEquals(YearMonthKey(2027, 1), nextPlanEditorMonth(YearMonthKey(2026, 12)))
    }
}
