package kr.jm.moalog.feature.plan.domain

import java.util.Calendar
import kr.jm.moalog.core.model.YearMonthKey

actual fun platformPlanCurrentMonthProvider() = PlanCurrentMonthProvider {
    Calendar.getInstance().let { YearMonthKey(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1) }
}
