package kr.jm.moalog.core.model

/** A recurring fixed cost reviewed for one accounting month. */
data class FixedCostCheckItem(
    val id: Long = 0,
    val ledgerId: Long = 1,
    val attributionMonth: YearMonthKey,
    val payerMemberOrder: Int?,
    val name: String,
    val amountWon: Long?,
    val displayOrder: Int = 0,
) {
    init {
        require(ledgerId > 0)
        require(payerMemberOrder == null || payerMemberOrder >= 0)
        require(name.isNotBlank())
        require(amountWon == null || amountWon >= 0) { "fixed cost amount must not be negative" }
        require(displayOrder >= 0)
    }
}

data class FixedCostCheckSheet(
    val month: YearMonthKey,
    val items: List<FixedCostCheckItem>,
) {
    val knownTotalWonOrNull: Long? get() = items.mapNotNull(FixedCostCheckItem::amountWon).sumWonOrNull()
    val knownTotalWon: Long get() = knownTotalWonOrNull ?: Long.MAX_VALUE
    val hasOverflow: Boolean get() = knownTotalWonOrNull == null
    val hasMissingAmounts: Boolean get() = items.any { it.amountWon == null }
}
