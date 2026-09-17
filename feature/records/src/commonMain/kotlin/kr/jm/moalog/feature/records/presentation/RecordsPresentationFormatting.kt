package kr.jm.moalog.feature.records.presentation

import kr.jm.moalog.core.model.LocalDateKey
import kr.jm.moalog.core.model.ExpenseCategory
import kr.jm.moalog.core.model.ExpenseRecord

fun filterEmptyDescription(state: RecordsUiState): String {
    val labels = buildList {
        addAll(state.categories.filter { it.id in state.filters.categoryIds }.map(ExpenseCategory::name))
        if (state.filters.overspentOnly) add("과소비")
    }
    val selected = labels.joinToString("', '", prefix = "'", postfix = "'")
    return if (labels.isEmpty()) {
        "선택한 조건에 해당하는 기록이 이번 달에는 없네요."
    } else {
        "설정하신 $selected 필터에 해당하는 기록이 이번 달에는 없네요."
    }
}

fun recordMonthMetadata(record: ExpenseRecord): String? {
    val actualDate = record.actualDate
    return when {
        actualDate == null -> "실제 발생일 미입력"
        actualDate.yearMonth != record.attributionMonth ->
            "${actualDate.year}년 ${actualDate.month}월 ${actualDate.day}일 발생 · " +
                "${record.attributionMonth.year}년 ${record.attributionMonth.month}월 귀속"
        else -> null
    }
}

fun koreanWeekday(date: LocalDateKey): String {
    var year = date.year
    var month = date.month
    if (month < 3) {
        month += 12
        year -= 1
    }
    val dayIndex =
        (date.day + (13 * (month + 1)) / 5 + year + year / 4 - year / 100 + year / 400) % 7
    return listOf("토요일", "일요일", "월요일", "화요일", "수요일", "목요일", "금요일")[dayIndex]
}
