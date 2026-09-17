package kr.jm.moalog.feature.records.domain

import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.core.model.LocalDateKey

fun interface CurrentMonthProvider {
    fun currentMonth(): YearMonthKey
}

expect fun platformCurrentMonthProvider(): CurrentMonthProvider

fun interface CurrentDateProvider {
    fun currentDate(): LocalDateKey
}

expect fun platformCurrentDateProvider(): CurrentDateProvider
