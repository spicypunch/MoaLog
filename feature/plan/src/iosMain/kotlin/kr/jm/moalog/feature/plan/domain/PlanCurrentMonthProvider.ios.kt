package kr.jm.moalog.feature.plan.domain

import kr.jm.moalog.core.model.YearMonthKey
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate

actual fun platformPlanCurrentMonthProvider() = PlanCurrentMonthProvider {
    val components = NSCalendar.currentCalendar.components(NSCalendarUnitYear or NSCalendarUnitMonth, NSDate())
    YearMonthKey(components.year.toInt(), components.month.toInt())
}
