package kr.jm.moalog.core.model

enum class PlanItemType { Income, FixedExpense, Savings }
enum class PlanItemStatus { Estimated, Confirmed }

data class PlanCatalogItem(
    val id: Long = 0,
    val ledgerId: Long = 1,
    val type: PlanItemType,
    val classification: String,
    val name: String,
    val ownerMemberOrder: Int?,
    val includePurposeAccount: Boolean = false,
    val includeNetSavings: Boolean = false,
    val displayOrder: Int = 0,
    val archived: Boolean = false,
) {
    init {
        require(ledgerId > 0)
        require(id >= 0)
        require(classification.isNotBlank())
        require(name.isNotBlank())
        require(ownerMemberOrder == null || ownerMemberOrder >= 0)
        require(displayOrder >= 0)
        if (type != PlanItemType.Savings) {
            require(!includePurposeAccount && !includeNetSavings)
        }
    }
}

val DefaultSavingsClassifications: List<String> =
    listOf("예적금", "청약", "주식", "부동산", "대출원금", "퇴직금")

data class MonthlyPlanItem(
    val id: Long = 0,
    val ledgerId: Long = 1,
    val type: PlanItemType,
    val attributionMonth: YearMonthKey,
    val name: String,
    val amountWon: Long?,
    val category: String,
    val status: PlanItemStatus,
    val ownerMemberOrder: Int?,
    val memo: String?,
    val includePurposeAccount: Boolean = false,
    val includeNetSavings: Boolean = false,
    val catalogId: Long = 0,
) {
    init {
        require(ledgerId > 0)
        require(catalogId >= 0)
        require(name.isNotBlank())
        require(category.isNotBlank())
        require(ownerMemberOrder == null || ownerMemberOrder >= 0)
        if (type != PlanItemType.Savings) {
            require(amountWon == null || amountWon >= 0) { "income and fixed-expense amounts must not be negative" }
            require(!includePurposeAccount && !includeNetSavings) { "savings flags are only valid for savings items" }
        }
    }
}
