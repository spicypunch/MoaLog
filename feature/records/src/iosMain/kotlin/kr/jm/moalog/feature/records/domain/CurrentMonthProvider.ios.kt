package kr.jm.moalog.feature.records.domain

import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.core.model.LocalDateKey
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate

actual fun platformCurrentMonthProvider(): CurrentMonthProvider = CurrentMonthProvider {
    val components = NSCalendar.currentCalendar.components(
        NSCalendarUnitYear or NSCalendarUnitMonth,
        fromDate = NSDate(),
    )
    YearMonthKey(components.year.toInt(), components.month.toInt())
}


actual fun platformCurrentDateProvider(): CurrentDateProvider = CurrentDateProvider {
    val components = NSCalendar.currentCalendar.components(
        NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay,
        fromDate = NSDate(),
    )
    LocalDateKey(components.year.toInt(), components.month.toInt(), components.day.toInt())
}
