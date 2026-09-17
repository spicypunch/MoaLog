package kr.jm.moalog.core.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val SYNC_MIN_YEAR = 1900
const val SYNC_MAX_YEAR = 9999

@Serializable
data class SyncYearMonthPayload(val year: Int, val month: Int) {
    init {
        require(year in SYNC_MIN_YEAR..SYNC_MAX_YEAR)
        require(month in 1..12)
    }
}

@Serializable
data class SyncDatePayload(val year: Int, val month: Int, val day: Int) {
    init {
        require(year in SYNC_MIN_YEAR..SYNC_MAX_YEAR)
        require(month in 1..12)
        require(day in 1..daysInMonth(year, month))
    }
}

@Serializable
enum class SyncPlanItemType {
    @SerialName("income") INCOME,
    @SerialName("fixed_expense") FIXED_EXPENSE,
    @SerialName("savings") SAVINGS,
}

@Serializable
enum class SyncPlanItemStatus {
    @SerialName("estimated") ESTIMATED,
    @SerialName("confirmed") CONFIRMED,
}

@Serializable
enum class SyncSalaryAllocationMethod {
    @SerialName("fixed_amount") FIXED_AMOUNT,
    @SerialName("salary_ratio") SALARY_RATIO,
    @SerialName("child_total") CHILD_TOTAL,
    @SerialName("remaining_from_source") REMAINING_FROM_SOURCE,
}

@Serializable
enum class SyncAssetType {
    @SerialName("cash") CASH,
    @SerialName("deposit") DEPOSIT,
    @SerialName("investment") INVESTMENT,
    @SerialName("housing") HOUSING,
    @SerialName("other") OTHER,
}

@Serializable
enum class SyncAssetKind {
    @SerialName("ordinary") ORDINARY,
    @SerialName("purpose_account") PURPOSE_ACCOUNT,
}

@Serializable
enum class SyncMaintenanceFeeItemKey {
    @SerialName("general_management") GENERAL_MANAGEMENT,
    @SerialName("cleaning") CLEANING,
    @SerialName("disinfection") DISINFECTION,
    @SerialName("elevator_maintenance") ELEVATOR_MAINTENANCE,
    @SerialName("repair_maintenance") REPAIR_MAINTENANCE,
    @SerialName("long_term_repair_reserve") LONG_TERM_REPAIR_RESERVE,
    @SerialName("building_insurance") BUILDING_INSURANCE,
    @SerialName("security_service") SECURITY_SERVICE,
    @SerialName("management_commission") MANAGEMENT_COMMISSION,
    @SerialName("residents_committee") RESIDENTS_COMMITTEE,
    @SerialName("election_committee") ELECTION_COMMITTEE,
    @SerialName("household_electricity") HOUSEHOLD_ELECTRICITY,
    @SerialName("common_electricity") COMMON_ELECTRICITY,
    @SerialName("elevator_electricity") ELEVATOR_ELECTRICITY,
    @SerialName("tv_license") TV_LICENSE,
    @SerialName("household_water") HOUSEHOLD_WATER,
    @SerialName("household_heating") HOUSEHOLD_HEATING,
    @SerialName("basic_heating") BASIC_HEATING,
    @SerialName("household_hot_water") HOUSEHOLD_HOT_WATER,
    @SerialName("deduction") DEDUCTION,
    @SerialName("household_waste") HOUSEHOLD_WASTE,
}

@Serializable
data class PlanCatalogItemSyncPayload(
    val type: SyncPlanItemType,
    val classification: String,
    val name: String,
    val ownerMemberOrder: Int? = null,
    val includePurposeAccount: Boolean = false,
    val includeNetSavings: Boolean = false,
    val displayOrder: Int = 0,
    val archived: Boolean = false,
) {
    init {
        requireText(classification, 80)
        requireText(name, 120)
        requireMemberOrder(ownerMemberOrder)
        require(displayOrder >= 0)
        if (type != SyncPlanItemType.SAVINGS) require(!includePurposeAccount && !includeNetSavings)
    }
}

@Serializable
data class MonthlyPlanItemSyncPayload(
    val catalogId: UuidString,
    val attributionMonth: SyncYearMonthPayload,
    val amountWon: Long? = null,
    val status: SyncPlanItemStatus,
    val memo: String? = null,
) {
    init { requireOptionalText(memo, 1_000) }
}

@Serializable
data class ExpenseCategorySyncPayload(
    val name: String,
    val displayOrder: Int,
    val archived: Boolean = false,
) {
    init {
        requireText(name, 120)
        require(displayOrder >= 0)
    }
}

@Serializable
data class ExpenseRecordSyncPayload(
    val categoryId: UuidString,
    val categoryName: String,
    val attributionMonth: SyncYearMonthPayload,
    val actualDate: SyncDatePayload? = null,
    val detail: String? = null,
    val amountWon: Long,
    val overspent: Boolean,
) {
    init {
        requireText(categoryName, 120)
        requireOptionalText(detail, 1_000)
        require(amountWon >= 0)
    }
}

