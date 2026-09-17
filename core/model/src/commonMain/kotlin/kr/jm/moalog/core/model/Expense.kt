package kr.jm.moalog.core.model

data class ExpenseCategory(
    val id: String,
    val name: String,
    val displayOrder: Int,
    val archived: Boolean = false,
    val ledgerId: Long = 1,
) {
    init {
        require(id.isNotBlank())
        require(name.isNotBlank())
        require(displayOrder >= 0)
        require(ledgerId > 0)
    }
}

data class ExpenseRecord(
    val id: Long = 0,
    val ledgerId: Long = 1,
    val categoryId: String,
    val categoryName: String,
    val attributionMonth: YearMonthKey,
    val actualDate: LocalDateKey?,
    val detail: String?,
    val amountWon: Long,
    val overspent: Boolean,
) {
    init {
        require(ledgerId > 0)
        require(categoryId.isNotBlank())
        require(amountWon >= 0) { "expense amount must not be negative" }
    }
}

data class LocalDateKey(val year: Int, val month: Int, val day: Int) : Comparable<LocalDateKey> {
    init {
        require(year in YearMonthKey.MIN_YEAR..YearMonthKey.MAX_YEAR)
        require(month in 1..12)
        require(day in 1..daysInMonth(year, month))
    }

    val yearMonth: YearMonthKey get() = YearMonthKey(year, month)

    override fun compareTo(other: LocalDateKey): Int =
        compareValuesBy(this, other, LocalDateKey::year, LocalDateKey::month, LocalDateKey::day)

    override fun toString(): String =
        "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 400 == 0 || year % 4 == 0 && year % 100 != 0) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}
