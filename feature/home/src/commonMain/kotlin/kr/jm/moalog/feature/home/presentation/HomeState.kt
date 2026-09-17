package kr.jm.moalog.feature.home.presentation

import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey

data class HomeMonthlySummary(
    val year: Int,
    val month: Int,
    val expenseTotalWon: Long = 0,
    val overspentTotalWon: Long = 0,
    val overspentCount: Int = 0,
    val isLoading: Boolean = false,
    val loadError: String? = null,
)

data class HomeAnnualComposition(
    val fixedExpenseWon: Long = 0,
    val variableExpenseWon: Long = 0,
    val savingsWon: Long = 0,
    val fixedExpensePercent: Double? = null,
    val variableExpensePercent: Double? = null,
    val savingsPercent: Double? = null,
    val isComplete: Boolean = false,
)

/**
 * Annual figures use known amounts even while a plan is partial. Consumers must
 * check the corresponding completeness flag before presenting a known amount as
 * the final total. Rates are null whenever one of their inputs is incomplete.
 */
data class HomeAnnualSummary(
    val year: Int,
    val knownIncomeWon: Long = 0,
    val knownFixedExpenseWon: Long = 0,
    val variableExpenseWon: Long = 0,
    val knownTotalExpenseWon: Long = 0,
    val knownSavingsWon: Long = 0,
    val knownPurposeAccountSavingsWon: Long = 0,
    val knownNetSavingsWon: Long = 0,
    val incomeIsComplete: Boolean = false,
    val fixedExpenseIsComplete: Boolean = false,
    val savingsIsComplete: Boolean = false,
    val savingsRate: Double? = null,
    val netSavingsRate: Double? = null,
    val goalProgressRate: Double? = null,
    val goalRemainingWon: Long? = null,
    val memberContributionLabel: String? = null,
    val composition: HomeAnnualComposition = HomeAnnualComposition(),
    val isPartial: Boolean = true,
    val isLoading: Boolean = false,
    val loadError: String? = null,
)

/** A feature-neutral annual input. Shared adapters map repository snapshots into this type. */
data class HomeAnnualMonthSnapshot(
    val month: YearMonthKey,
    val items: List<MonthlyPlanItem>,
    val variableExpenses: List<ExpenseRecord>,
)

data class HomeUiState(
    val ledgerName: String,
    val memberNames: String,
    val baseYear: Int,
    val selectedYear: Int = baseYear,
    val annualSavingsTargetWon: Long?,
    val monthlySummary: HomeMonthlySummary,
    val selectedMonth: YearMonthKey = YearMonthKey(selectedYear, monthlySummary.month),
    val annualSummary: HomeAnnualSummary = HomeAnnualSummary(year = selectedYear),
    val isLoading: Boolean = false,
    val loadError: String? = null,
    val isGoalEditorVisible: Boolean = false,
    val goalInput: String = "",
    val goalInputError: String? = null,
    val isSavingGoal: Boolean = false,
    val goalSaveError: String? = null,
)

fun LedgerSetup.toHomeUiState(
    monthlySummary: HomeMonthlySummary,
    selectedYear: Int = baseYear,
    annualSummary: HomeAnnualSummary = HomeAnnualSummary(year = selectedYear),
): HomeUiState = HomeUiState(
    ledgerName = ledgerName,
    memberNames = members.sortedBy { it.order }.joinToString(" · ") { it.displayName },
    baseYear = baseYear,
    selectedYear = selectedYear,
    selectedMonth = YearMonthKey(selectedYear, monthlySummary.month),
    annualSavingsTargetWon = savingsTargetFor(selectedYear),
    monthlySummary = monthlySummary,
    annualSummary = annualSummary,
)