@Serializable
data class SalaryIncomeSyncPayload(
    val attributionMonth: SyncYearMonthPayload,
    val memberOrder: Int,
    val amountWon: Long? = null,
) {
    init {
        require(memberOrder >= 0)
        require(amountWon == null || amountWon >= 0)
    }
}

@Serializable
data class SalaryAllocationCategorySyncPayload(
    val attributionMonth: SyncYearMonthPayload,
    val name: String,
    val sourceMemberOrder: Int? = null,
    val method: SyncSalaryAllocationMethod,
    val amountWon: Long? = null,
    val rateBasisPoints: Int? = null,
    val memo: String? = null,
    val displayOrder: Int = 0,
) {
    init {
        requireText(name, 120)
        requireMemberOrder(sourceMemberOrder)
        require(amountWon == null || amountWon >= 0)
        require(rateBasisPoints == null || rateBasisPoints in 0..10_000)
        requireOptionalText(memo, 1_000)
        require(displayOrder >= 0)
        when (method) {
            SyncSalaryAllocationMethod.FIXED_AMOUNT -> require(rateBasisPoints == null)
            SyncSalaryAllocationMethod.SALARY_RATIO -> require(amountWon == null && rateBasisPoints != null)
            SyncSalaryAllocationMethod.CHILD_TOTAL,
            SyncSalaryAllocationMethod.REMAINING_FROM_SOURCE,
            -> require(amountWon == null && rateBasisPoints == null)
        }
    }
}

@Serializable
data class SalaryAllocationDeductionSyncPayload(
    val categoryId: UuidString,
    val deductedCategoryId: UuidString,
) {
    init { require(categoryId != deductedCategoryId) }
}

@Serializable
data class SalaryAllocationChildSyncPayload(
    val categoryId: UuidString,
    val attributionMonth: SyncYearMonthPayload,
    val name: String,
    val amountWon: Long? = null,
    val memo: String? = null,
    val displayOrder: Int = 0,
) {
    init {
        requireText(name, 120)
        require(amountWon == null || amountWon >= 0)
        requireOptionalText(memo, 1_000)
        require(displayOrder >= 0)
    }
}

@Serializable
data class SalaryAllocationGrandchildSyncPayload(
    val childId: UuidString,
    val attributionMonth: SyncYearMonthPayload,
    val name: String,
    val amountWon: Long? = null,
    val memo: String? = null,
    val displayOrder: Int = 0,
) {
    init {
        requireText(name, 120)
        require(amountWon == null || amountWon >= 0)
        requireOptionalText(memo, 1_000)
        require(displayOrder >= 0)
    }
}

@Serializable
data class FixedCostItemSyncPayload(
    val attributionMonth: SyncYearMonthPayload,
    val payerMemberOrder: Int? = null,
    val name: String,
    val amountWon: Long? = null,
    val displayOrder: Int = 0,
) {
    init {
        requireMemberOrder(payerMemberOrder)
        requireText(name, 120)
        require(amountWon == null || amountWon >= 0)
        require(displayOrder >= 0)
    }
}

@Serializable
data class AssetSyncPayload(
    val name: String,
    val type: SyncAssetType,
    val ownerMemberOrder: Int? = null,
    val memo: String? = null,
    val kind: SyncAssetKind = SyncAssetKind.ORDINARY,
) {
    init {
        requireText(name, 120)
        requireMemberOrder(ownerMemberOrder)
        requireOptionalText(memo, 1_000)
    }
}

@Serializable
data class AssetValuationSyncPayload(
    val assetId: UuidString,
    val valuationMonth: SyncYearMonthPayload,
    val amountWon: Long,
) {
    init { require(amountWon >= 0) }
}

@Serializable
data class AssetGrowthRuleSyncPayload(
    val assetId: UuidString,
    val startMonth: SyncYearMonthPayload,
    val durationMonths: Int,
    val baseAmountWon: Long,
    val monthlyIncreaseWon: Long,
) {
    init {
        require(durationMonths in 1..1_200)
        require(baseAmountWon >= 0)
    }
}

@Serializable
data class MaintenanceFeeEntrySyncPayload(
    val key: SyncMaintenanceFeeItemKey,
    val amountWon: Long? = null,
) {
    init { require(amountWon == null || key == SyncMaintenanceFeeItemKey.DEDUCTION || amountWon >= 0) }
}

@Serializable
data class MaintenanceFeeMonthSyncPayload(
    val billMonth: SyncYearMonthPayload,
    val entries: List<MaintenanceFeeEntrySyncPayload>,
) {
    init {
        require(entries.map { it.key } == SyncMaintenanceFeeItemKey.entries)
    }
}

private fun requireText(value: String, maxLength: Int) {
    require(value.isNotBlank() && value.length <= maxLength)
}

private fun requireOptionalText(value: String?, maxLength: Int) {
    require(value == null || value.length <= maxLength)
}

private fun requireMemberOrder(value: Int?) {
    require(value == null || value >= 0)
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 400 == 0 || year % 4 == 0 && year % 100 != 0) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}
