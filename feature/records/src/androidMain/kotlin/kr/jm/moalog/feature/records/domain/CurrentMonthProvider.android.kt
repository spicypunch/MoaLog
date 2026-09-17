package kr.jm.moalog.feature.records.domain

import java.util.Calendar
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.core.model.LocalDateKey

actual fun platformCurrentMonthProvider(): CurrentMonthProvider = CurrentMonthProvider {
    Calendar.getInstance().let { YearMonthKey(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1) }
}

actual fun platformCurrentDateProvider(): CurrentDateProvider = CurrentDateProvider {
    Calendar.getInstance().let {
        LocalDateKey(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH))
    }
}
