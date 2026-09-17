package kr.jm.moalog.feature.plan.domain

import kr.jm.moalog.core.model.YearMonthKey

fun interface PlanCurrentMonthProvider { fun currentMonth(): YearMonthKey }
expect fun platformPlanCurrentMonthProvider(): PlanCurrentMonthProvider
