package kr.jm.moalog.core.model

enum class MaintenanceFeeGroup {
    CommonManagement,
    Electricity,
    HouseholdUtilities,
    DeductionsAndOther,
}

/** The persisted keys and display order mirror the source maintenance-fee sheet. */
enum class MaintenanceFeeItemKey(
    val label: String,
    val group: MaintenanceFeeGroup,
    val acceptsNegative: Boolean = false,
) {
    GeneralManagement("일반관리비", MaintenanceFeeGroup.CommonManagement),
    Cleaning("청소비", MaintenanceFeeGroup.CommonManagement),
    Disinfection("소독비", MaintenanceFeeGroup.CommonManagement),
    ElevatorMaintenance("승강기유지비", MaintenanceFeeGroup.CommonManagement),
    RepairMaintenance("수선유지비", MaintenanceFeeGroup.CommonManagement),
    LongTermRepairReserve("장기수선충당금", MaintenanceFeeGroup.CommonManagement),
    BuildingInsurance("건물보험료", MaintenanceFeeGroup.CommonManagement),
    SecurityService("경비용역비", MaintenanceFeeGroup.CommonManagement),
    ManagementCommission("위탁관리수수료", MaintenanceFeeGroup.CommonManagement),
    ResidentsCommittee("입주자대표회의", MaintenanceFeeGroup.CommonManagement),
    ElectionCommittee("선거관리위원회", MaintenanceFeeGroup.CommonManagement),
    HouseholdElectricity("세대전기료", MaintenanceFeeGroup.Electricity),
    CommonElectricity("공동전기료", MaintenanceFeeGroup.Electricity),
    ElevatorElectricity("승강기전기", MaintenanceFeeGroup.Electricity),
    TvLicense("TV수신료", MaintenanceFeeGroup.Electricity),
    HouseholdWater("세대수도료", MaintenanceFeeGroup.HouseholdUtilities),
    HouseholdHeating("세대난방비", MaintenanceFeeGroup.HouseholdUtilities),
    BasicHeating("기본난방비", MaintenanceFeeGroup.HouseholdUtilities),
    HouseholdHotWater("세대급탕비", MaintenanceFeeGroup.HouseholdUtilities),
    Deduction("관리비차감", MaintenanceFeeGroup.DeductionsAndOther, acceptsNegative = true),
    HouseholdWaste("생활폐기물수수료", MaintenanceFeeGroup.DeductionsAndOther),
}

data class MaintenanceFeeEntry(
    val key: MaintenanceFeeItemKey,
    val amountWon: Long?,
) {
    init {
        require(amountWon == null || key.acceptsNegative || amountWon >= 0) {
            "Only maintenance-fee deductions may be negative"
        }
    }
}

data class MaintenanceFeeMonth(
    val ledgerId: Long = 1,
    val billMonth: YearMonthKey,
    val entries: List<MaintenanceFeeEntry>,
) {
    init {
        require(ledgerId > 0) { "ledgerId must be positive" }
        require(entries.map { it.key } == MaintenanceFeeItemKey.entries) {
            "Maintenance-fee entries must contain all 21 items exactly once in canonical order"
        }
    }

    val totalWon: Long?
        get() = entries.mapNotNull { it.amountWon }
            .takeIf { it.isNotEmpty() }
            ?.fold(0L, ::checkedMaintenanceFeeAdd)

    val enteredCount: Int get() = entries.count { it.amountWon != null }

    fun amountOf(key: MaintenanceFeeItemKey): Long? = entries[key.ordinal].amountWon

    companion object {
        fun empty(billMonth: YearMonthKey, ledgerId: Long = 1): MaintenanceFeeMonth = MaintenanceFeeMonth(
            ledgerId = ledgerId,
            billMonth = billMonth,
            entries = MaintenanceFeeItemKey.entries.map { MaintenanceFeeEntry(it, null) },
        )

        fun fromAmounts(
            billMonth: YearMonthKey,
            amounts: Map<MaintenanceFeeItemKey, Long?>,
            ledgerId: Long = 1,
        ): MaintenanceFeeMonth = MaintenanceFeeMonth(
            ledgerId = ledgerId,
            billMonth = billMonth,
            entries = MaintenanceFeeItemKey.entries.map { key -> MaintenanceFeeEntry(key, amounts[key]) },
        )
    }
}

private fun checkedMaintenanceFeeAdd(total: Long, amount: Long): Long {
    require(
        (amount <= 0 || total <= Long.MAX_VALUE - amount) &&
            (amount >= 0 || total >= Long.MIN_VALUE - amount),
    ) { "Maintenance-fee total is outside the supported range" }
    return total + amount
}
