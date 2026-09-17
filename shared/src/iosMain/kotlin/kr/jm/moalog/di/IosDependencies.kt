package kr.jm.moalog.di

import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.assets.domain.AssetRepository
import kr.jm.moalog.feature.assets.presentation.AssetDetailArgs
import kr.jm.moalog.feature.assets.presentation.AssetDetailStateHolder
import kr.jm.moalog.feature.assets.presentation.AssetEditorArgs
import kr.jm.moalog.feature.assets.presentation.AssetEditorMode
import kr.jm.moalog.feature.assets.presentation.AssetEditorStateHolder
import kr.jm.moalog.feature.assets.presentation.AssetsStateHolder
import kr.jm.moalog.feature.assets.presentation.PurposeAccountsStateHolder
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.feature.maintenance.domain.MaintenanceRepository
import kr.jm.moalog.feature.maintenance.presentation.MaintenanceEditorArgs
import kr.jm.moalog.feature.maintenance.presentation.MaintenanceEditorStateHolder
import kr.jm.moalog.feature.maintenance.presentation.MaintenanceOverviewArgs
import kr.jm.moalog.feature.maintenance.presentation.MaintenanceOverviewStateHolder
import kr.jm.moalog.feature.home.presentation.CompositionAnalysisStateHolder
import kr.jm.moalog.feature.home.presentation.CompositionTab
import kr.jm.moalog.feature.plan.domain.FixedCostCheckRepository
import kr.jm.moalog.feature.plan.domain.PlanRepository
import kr.jm.moalog.feature.plan.domain.SalaryAllocationRepository
import kr.jm.moalog.feature.plan.presentation.AnnualPlanArgs
import kr.jm.moalog.feature.plan.presentation.AnnualPlanStateHolder
import kr.jm.moalog.feature.plan.presentation.FixedCostCheckArgs
import kr.jm.moalog.feature.plan.presentation.FixedCostCheckStateHolder
import kr.jm.moalog.feature.plan.presentation.MultiMonthApplyArgs
import kr.jm.moalog.feature.plan.presentation.MultiMonthApplyStateHolder
import kr.jm.moalog.feature.plan.presentation.ItemManagementStateHolder
import kr.jm.moalog.feature.plan.presentation.PlanEditorArgs
import kr.jm.moalog.feature.plan.presentation.PlanEditorStateHolder
import kr.jm.moalog.feature.plan.presentation.PlanStateHolder
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationArgs
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationDetailArgs
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationDetailStateHolder
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationStateHolder
import kr.jm.moalog.feature.records.domain.ExpenseRepository
import kr.jm.moalog.feature.records.presentation.ExpenseEditorArgs
import kr.jm.moalog.feature.records.presentation.ExpenseEditorStateHolder
import kr.jm.moalog.feature.records.presentation.RecordsStateHolder
import kr.jm.moalog.feature.setup.domain.SetupRepository
import kr.jm.moalog.feature.setup.presentation.LedgerSettingsStateHolder
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/**
 * Typed access to the shared dependency graph for the native SwiftUI host.
 *
 * Swift code owns every factory-created state holder and must call `close()` when
 * that holder exposes it and the corresponding screen leaves the navigation stack.
 */
class IosDependencies {
    init {
        initKoin()
    }

    private val koin: Koin
        get() = KoinPlatform.getKoin()

    fun setupRepository(): SetupRepository = koin.get()
    fun authStore(): IosAuthStore = IosAuthStore(koin.get())
    fun householdStore(): IosHouseholdStore = IosHouseholdStore(koin.get())
    fun syncStore(): IosSyncStore = IosSyncStore(koin.get())
    fun rootStore(): IosRootStore = IosRootStore(koin.get())
    fun setupStore(): IosSetupStore = IosSetupStore(koin.get())
    fun ledgerSettingsStore(): IosLedgerSettingsStore =
        IosLedgerSettingsStore(koin.get<LedgerSettingsStateHolder>())
    fun homeStore(setup: LedgerSetup): IosHomeStore = IosHomeStore(
        initialSetup = setup,
        setupRepository = koin.get(),
        planRepository = koin.get(),
        recordsStateHolder = koin.get(),
    )
    fun goalEditStore(setup: LedgerSetup, year: Int): IosGoalEditStore =
        IosGoalEditStore(setup, year, koin.get())
    fun compositionAnalysisStore(
        initialMonth: YearMonthKey,
        initialTab: CompositionTab = CompositionTab.VariableExpense,
    ): IosCompositionAnalysisStore = IosCompositionAnalysisStore(
        koin.get<CompositionAnalysisStateHolder> { parametersOf(initialMonth, initialTab) },
    )

    fun expenseRepository(): ExpenseRepository = koin.get()
    fun recordsStateHolder(): RecordsStateHolder = koin.get()
    fun recordsStore(): IosRecordsStore = IosRecordsStore(koin.get())
    fun expenseEditorStateHolder(args: ExpenseEditorArgs): ExpenseEditorStateHolder =
        koin.get { parametersOf(args) }
    fun expenseEditorStore(recordId: Long?, initialMonth: YearMonthKey): IosExpenseEditorStore =
        IosExpenseEditorStore(koin.get { parametersOf(ExpenseEditorArgs(recordId, initialMonth)) })

