package kr.jm.moalog.feature.records.presentation

fun normalizeAmountInput(value: String): String = value.filter(Char::isDigit)

fun formatAmountInput(value: String): String {
    val digits = normalizeAmountInput(value)
    if (digits.isEmpty()) return ""
    return digits.reversed().chunked(3).joinToString(",").reversed()
}
