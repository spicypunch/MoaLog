package kr.jm.moalog.core.model

data class LedgerSetup(
    val ledgerName: String,
    val members: List<LedgerMember>,
    val baseYear: Int,
    val annualSavingsTargetWon: Long?,
    val annualSavingsTargetsWon: Map<Int, Long> = annualSavingsTargetWon
        ?.let { mapOf(baseYear to it) }
        .orEmpty(),
) {
    init {
        require(members.size == 2) { "A couple ledger must have exactly two display members" }
        require(annualSavingsTargetsWon.keys.all { it in 1900..9999 }) { "Savings target year is out of range" }
        require(annualSavingsTargetsWon.values.all { it >= 0L }) { "Savings target must be zero or greater" }
    }

    fun savingsTargetFor(year: Int): Long? = annualSavingsTargetsWon[year]
        ?: annualSavingsTargetWon.takeIf { year == baseYear }
}

data class LedgerMember(
    val displayName: String,
    val order: Int,
)
