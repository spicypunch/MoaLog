package kr.jm.moalog.core.contracts

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

private val UUID_PATTERN = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
)
private val UTC_INSTANT_PATTERN = Regex(
    "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z$",
)

/** Canonical lowercase UUID used across the HTTP boundary. */
@Serializable
@JvmInline
value class UuidString(val value: String) {
    init {
        require(UUID_PATTERN.matches(value)) {
            "UUID must use the canonical lowercase 8-4-4-4-12 format"
        }
    }

    override fun toString(): String = value
}

/** Calendar month encoded as YYYY-MM on the wire. */
@Serializable
@JvmInline
value class YearMonthString(val value: String) {
    init {
        require(isValidYearMonth(value)) { "Year-month must use YYYY-MM with a month from 01 to 12" }
    }

    override fun toString(): String = value
}

private fun isValidYearMonth(value: String): Boolean {
    if (value.length != 7 || value[4] != '-') return false
    val year = value.substring(0, 4).toIntOrNull() ?: return false
    val month = value.substring(5, 7).toIntOrNull() ?: return false
    return year in 1..9999 && month in 1..12
}

/** Korean won is always represented as an integer. Nullable DTO fields distinguish missing from zero. */
typealias WonAmount = Long

/** UTC timestamp encoded using the RFC 3339 date-time form ending in Z. */
@Serializable
@JvmInline
value class UtcInstantString(val value: String) {
    init {
        require(isValidUtcInstant(value)) {
            "UTC instant must use an RFC 3339 date-time ending in Z"
        }
    }

    override fun toString(): String = value
}

private fun isValidUtcInstant(value: String): Boolean {
    if (!UTC_INSTANT_PATTERN.matches(value)) return false
    val year = value.substring(0, 4).toInt()
    val month = value.substring(5, 7).toInt()
    val day = value.substring(8, 10).toInt()
    val hour = value.substring(11, 13).toInt()
    val minute = value.substring(14, 16).toInt()
    val second = value.substring(17, 19).toInt()
    if (year !in 1..9999 || month !in 1..12 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
        return false
    }
    val leapYear = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val maxDay = when (month) {
        2 -> if (leapYear) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    return day in 1..maxDay
}
