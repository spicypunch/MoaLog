package kr.jm.moalog.core.model

enum class SalaryAllocationMethod { FixedAmount, SalaryRatio, ChildTotal, RemainingFromSource }

data class SalaryIncome(
    val attributionMonth: YearMonthKey,
    val memberOrder: Int,
    val amountWon: Long?,
) {
    init {
        require(memberOrder >= 0)
        require(amountWon == null || amountWon >= 0)
    }
}

data class SalaryAllocationCategory(
    val id: Long = 0,
    val ledgerId: Long = 1,
    val attributionMonth: YearMonthKey,
    val name: String,
    val sourceMemberOrder: Int? = null,
    val method: SalaryAllocationMethod,
    val amountWon: Long? = null,
    val rateBasisPoints: Int? = null,
    val memo: String? = null,
    val displayOrder: Int = 0,
    val children: List<SalaryAllocationChild> = emptyList(),
    /** null means a pre-v11 row, which keeps the legacy "deduct every peer" behavior. */
    val deductedCategoryIds: Set<Long>? = null,
) {
    init {
        require(ledgerId > 0)
        require(name.isNotBlank())
        require(sourceMemberOrder == null || sourceMemberOrder >= 0)
        require(amountWon == null || amountWon >= 0)
        require(rateBasisPoints == null || rateBasisPoints in 0..10_000)
        require(deductedCategoryIds?.all { it > 0L } != false)
        when (method) {
            SalaryAllocationMethod.FixedAmount -> require(rateBasisPoints == null)
            SalaryAllocationMethod.SalaryRatio -> require(amountWon == null)
            SalaryAllocationMethod.ChildTotal, SalaryAllocationMethod.RemainingFromSource -> require(amountWon == null && rateBasisPoints == null)
        }
    }
}

data class SalaryAllocationChild(
    val id: Long = 0,
    val categoryId: Long,
    val attributionMonth: YearMonthKey,
    val name: String,
    val amountWon: Long?,
    val memo: String? = null,
    val displayOrder: Int = 0,
    val grandchildren: List<SalaryAllocationGrandchild> = emptyList(),
) {
    init {
        require(categoryId > 0)
        require(name.isNotBlank())
        require(amountWon == null || amountWon >= 0)
    }
}

data class SalaryAllocationGrandchild(
    val id: Long = 0,
    val childId: Long,
    val attributionMonth: YearMonthKey,
    val name: String,
    val amountWon: Long?,
    val memo: String? = null,
    val displayOrder: Int = 0,
) {
    init {
        require(childId > 0)
        require(name.isNotBlank())
        require(amountWon == null || amountWon >= 0)
    }
}

data class SalaryAllocationSheet(
    val month: YearMonthKey,
    val incomes: List<SalaryIncome>,
    val categories: List<SalaryAllocationCategory>,
)

fun calculateRatioAmount(baseWon: Long, rateBasisPoints: Int): Long {
    require(baseWon >= 0)
    require(rateBasisPoints in 0..10_000)
    val whole = (baseWon / 10_000L) * rateBasisPoints
    val remainder = baseWon % 10_000L
    return whole + ((remainder * rateBasisPoints + 5_000L) / 10_000L)
}

fun SalaryAllocationCategory.targetAmount(incomes: List<SalaryIncome>): Long? = when (method) {
    SalaryAllocationMethod.FixedAmount -> amountWon
    SalaryAllocationMethod.SalaryRatio -> {
        val rate = rateBasisPoints ?: return null
        val base = if (sourceMemberOrder == null) {
            if (incomes.size != 2 || incomes.any { it.amountWon == null }) null else incomes.mapNotNull { it.amountWon }.sumWonOrNull()
        } else incomes.firstOrNull { it.memberOrder == sourceMemberOrder }?.amountWon
        base?.let { calculateRatioAmount(it, rate) }
    }
    SalaryAllocationMethod.ChildTotal -> childTotal()
    SalaryAllocationMethod.RemainingFromSource -> null
}

fun SalaryAllocationChild.subtotal(): Long? = if (grandchildren.isEmpty()) amountWon else {
    if (grandchildren.any { it.amountWon == null }) null else grandchildren.mapNotNull { it.amountWon }.sumWonOrNull()
}

fun SalaryAllocationChild.hasSubtotalOverflow(): Boolean =
    grandchildren.isNotEmpty() && grandchildren.all { it.amountWon != null } && subtotal() == null

fun SalaryAllocationCategory.childTotal(): Long? =
    if (children.isEmpty()) null else children.map { it.subtotal() }.takeIf { values -> values.none { it == null } }?.mapNotNull { it }?.sumWonOrNull()

fun SalaryAllocationCategory.hasChildTotalOverflow(): Boolean =
    children.isNotEmpty() &&
        children.all { child ->
            if (child.grandchildren.isEmpty()) child.amountWon != null
            else child.grandchildren.all { it.amountWon != null }
        } &&
        childTotal() == null

fun SalaryAllocationSheet.categoryTargets(): Map<Long, Long?> {
    val direct = categories.associate { it.id to it.targetAmount(incomes) }.toMutableMap()
    categories.filter { it.method == SalaryAllocationMethod.RemainingFromSource }.forEach { remaining ->
        val sameSourceRemainders = categories.count { it.method == SalaryAllocationMethod.RemainingFromSource && it.sourceMemberOrder == remaining.sourceMemberOrder }
        val base = if (remaining.sourceMemberOrder == null) {
            if (incomes.size != 2 || incomes.any { it.amountWon == null }) null else incomes.mapNotNull { it.amountWon }.sumWonOrNull()
        } else incomes.firstOrNull { it.memberOrder == remaining.sourceMemberOrder }?.amountWon
        val eligiblePeers = categories.filter { it.id != remaining.id && it.sourceMemberOrder == remaining.sourceMemberOrder && it.method != SalaryAllocationMethod.RemainingFromSource }
        val peers = remaining.deductedCategoryIds?.let { ids -> eligiblePeers.filter { it.id in ids } } ?: eligiblePeers
        val peerTotal = peers.mapNotNull { direct[it.id] }.takeIf { it.size == peers.size }?.sumWonOrNull()
        direct[remaining.id] = if (sameSourceRemainders != 1 || base == null || peerTotal == null) null
        else if (peerTotal >= base) 0L else base - peerTotal
    }
    return direct
}

/** Adds non-negative won values without allowing a wrapped negative result. */
fun Iterable<Long>.sumWonOrNull(): Long? {
    var total = 0L
    for (value in this) {
        require(value >= 0) { "won value must not be negative" }
        if (Long.MAX_VALUE - total < value) return null
        total += value
    }
    return total
}
