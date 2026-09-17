package kr.jm.moalog.core.model

/** A platform-neutral accounting month used in API, storage, and domain boundaries. */
data class YearMonthKey(
    val year: Int,
    val month: Int,
) {
    init {
        require(year in MIN_YEAR..MAX_YEAR) { "year must be between $MIN_YEAR and $MAX_YEAR" }
        require(month in 1..12) { "month must be between 1 and 12" }
    }

    override fun toString(): String = "$year-${month.toString().padStart(2, '0')}"

    fun plusMonths(delta: Int): YearMonthKey {
        return requireNotNull(plusMonthsOrNull(delta)) { "month is outside the supported range" }
    }

    fun plusMonthsOrNull(delta: Int): YearMonthKey? {
        val absolute = year.toLong() * 12L + (month - 1) + delta.toLong()
        val minimum = MIN_YEAR.toLong() * 12L
        val maximum = MAX_YEAR.toLong() * 12L + 11L
        if (absolute !in minimum..maximum) return null
        return YearMonthKey((absolute / 12L).toInt(), (absolute % 12L).toInt() + 1)
    }

    companion object {
        const val MIN_YEAR: Int = 1900
        const val MAX_YEAR: Int = 9999
    }
}