    fun planRepository(): PlanRepository = koin.get()
    fun salaryAllocationRepository(): SalaryAllocationRepository = koin.get()
    fun fixedCostCheckRepository(): FixedCostCheckRepository = koin.get()
    fun planStore(): IosPlanStore = IosPlanStore(koin.get())
    fun itemManagementStore(): IosItemManagementStore =
        IosItemManagementStore(koin.get<ItemManagementStateHolder>())
    fun planEditorStore(
        itemId: Long?,
        type: kr.jm.moalog.core.model.PlanItemType,
        initialMonth: YearMonthKey,
    ): IosPlanEditorStore = IosPlanEditorStore(
        koin.get { parametersOf(PlanEditorArgs(itemId, type, initialMonth)) },
    )
    fun createPlanEditorStore(
        type: kr.jm.moalog.core.model.PlanItemType,
        initialMonth: YearMonthKey,
    ): IosPlanEditorStore = planEditorStore(null, type, initialMonth)
    fun existingPlanEditorStore(
        itemId: Long,
        type: kr.jm.moalog.core.model.PlanItemType,
        initialMonth: YearMonthKey,
    ): IosPlanEditorStore = planEditorStore(itemId, type, initialMonth)
    fun planStateHolder(): PlanStateHolder = koin.get()
    fun planEditorStateHolder(args: PlanEditorArgs): PlanEditorStateHolder =
        koin.get { parametersOf(args) }
    fun multiMonthApplyStateHolder(args: MultiMonthApplyArgs): MultiMonthApplyStateHolder =
        koin.get { parametersOf(args) }
    fun multiMonthApplyStore(
        sourceMonth: YearMonthKey,
        type: kr.jm.moalog.core.model.PlanItemType,
        sourceItemId: Long?,
    ): IosMultiMonthApplyStore = IosMultiMonthApplyStore(
        koin.get { parametersOf(MultiMonthApplyArgs(sourceMonth, type, sourceItemId)) },
    )
    fun annualPlanStateHolder(args: AnnualPlanArgs): AnnualPlanStateHolder =
        koin.get { parametersOf(args) }
    fun annualPlanStore(year: Int): IosAnnualPlanStore = IosAnnualPlanStore(
        koin.get { parametersOf(AnnualPlanArgs(year)) },
    )
    fun salaryAllocationStateHolder(args: SalaryAllocationArgs): SalaryAllocationStateHolder =
        koin.get { parametersOf(args) }
    fun salaryAllocationStore(
        month: YearMonthKey,
        firstMemberOrder: Int,
        secondMemberOrder: Int,
    ): IosSalaryAllocationStore = IosSalaryAllocationStore(
        koin.get { parametersOf(SalaryAllocationArgs(month, listOf(firstMemberOrder, secondMemberOrder))) },
    )
    fun salaryAllocationDetailStateHolder(
        args: SalaryAllocationDetailArgs,
    ): SalaryAllocationDetailStateHolder = koin.get { parametersOf(args) }
    fun salaryAllocationDetailStore(
        categoryId: Long,
        month: YearMonthKey,
    ): IosSalaryAllocationDetailStore = IosSalaryAllocationDetailStore(
        koin.get { parametersOf(SalaryAllocationDetailArgs(categoryId, month)) },
    )
    fun fixedCostCheckStateHolder(args: FixedCostCheckArgs): FixedCostCheckStateHolder =
        koin.get { parametersOf(args) }
    fun fixedCostCheckStore(
        month: YearMonthKey,
        firstMemberOrder: Int,
        secondMemberOrder: Int,
    ): IosFixedCostCheckStore = IosFixedCostCheckStore(
        koin.get { parametersOf(FixedCostCheckArgs(month, listOf(firstMemberOrder, secondMemberOrder))) },
    )

    fun assetRepository(): AssetRepository = koin.get()
    fun assetsStateHolder(initialMonth: YearMonthKey): AssetsStateHolder =
        koin.get { parametersOf(initialMonth) }
    fun assetsStore(initialMonth: YearMonthKey): IosAssetsStore =
        IosAssetsStore(koin.get { parametersOf(initialMonth) })
    fun assetDetailStateHolder(args: AssetDetailArgs): AssetDetailStateHolder =
        koin.get { parametersOf(args) }
    fun assetDetailStore(assetId: Long, month: YearMonthKey): IosAssetDetailStore =
        IosAssetDetailStore(koin.get { parametersOf(AssetDetailArgs(assetId, month)) })
    fun assetEditorStateHolder(args: AssetEditorArgs): AssetEditorStateHolder =
        koin.get { parametersOf(args) }
    fun assetEditorStore(
        assetId: Long?,
        month: YearMonthKey,
        mode: AssetEditorMode,
        purposeAccount: Boolean,
    ): IosAssetEditorStore = IosAssetEditorStore(
        koin.get { parametersOf(AssetEditorArgs(assetId, month, mode, purposeAccount)) },
    )
    fun purposeAccountsStateHolder(initialMonth: YearMonthKey): PurposeAccountsStateHolder =
        koin.get { parametersOf(initialMonth) }
    fun purposeAccountsStore(initialMonth: YearMonthKey): IosPurposeAccountsStore =
        IosPurposeAccountsStore(koin.get { parametersOf(initialMonth) })

    fun maintenanceRepository(): MaintenanceRepository = koin.get()
    fun maintenanceOverviewStateHolder(
        args: MaintenanceOverviewArgs,
    ): MaintenanceOverviewStateHolder = koin.get { parametersOf(args) }
    fun maintenanceEditorStateHolder(
        args: MaintenanceEditorArgs,
    ): MaintenanceEditorStateHolder = koin.get { parametersOf(args) }
}

/** Starts the shared graph and returns the sole Swift-facing resolution API. */
fun createIosDependencies(): IosDependencies = IosDependencies()