fun calculateHomeAnnualSummary(
    setup: LedgerSetup,
    year: Int,
    months: List<HomeAnnualMonthSnapshot>,
    isLoading: Boolean = false,
    loadError: String? = null,
): HomeAnnualSummary {
    val yearMonths = months.filter { it.month.year == year }
    val monthsByNumber = yearMonths.associateBy { it.month.month }
    val hasAllCalendarMonths = monthsByNumber.keys == (1..12).toSet()
    val yearItems = yearMonths.flatMap { it.items }.filter { it.attributionMonth.year == year }
    val savingsItems = yearItems.filter { it.type == PlanItemType.Savings }
    val netSavingsItems = savingsItems.filter { it.includeNetSavings }

    val income = yearMonths.annualAmount(PlanItemType.Income, hasAllCalendarMonths)
    val fixed = yearMonths.annualAmount(PlanItemType.FixedExpense, hasAllCalendarMonths)
    val savings = yearMonths.annualAmount(PlanItemType.Savings, hasAllCalendarMonths)
    val netSavingsWon = netSavingsItems.sumKnownAmount()
    val variableExpenseWon = yearMonths
        .asSequence()
        .flatMap { it.variableExpenses.asSequence() }
        .filter { it.attributionMonth.year == year }
        .map { it.amountWon }
        .toList()
        .checkedMoneySum()

    // A null saving amount makes both savings rates unknown. This avoids
    // presenting a partial 12-month plan as a final annual percentage.
    val savingsRate = rateOrNull(
        numerator = savings.totalWon,
        denominator = income.totalWon,
        inputsComplete = income.isComplete && savings.isComplete,
    )
    val netSavingsRate = rateOrNull(
        numerator = netSavingsWon,
        denominator = income.totalWon,
        inputsComplete = income.isComplete && savings.isComplete,
    )

    val target = setup.savingsTargetFor(year)
    val goalRemainingWon = if (target != null && savings.isComplete) {
        checkedMoneySubtract(target, savings.totalWon)
    } else {
        null
    }
    val goalProgressRate = if (target != null && target > 0 && savings.isComplete) {
        savings.totalWon.toDouble() * 100.0 / target.toDouble()
    } else {
        null
    }

    val compositionComplete = fixed.isComplete && savings.isComplete
    val compositionTotal = listOf(fixed.totalWon, variableExpenseWon, savings.totalWon).checkedMoneySum()
    val composition = HomeAnnualComposition(
        fixedExpenseWon = fixed.totalWon,
        variableExpenseWon = variableExpenseWon,
        savingsWon = savings.totalWon,
        fixedExpensePercent = compositionPercent(fixed.totalWon, compositionTotal, compositionComplete),
        variableExpensePercent = compositionPercent(variableExpenseWon, compositionTotal, compositionComplete),
        savingsPercent = compositionPercent(savings.totalWon, compositionTotal, compositionComplete),
        isComplete = compositionComplete,
    )

    return HomeAnnualSummary(
        year = year,
        knownIncomeWon = income.totalWon,
        knownFixedExpenseWon = fixed.totalWon,
        variableExpenseWon = variableExpenseWon,
        knownTotalExpenseWon = listOf(fixed.totalWon, variableExpenseWon).checkedMoneySum(),
        knownSavingsWon = savings.totalWon,
        knownPurposeAccountSavingsWon = savings.totalWon,
        knownNetSavingsWon = netSavingsWon,
        incomeIsComplete = income.isComplete,
        fixedExpenseIsComplete = fixed.isComplete,
        savingsIsComplete = savings.isComplete,
        savingsRate = savingsRate,
        netSavingsRate = netSavingsRate,
        goalProgressRate = goalProgressRate,
        goalRemainingWon = goalRemainingWon,
        memberContributionLabel = setup.memberContributionLabel(savingsItems, savings.isComplete),
        composition = composition,
        isPartial = !(income.isComplete && fixed.isComplete && savings.isComplete),
        isLoading = isLoading,
        loadError = loadError,
    )
}

private data class KnownAmount(
    val totalWon: Long,
    val isComplete: Boolean,
)

private fun List<HomeAnnualMonthSnapshot>.annualAmount(
    type: PlanItemType,
    hasAllCalendarMonths: Boolean,
): KnownAmount {
    val items = flatMap { month -> month.items.filter { it.type == type } }
    val everyMonthComplete = hasAllCalendarMonths && all { month ->
        val monthItems = month.items.filter { it.type == type }
        monthItems.isNotEmpty() && monthItems.none { it.amountWon == null }
    }
    return KnownAmount(totalWon = items.sumKnownAmount(), isComplete = everyMonthComplete)
}

private fun List<MonthlyPlanItem>.sumKnownAmount(): Long = map { it.amountWon ?: 0L }.checkedMoneySum()

internal fun Iterable<Long>.checkedMoneySum(): Long {
    var total = 0L
    for (value in this) {
        if (value > 0L && total > Long.MAX_VALUE - value) throw ArithmeticException("금액 합계가 허용 범위를 넘었어요")
        if (value < 0L && total < Long.MIN_VALUE - value) throw ArithmeticException("금액 합계가 허용 범위를 넘었어요")
        total += value
    }
    return total
}

private fun checkedMoneySubtract(left: Long, right: Long): Long {
    if (right > 0L && left < Long.MIN_VALUE + right) throw ArithmeticException("금액 합계가 허용 범위를 넘었어요")
    if (right < 0L && left > Long.MAX_VALUE + right) throw ArithmeticException("금액 합계가 허용 범위를 넘었어요")
    return left - right
}

private fun rateOrNull(
    numerator: Long,
    denominator: Long,
    inputsComplete: Boolean,
): Double? = if (inputsComplete && denominator > 0) {
    numerator.toDouble() * 100.0 / denominator.toDouble()
} else {
    null
}

private fun compositionPercent(amount: Long, total: Long, isComplete: Boolean): Double? =
    if (isComplete && total > 0) amount.toDouble() * 100.0 / total.toDouble() else null

private fun LedgerSetup.memberContributionLabel(
    savingsItems: List<MonthlyPlanItem>,
    savingsComplete: Boolean,
): String? {
    if (!savingsComplete || savingsItems.isEmpty()) return null
    if (savingsItems.any { it.ownerMemberOrder == null }) return null

    val contributionsByOrder = savingsItems
        .groupBy { it.ownerMemberOrder!! }
        .mapValues { (_, items) -> items.sumKnownAmount() }
    if (contributionsByOrder.keys.any { order -> members.none { it.order == order } }) return null
    if (contributionsByOrder.values.any { it < 0 }) return null

    val total = contributionsByOrder.values.checkedMoneySum()
    if (total <= 0) return null

    return members.sortedBy { it.order }.joinToString(" · ") { member ->
        val amount = contributionsByOrder[member.order] ?: 0L
        "${member.displayName} ${formatContributionPercent(amount, total)}"
    }
}

private fun formatContributionPercent(amount: Long, total: Long): String {
    val percentTimesTen = ((amount.toDouble() * 1_000.0 / total.toDouble()) + 0.5).toLong()
    val whole = percentTimesTen / 10
    val decimal = percentTimesTen % 10
    return if (decimal == 0L) "$whole%" else "$whole.$decimal%"
}
