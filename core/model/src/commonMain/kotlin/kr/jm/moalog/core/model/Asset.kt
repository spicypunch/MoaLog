package kr.jm.moalog.core.model

enum class AssetType(val displayName: String) {
    Cash("현금·수시입출금"),
    Deposit("예적금"),
    Investment("투자"),
    Housing("주택·청약"),
    Other("기타"),
}

/** Separates balance-sheet assets from savings accounts reserved for a purpose. */
enum class AssetKind {
    Ordinary,
    PurposeAccount,
}

data class AssetItem(
    val id: Long = 0,
    val name: String,
    val type: AssetType,
    /** null means jointly owned; otherwise this is LedgerMember.order. */
    val ownerMemberOrder: Int? = null,
    val memo: String? = null,
    val kind: AssetKind = AssetKind.Ordinary,
)

data class AssetValuation(
    val assetId: Long,
    val valuationMonth: YearMonthKey,
    val amountWon: Long,
) {
    init { require(amountWon >= 0) }
}

/**
 * A forecast based on the balance at [startMonth].
 * [durationMonths] counts the projected months after that base month.
 * Explicit [AssetValuation] values always take precedence.
 */
data class AssetGrowthRule(
    val assetId: Long,
    val startMonth: YearMonthKey,
    val durationMonths: Int,
    val baseAmountWon: Long,
    val monthlyIncreaseWon: Long,
) {
    init {
        require(durationMonths > 0)
        require(baseAmountWon >= 0)
    }
}

enum class AssetValueStatus { Confirmed, Estimated, Missing }

data class ResolvedAssetValue(
    val amountWon: Long?,
    val status: AssetValueStatus,
    val hasOverflow: Boolean = false,
)

fun AssetPortfolio.resolveValue(assetId: Long, month: YearMonthKey): ResolvedAssetValue {
    valuations.firstOrNull { it.assetId == assetId && it.valuationMonth == month }?.let {
        return ResolvedAssetValue(it.amountWon, AssetValueStatus.Confirmed)
    }
    val rule = growthRules.firstOrNull { it.assetId == assetId }
        ?: return ResolvedAssetValue(null, AssetValueStatus.Missing)
    val offset = monthsBetween(rule.startMonth, month)
    if (offset !in 0..rule.durationMonths) return ResolvedAssetValue(null, AssetValueStatus.Missing)
    val latestConfirmed = valuations
        .asSequence()
        .filter { valuation ->
            valuation.assetId == assetId &&
                monthsBetween(rule.startMonth, valuation.valuationMonth) in 0..rule.durationMonths &&
                monthOrdinal(valuation.valuationMonth) <= monthOrdinal(month)
        }
        .maxByOrNull { monthOrdinal(it.valuationMonth) }
    val baseMonth = latestConfirmed?.valuationMonth ?: rule.startMonth
    val baseAmount = latestConfirmed?.amountWon ?: rule.baseAmountWon
    val steps = monthsBetween(baseMonth, month).toLong()
    val change = multiplyByNonNegativeOrNull(rule.monthlyIncreaseWon, steps)
    val projected = change?.let { addOrNull(baseAmount, it) }
    if (projected == null) {
        return ResolvedAssetValue(null, AssetValueStatus.Estimated, hasOverflow = true)
    }
    return ResolvedAssetValue(projected, AssetValueStatus.Estimated)
}

private fun multiplyByNonNegativeOrNull(value: Long, multiplier: Long): Long? {
    require(multiplier >= 0)
    if (value == 0L || multiplier == 0L) return 0L
    if (value > 0L && value > Long.MAX_VALUE / multiplier) return null
    if (value < 0L && value < Long.MIN_VALUE / multiplier) return null
    return value * multiplier
}

private fun addOrNull(left: Long, right: Long): Long? = when {
    right > 0L && left > Long.MAX_VALUE - right -> null
    right < 0L && left < Long.MIN_VALUE - right -> null
    else -> left + right
}

private fun monthsBetween(from: YearMonthKey, to: YearMonthKey): Int =
    (to.year - from.year) * 12 + to.month - from.month

private fun monthOrdinal(month: YearMonthKey): Long = month.year.toLong() * 12L + month.month

data class AssetPortfolio(
    val assets: List<AssetItem>,
    val valuations: List<AssetValuation>,
    val growthRules: List<AssetGrowthRule> = emptyList(),
)
