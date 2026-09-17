package kr.jm.moalog.feature.plan.presentation

import kr.jm.moalog.core.model.YearMonthKey

fun previousPlanEditorMonth(month: YearMonthKey): YearMonthKey? =
    month.takeUnless { it.year == YearMonthKey.MIN_YEAR && it.month == 1 }?.plusMonths(-1)

fun nextPlanEditorMonth(month: YearMonthKey): YearMonthKey? =
    month.takeUnless { it.year == 9999 && it.month == 12 }?.plusMonths(1)
