package kr.jm.moalog.di

import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.home.presentation.HomeAnnualSummary
import kr.jm.moalog.feature.home.presentation.HomeAnnualMonthSnapshot
import kr.jm.moalog.feature.home.presentation.HomeMonthlySummary
import kr.jm.moalog.feature.home.presentation.HomeUiState
import kr.jm.moalog.feature.home.presentation.CompositionAnalysisAction
import kr.jm.moalog.feature.home.presentation.CompositionAnalysisStateHolder
import kr.jm.moalog.feature.home.presentation.CompositionAnalysisUiState
import kr.jm.moalog.feature.home.presentation.CompositionPeriod
import kr.jm.moalog.feature.home.presentation.CompositionTab
import kr.jm.moalog.feature.home.presentation.calculateHomeAnnualSummary
import kr.jm.moalog.feature.home.presentation.toHomeUiState
import kr.jm.moalog.feature.home.domain.HomeRepository
import kr.jm.moalog.feature.plan.domain.PlanRepository
import kr.jm.moalog.feature.plan.domain.PlanYearMonth
import kr.jm.moalog.feature.plan.presentation.PlanAction
import kr.jm.moalog.feature.plan.presentation.PlanEditorAction
import kr.jm.moalog.feature.plan.presentation.PlanEditorDraftSnapshot
import kr.jm.moalog.feature.plan.presentation.PlanEditorErrors
import kr.jm.moalog.feature.plan.presentation.PlanEditorStateHolder
import kr.jm.moalog.feature.plan.presentation.PlanEditorUiState
import kr.jm.moalog.feature.plan.presentation.MultiMonthApplyAction
import kr.jm.moalog.feature.plan.presentation.MultiMonthApplyDraftSnapshot
import kr.jm.moalog.feature.plan.presentation.MultiMonthApplyStateHolder
import kr.jm.moalog.feature.plan.presentation.MultiMonthApplyUiState
import kr.jm.moalog.feature.plan.presentation.ItemManagementAction
import kr.jm.moalog.feature.plan.presentation.ItemManagementDraft
import kr.jm.moalog.feature.plan.presentation.ItemManagementStateHolder
import kr.jm.moalog.feature.plan.presentation.ItemManagementUiState
import kr.jm.moalog.feature.plan.presentation.ManagedItemType
import kr.jm.moalog.feature.plan.presentation.toDraftSnapshot
import kr.jm.moalog.feature.plan.presentation.PlanStateHolder
import kr.jm.moalog.feature.plan.presentation.PlanTab
import kr.jm.moalog.feature.plan.presentation.PlanUiState
import kr.jm.moalog.feature.plan.presentation.AnnualPlanAction
import kr.jm.moalog.feature.plan.presentation.AnnualPlanSection
import kr.jm.moalog.feature.plan.presentation.AnnualPlanStateHolder
import kr.jm.moalog.feature.plan.presentation.AnnualPlanUiState
import kr.jm.moalog.feature.plan.presentation.AnnualPlanViewMode
import kr.jm.moalog.feature.plan.presentation.FixedCostCheckAction
import kr.jm.moalog.feature.plan.presentation.FixedCostCheckStateHolder
import kr.jm.moalog.feature.plan.presentation.FixedCostCheckUiState
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationAction
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationDetailAction
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationDetailStateHolder
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationDetailUiState
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationStateHolder
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationUiState
import kr.jm.moalog.core.model.FixedCostCheckItem
import kr.jm.moalog.core.model.SalaryAllocationCategory
import kr.jm.moalog.core.model.SalaryAllocationChild
import kr.jm.moalog.core.model.SalaryAllocationGrandchild
import kr.jm.moalog.core.model.SalaryAllocationMethod
import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.AssetType
import kr.jm.moalog.core.model.AssetKind
import kr.jm.moalog.core.database.ExistingPlanPolicy
import kr.jm.moalog.feature.records.presentation.RecordsAction
import kr.jm.moalog.feature.records.presentation.ExpenseFilters
import kr.jm.moalog.feature.records.presentation.ExpenseSort
import kr.jm.moalog.feature.records.presentation.ExpenseEditorAction
import kr.jm.moalog.feature.records.presentation.ExpenseEditorStateHolder
import kr.jm.moalog.feature.records.presentation.ExpenseEditorUiState
import kr.jm.moalog.feature.records.presentation.ExpenseEditorDraftSnapshot
import kr.jm.moalog.feature.records.presentation.RecordsStateHolder
import kr.jm.moalog.feature.records.presentation.RecordsUiState
import kr.jm.moalog.feature.assets.presentation.AssetsAction
import kr.jm.moalog.feature.assets.presentation.AssetsStateHolder
import kr.jm.moalog.feature.assets.presentation.AssetsUiState
import kr.jm.moalog.feature.assets.presentation.AssetDetailAction
import kr.jm.moalog.feature.assets.presentation.AssetDetailStateHolder
import kr.jm.moalog.feature.assets.presentation.AssetDetailUiState
import kr.jm.moalog.feature.assets.presentation.AssetEditorAction
import kr.jm.moalog.feature.assets.presentation.AssetEditorMode
import kr.jm.moalog.feature.assets.presentation.AssetEditorStateHolder
import kr.jm.moalog.feature.assets.presentation.AssetEditorUiState
import kr.jm.moalog.feature.assets.presentation.PurposeAccountsAction
import kr.jm.moalog.feature.assets.presentation.PurposeAccountsStateHolder
import kr.jm.moalog.feature.assets.presentation.PurposeAccountsUiState
import kr.jm.moalog.feature.setup.domain.SetupRepository
import kr.jm.moalog.feature.setup.presentation.SetupAction
import kr.jm.moalog.feature.setup.presentation.SetupStateHolder
import kr.jm.moalog.feature.setup.presentation.SetupUiState
import kr.jm.moalog.feature.setup.presentation.LedgerSettingsAction
import kr.jm.moalog.feature.setup.presentation.LedgerSettingsDraft
import kr.jm.moalog.feature.setup.presentation.LedgerSettingsStateHolder
import kr.jm.moalog.feature.setup.presentation.LedgerSettingsUiState
import kr.jm.moalog.feature.setup.presentation.WonParseResult
import kr.jm.moalog.feature.setup.presentation.parseOptionalWon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A subscription handle that Swift can cancel without importing coroutine types. */
class IosObservation internal constructor(
    private val job: Job,
) {
    fun cancel() = job.cancel()
}

enum class IosRootDestination {
    Loading,
    Setup,
    Main,
    Failed,
}

data class IosRootState(
    val destination: IosRootDestination = IosRootDestination.Loading,
    val setup: LedgerSetup? = null,
)

/** Owns the setup lookup that selects the first native SwiftUI destination. */
class IosRootStore internal constructor(
    private val repository: SetupRepository,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(IosRootState())
    private var loadJob: Job? = null
    private var isClosed = false

    val currentState: IosRootState
        get() = mutableState.value

    init {
        load()
    }

    fun observe(observer: (IosRootState) -> Unit): IosObservation {
        val job = scope.launch {
            mutableState.collect(observer)
        }
        return IosObservation(job)
    }

    fun retry() {
        if (!isClosed) load()
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        storeJob.cancel()
    }

    private fun load() {
        loadJob?.cancel()
        mutableState.value = IosRootState()
        loadJob = scope.launch {
            try {
                repository.observeSetup().collect { setup ->
                    mutableState.value = if (setup == null) {
                        IosRootState(destination = IosRootDestination.Setup)
                    } else {
                        IosRootState(destination = IosRootDestination.Main, setup = setup)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.value = IosRootState(destination = IosRootDestination.Failed)
            }
        }
    }
}

/**
 * Swift-facing adapter for [SetupStateHolder]. It keeps sealed actions and
 * coroutine ownership on the Kotlin side of the framework boundary.
 */
class IosSetupStore internal constructor(
    repository: SetupRepository,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private val holder = SetupStateHolder(repository, scope)
    private var isClosed = false

    val currentState: SetupUiState
        get() = holder.state.value

    fun observe(observer: (SetupUiState) -> Unit): IosObservation {
        val job = scope.launch {
            holder.state.collect(observer)
        }
        return IosObservation(job)
    }

    fun changeLedgerName(value: String) = dispatch(SetupAction.LedgerNameChanged(value))

    fun changeFirstMemberName(value: String) = dispatch(SetupAction.FirstMemberNameChanged(value))

    fun changeSecondMemberName(value: String) = dispatch(SetupAction.SecondMemberNameChanged(value))

    fun changeBaseYear(value: String) = dispatch(SetupAction.BaseYearChanged(value))

    fun changeAnnualSavingsTarget(value: String) =
        dispatch(SetupAction.AnnualSavingsTargetChanged(value))

    fun save() = dispatch(SetupAction.Save)

    fun close() {
        if (isClosed) return
        isClosed = true
        storeJob.cancel()
    }

    private fun dispatch(action: SetupAction) {
        if (!isClosed) holder.onAction(action)
    }
}

/** Swift-facing B09 adapter that keeps restoration and sealed actions in Kotlin. */
class IosLedgerSettingsStore internal constructor(
    private val holder: LedgerSettingsStateHolder,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private var isClosed = false

    val currentState: LedgerSettingsUiState
        get() = holder.state.value

    fun observe(observer: (LedgerSettingsUiState) -> Unit): IosObservation = IosObservation(
        scope.launch { holder.state.collect(observer) },
    )

    fun changeLedgerName(value: String) = dispatch(LedgerSettingsAction.ChangeLedgerName(value))
    fun changeFirstMemberName(value: String) = dispatch(LedgerSettingsAction.ChangeFirstMemberName(value))
    fun changeSecondMemberName(value: String) = dispatch(LedgerSettingsAction.ChangeSecondMemberName(value))
    fun restoreDraft(ledgerName: String, firstMemberName: String, secondMemberName: String) = dispatch(
        LedgerSettingsAction.RestoreDraft(
            LedgerSettingsDraft(ledgerName, firstMemberName, secondMemberName),
        ),
    )
    fun save() = dispatch(LedgerSettingsAction.Save)
    fun retry() = dispatch(LedgerSettingsAction.Retry)

    fun close() {
        if (isClosed) return
        isClosed = true
        holder.close()
        storeJob.cancel()
    }

    private fun dispatch(action: LedgerSettingsAction) {
        if (!isClosed) holder.onAction(action)
    }
}

/** Swift-facing P19/B08 adapter. */
class IosItemManagementStore internal constructor(
    private val holder: ItemManagementStateHolder,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private var isClosed = false

    val currentState: ItemManagementUiState get() = holder.state.value

    init { holder.start() }

    fun observe(observer: (ItemManagementUiState) -> Unit): IosObservation = IosObservation(
        scope.launch { holder.state.collect(observer) },
    )

    fun selectType(type: ManagedItemType) = dispatch(ItemManagementAction.SelectType(type))
    fun showArchived(value: Boolean) = dispatch(ItemManagementAction.ShowArchived(value))
    fun add() = dispatch(ItemManagementAction.Add)
    fun edit(stableId: String) = dispatch(ItemManagementAction.Edit(stableId))
    fun restoreDraft(
        stableId: String?,
        name: String,
        classification: String,
        ownerMemberOrder: Int?,
        includePurposeAccount: Boolean,
        includeNetSavings: Boolean,
    ) = dispatch(
        ItemManagementAction.RestoreDraft(
            ItemManagementDraft(
                stableId, name, classification, ownerMemberOrder,
                includePurposeAccount, includeNetSavings,
            ),
        ),
    )
    fun changeName(value: String) = dispatch(ItemManagementAction.NameChanged(value))
    fun changeClassification(value: String) = dispatch(ItemManagementAction.ClassificationChanged(value))
    fun selectCommonOwner() = dispatch(ItemManagementAction.OwnerChanged(null))
    fun selectOwner(memberOrder: Int) = dispatch(ItemManagementAction.OwnerChanged(memberOrder))
    fun changePurpose(value: Boolean) = dispatch(ItemManagementAction.PurposeChanged(value))
    fun changeNetSavings(value: Boolean) = dispatch(ItemManagementAction.NetSavingsChanged(value))
    fun dismissEditor() = dispatch(ItemManagementAction.DismissEditor)
    fun save() = dispatch(ItemManagementAction.Save)
    fun requestArchive(stableId: String) = dispatch(ItemManagementAction.RequestArchive(stableId))
    fun dismissArchive() = dispatch(ItemManagementAction.DismissArchive)
    fun confirmArchive() = dispatch(ItemManagementAction.ConfirmArchive)
    fun restore(stableId: String) = dispatch(ItemManagementAction.Restore(stableId))
    fun moveUp(stableId: String) = dispatch(ItemManagementAction.Move(stableId, -1))
    fun moveDown(stableId: String) = dispatch(ItemManagementAction.Move(stableId, 1))
    fun retry() = dispatch(ItemManagementAction.Retry)
    fun clearResultAnnouncement() = dispatch(ItemManagementAction.ClearResultAnnouncement)

    fun close() {
        if (isClosed) return
        isClosed = true
        holder.close()
        storeJob.cancel()
    }

    private fun dispatch(action: ItemManagementAction) {
        if (!isClosed) holder.onAction(action)
    }
}

/** Swift-facing P03 adapter that keeps sealed actions inside Kotlin. */
class IosCompositionAnalysisStore internal constructor(
    private val holder: CompositionAnalysisStateHolder,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private var isClosed = false

    val currentState: CompositionAnalysisUiState
        get() = holder.state.value

    fun observe(observer: (CompositionAnalysisUiState) -> Unit): IosObservation = IosObservation(
        scope.launch { holder.state.collect(observer) },
    )

    fun selectTab(tab: CompositionTab) = dispatch(CompositionAnalysisAction.SelectTab(tab))
    fun selectMonthly() = dispatch(CompositionAnalysisAction.SelectPeriod(CompositionPeriod.Monthly))
    fun selectAnnual() = dispatch(CompositionAnalysisAction.SelectPeriod(CompositionPeriod.Annual))
    fun previousPeriod() = dispatch(CompositionAnalysisAction.PreviousPeriod)
    fun nextPeriod() = dispatch(CompositionAnalysisAction.NextPeriod)
    fun retry() = dispatch(CompositionAnalysisAction.Retry)

    fun close() {
        if (isClosed) return
        isClosed = true
        holder.close()
        storeJob.cancel()
    }

    private fun dispatch(action: CompositionAnalysisAction) {
        if (!isClosed) holder.onAction(action)
    }
}

/** Swift-facing annual overview that owns and closes the shared state holder. */
class IosAnnualPlanStore internal constructor(
    private val holder: AnnualPlanStateHolder,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private var isClosed = false

    val currentState: AnnualPlanUiState
        get() = holder.state.value

    init {
        holder.start()
    }

    fun observe(observer: (AnnualPlanUiState) -> Unit): IosObservation = IosObservation(
        scope.launch { holder.state.collect(observer) },
    )

    fun selectSection(section: AnnualPlanSection) = dispatch(AnnualPlanAction.SelectSection(section))
    fun showTable() = dispatch(AnnualPlanAction.SelectViewMode(AnnualPlanViewMode.Table))
    fun showMonthlyList() = dispatch(AnnualPlanAction.SelectViewMode(AnnualPlanViewMode.MonthlyList))
    fun retry() = dispatch(AnnualPlanAction.Retry)

    fun close() {
        if (isClosed) return
        isClosed = true
        holder.close()
        storeJob.cancel()
    }

    private fun dispatch(action: AnnualPlanAction) {
        if (!isClosed) holder.onAction(action)
    }
}

/** Swift-facing P08 adapter. Month navigation recreates this store with a new holder. */
class IosSalaryAllocationStore internal constructor(
    private val holder: SalaryAllocationStateHolder,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private var isClosed = false

    val currentState: SalaryAllocationUiState
        get() = holder.state.value

    init { holder.start() }

    fun observe(observer: (SalaryAllocationUiState) -> Unit): IosObservation = IosObservation(
        scope.launch { holder.state.collect(observer) },
    )

    fun targetAmount(categoryId: Long): Long? = currentState.targets[categoryId]

    fun openSalaryEditor() = dispatch(SalaryAllocationAction.OpenSalaryEditor)
    fun closeSalaryEditor() = dispatch(SalaryAllocationAction.CloseSalaryEditor)
    fun changeSalary(memberOrder: Int, value: String) =
        dispatch(SalaryAllocationAction.ChangeSalary(memberOrder, value))
    fun saveSalaries() = dispatch(SalaryAllocationAction.SaveSalaries)
    fun openCategoryEditor(category: SalaryAllocationCategory) =
        dispatch(SalaryAllocationAction.OpenCategoryEditor(category))
    fun openNewCategoryEditor() = dispatch(SalaryAllocationAction.OpenCategoryEditor())
    fun closeCategoryEditor() = dispatch(SalaryAllocationAction.CloseCategoryEditor)
    fun changeCategoryName(value: String) = dispatch(SalaryAllocationAction.ChangeCategoryName(value))
    fun selectCommonSource() = dispatch(SalaryAllocationAction.ChangeCategorySource(null))
    fun selectMemberSource(memberOrder: Int) =
        dispatch(SalaryAllocationAction.ChangeCategorySource(memberOrder))
    fun changeCategoryMethod(method: SalaryAllocationMethod) =
        dispatch(SalaryAllocationAction.ChangeCategoryMethod(method))
    fun changeCategoryValue(value: String) = dispatch(SalaryAllocationAction.ChangeCategoryValue(value))
    fun changeCategoryMemo(value: String) = dispatch(SalaryAllocationAction.ChangeCategoryMemo(value))
    fun toggleDeductedCategory(categoryId: Long) =
        dispatch(SalaryAllocationAction.ToggleDeductedCategory(categoryId))
    fun saveCategory() = dispatch(SalaryAllocationAction.SaveCategory)
    fun deleteCategory() = dispatch(SalaryAllocationAction.DeleteCategory)

    fun close() {
        if (isClosed) return
        isClosed = true
        holder.close()
        storeJob.cancel()
    }

    private fun dispatch(action: SalaryAllocationAction) {
        if (!isClosed) holder.onAction(action)
    }
}

/** Swift-facing P09 adapter for one allocation category's child rows. */
class IosSalaryAllocationDetailStore internal constructor(
    private val holder: SalaryAllocationDetailStateHolder,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private var isClosed = false

    val currentState: SalaryAllocationDetailUiState
        get() = holder.state.value

    init { holder.start() }

    fun observe(observer: (SalaryAllocationDetailUiState) -> Unit): IosObservation = IosObservation(
        scope.launch { holder.state.collect(observer) },
    )

    fun openChildEditor(child: SalaryAllocationChild) =
        dispatch(SalaryAllocationDetailAction.OpenChildEditor(child))
    fun openNewChildEditor() = dispatch(SalaryAllocationDetailAction.OpenChildEditor())
    fun closeEditor() = dispatch(SalaryAllocationDetailAction.CloseEditor)
    fun changeName(value: String) = dispatch(SalaryAllocationDetailAction.ChangeName(value))
    fun changeAmount(value: String) = dispatch(SalaryAllocationDetailAction.ChangeAmount(value))
    fun changeMemo(value: String) = dispatch(SalaryAllocationDetailAction.ChangeMemo(value))
    fun save() = dispatch(SalaryAllocationDetailAction.Save)
    fun delete() = dispatch(SalaryAllocationDetailAction.Delete)
    fun toggleChild(childId: Long) = dispatch(SalaryAllocationDetailAction.ToggleChild(childId))
    fun openGrandchildEditor(childId: Long, item: SalaryAllocationGrandchild) =
        dispatch(SalaryAllocationDetailAction.OpenGrandchildEditor(childId, item))
    fun openNewGrandchildEditor(childId: Long) =
        dispatch(SalaryAllocationDetailAction.OpenGrandchildEditor(childId))
    fun closeGrandchildEditor() = dispatch(SalaryAllocationDetailAction.CloseGrandchildEditor)
    fun changeGrandchildName(value: String) =
        dispatch(SalaryAllocationDetailAction.ChangeGrandchildName(value))
    fun changeGrandchildAmount(value: String) =
        dispatch(SalaryAllocationDetailAction.ChangeGrandchildAmount(value))
    fun changeGrandchildMemo(value: String) =
        dispatch(SalaryAllocationDetailAction.ChangeGrandchildMemo(value))
    fun saveGrandchild() = dispatch(SalaryAllocationDetailAction.SaveGrandchild)
    fun deleteGrandchild() = dispatch(SalaryAllocationDetailAction.DeleteGrandchild)

    fun close() {
        if (isClosed) return
        isClosed = true
        holder.close()
        storeJob.cancel()
    }

    private fun dispatch(action: SalaryAllocationDetailAction) {
        if (!isClosed) holder.onAction(action)
    }
}

/** Swift-facing P10 adapter, including explicit overwrite/keep conflict choices. */
class IosFixedCostCheckStore internal constructor(
    private val holder: FixedCostCheckStateHolder,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private var isClosed = false

    val currentState: FixedCostCheckUiState
        get() = holder.state.value

    init { holder.start() }

    fun observe(observer: (FixedCostCheckUiState) -> Unit): IosObservation = IosObservation(
        scope.launch { holder.state.collect(observer) },
    )

    fun conflictRows(): List<IosFixedCostConflictRow> = currentState.conflictPreview?.rows.orEmpty().map { row ->
        IosFixedCostConflictRow(
            id = row.sourceItem.id,
            payerMemberOrder = row.sourceItem.payerMemberOrder,
            name = row.sourceItem.name,
            amountWon = row.sourceItem.amountWon,
            hasExistingItem = row.existingItem != null,
        )
    }

    fun toggleSelectionMode() = dispatch(FixedCostCheckAction.ToggleSelectionMode)
    fun toggleItem(id: Long) = dispatch(FixedCostCheckAction.ToggleItem(id))
    fun toggleCommonPayer() = dispatch(FixedCostCheckAction.TogglePayer(null))
    fun toggleMemberPayer(memberOrder: Int) = dispatch(FixedCostCheckAction.TogglePayer(memberOrder))
    fun toggleAll() = dispatch(FixedCostCheckAction.ToggleAll)
    fun openEditor(item: FixedCostCheckItem) = dispatch(FixedCostCheckAction.OpenEditor(item))
    fun openNewEditor() = dispatch(FixedCostCheckAction.OpenEditor())
    fun closeEditor() = dispatch(FixedCostCheckAction.CloseEditor)
    fun selectCommonPayer() = dispatch(FixedCostCheckAction.ChangePayer(null))
    fun selectMemberPayer(memberOrder: Int) = dispatch(FixedCostCheckAction.ChangePayer(memberOrder))
    fun changeName(value: String) = dispatch(FixedCostCheckAction.ChangeName(value))
    fun changeAmount(value: String) = dispatch(FixedCostCheckAction.ChangeAmount(value))
    fun save() = dispatch(FixedCostCheckAction.Save)
    fun delete() = dispatch(FixedCostCheckAction.Delete)
    fun requestApply() = dispatch(FixedCostCheckAction.RequestApply)
    fun overwriteExisting() = dispatch(FixedCostCheckAction.ConfirmApply(ExistingPlanPolicy.Overwrite))
    fun keepExisting() = dispatch(FixedCostCheckAction.ConfirmApply(ExistingPlanPolicy.KeepExisting))
    fun dismissConflict() = dispatch(FixedCostCheckAction.DismissConflict)
    fun clearMessage() = dispatch(FixedCostCheckAction.ClearMessage)

    fun close() {
        if (isClosed) return
        isClosed = true
        holder.close()
        storeJob.cancel()
    }

    private fun dispatch(action: FixedCostCheckAction) {
        if (!isClosed) holder.onAction(action)
    }
}

data class IosFixedCostConflictRow(
    val id: Long,
    val payerMemberOrder: Int?,
    val name: String,
    val amountWon: Long?,
    val hasExistingItem: Boolean,
)

data class IosGoalEditState(
    val year: Int,
    val targetInput: String,
    val inputError: String? = null,
    val persistenceError: String? = null,
    val isSaving: Boolean = false,
    val didSave: Boolean = false,
)

/** Edits one year's savings goal without overwriting setup or other years. */
class IosGoalEditStore internal constructor(
    private val initialSetup: LedgerSetup,
    private val year: Int,
    private val repository: HomeRepository,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(
        IosGoalEditState(
            year = year,
            targetInput = initialSetup.savingsTargetFor(year)?.toString().orEmpty(),
        ),
    )
    private var isClosed = false

    val currentState: IosGoalEditState
        get() = mutableState.value

    fun observe(observer: (IosGoalEditState) -> Unit): IosObservation = IosObservation(
        scope.launch { mutableState.collect(observer) },
    )

    fun changeTarget(value: String) {
        if (isClosed || mutableState.value.isSaving) return
        mutableState.value = mutableState.value.copy(
            targetInput = value,
            inputError = null,
            persistenceError = null,
            didSave = false,
        )
    }

    fun save() {
        if (isClosed || mutableState.value.isSaving) return
        val parsed = parseOptionalWon(mutableState.value.targetInput)
        if (parsed is WonParseResult.Invalid) {
            mutableState.value = mutableState.value.copy(
                inputError = "0원 이상의 금액을 숫자로 입력해 주세요",
                persistenceError = null,
                didSave = false,
            )
            return
        }
        val target = (parsed as WonParseResult.Valid).value
        mutableState.value = mutableState.value.copy(
            inputError = null,
            persistenceError = null,
            isSaving = true,
            didSave = false,
        )
        scope.launch {
            try {
                repository.updateAnnualSavingsTarget(year, target)
                mutableState.value = mutableState.value.copy(isSaving = false, didSave = true)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    isSaving = false,
                    persistenceError = "목표를 저장하지 못했어요. 다시 시도해 주세요",
                )
            }
        }
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        storeJob.cancel()
    }
}

/** Keeps native record-tab deep links expressed through typed shared actions. */
class IosRecordsStore internal constructor(
    private val holder: RecordsStateHolder,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private var selectionJob: Job? = null
    private var isClosed = false

    val currentState: RecordsUiState
        get() = holder.state.value

    init {
        holder.start()
    }

    fun observe(observer: (RecordsUiState) -> Unit): IosObservation = IosObservation(
        scope.launch { holder.state.collect(observer) },
    )

    fun applySelection(
        year: Int,
        month: Int,
        categoryId: String?,
        overspentOnly: Boolean,
    ) {
        if (isClosed || year !in YearMonthKey.MIN_YEAR..YearMonthKey.MAX_YEAR || month !in 1..12) return
        val selectedMonth = YearMonthKey(year, month)
        holder.onAction(RecordsAction.SelectMonth(selectedMonth))
        selectionJob?.cancel()
        selectionJob = scope.launch {
            holder.state.first {
                it.month == selectedMonth &&
                    !it.isLoading &&
                    (categoryId == null || it.categories.any { category -> category.id == categoryId })
            }
            if (!isClosed) {
                holder.onAction(
                    RecordsAction.FiltersApplied(
                        ExpenseFilters(
                            categoryIds = categoryId?.let(::setOf).orEmpty(),
                            overspentOnly = overspentOnly,
                        ),
                    ),
                )
            }
        }
    }

    fun restoreSelection(
        year: Int,
        month: Int,
        categoryIds: List<String>,
        overspentOnly: Boolean,
        oldestFirst: Boolean,
    ) {
        if (isClosed || year !in YearMonthKey.MIN_YEAR..YearMonthKey.MAX_YEAR || month !in 1..12) return
        val selectedMonth = YearMonthKey(year, month)
        holder.onAction(RecordsAction.SelectMonth(selectedMonth))
        selectionJob?.cancel()
        selectionJob = scope.launch {
            holder.state.first { it.month == selectedMonth && !it.isLoading }
            if (!isClosed) applyFilters(categoryIds, overspentOnly, oldestFirst)
        }
    }

    fun previousMonth() = dispatch(RecordsAction.PreviousMonth)
    fun nextMonth() = dispatch(RecordsAction.NextMonth)
    fun retry() = dispatch(RecordsAction.Retry)
    fun toggleCategory(categoryId: String) = dispatch(RecordsAction.CategoryToggled(categoryId))
    fun clearCategories() = dispatch(RecordsAction.ClearCategories)
    fun toggleOverspentOnly() = dispatch(RecordsAction.OverspentOnlyToggled)
    fun toggleSort() = dispatch(RecordsAction.SortToggled)
    fun clearFilters() = dispatch(RecordsAction.ClearFilters)

    fun applyFilters(categoryIds: List<String>, overspentOnly: Boolean, oldestFirst: Boolean) = dispatch(
        RecordsAction.FiltersApplied(
            ExpenseFilters(
                categoryIds = categoryIds.toSet(),
                overspentOnly = overspentOnly,
                sort = if (oldestFirst) ExpenseSort.Oldest else ExpenseSort.Latest,
            ),
        ),
    )

    fun close() {
        if (isClosed) return
        isClosed = true
        selectionJob?.cancel()
        holder.close()
        storeJob.cancel()
    }

    private fun dispatch(action: RecordsAction) {
        if (!isClosed) holder.onAction(action)
    }
}

/** Swift-facing P12 adapter that keeps sealed editor actions in Kotlin. */
class IosExpenseEditorStore internal constructor(
    private val holder: ExpenseEditorStateHolder,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private var isClosed = false

    val currentState: ExpenseEditorUiState get() = holder.state.value

    init { holder.start() }

    fun observe(observer: (ExpenseEditorUiState) -> Unit): IosObservation = IosObservation(
        scope.launch { holder.state.collect(observer) },
    )

    fun changeAmount(value: String) = dispatch(ExpenseEditorAction.AmountChanged(value))
    fun selectCategory(categoryId: String) = dispatch(ExpenseEditorAction.CategorySelected(categoryId))
    fun changeAttributionMonth(value: String) = dispatch(ExpenseEditorAction.AttributionMonthChanged(value))
    fun changeActualDate(value: String) = dispatch(ExpenseEditorAction.ActualDateChanged(value))
    fun useSuggestedActualDate() = dispatch(ExpenseEditorAction.UseSuggestedActualDate)
    fun changeDetail(value: String) = dispatch(ExpenseEditorAction.DetailChanged(value))
    fun changeOverspent(value: Boolean) = dispatch(ExpenseEditorAction.OverspentChanged(value))
    fun save() = dispatch(ExpenseEditorAction.Save)
    fun retryPersistence() = dispatch(ExpenseEditorAction.RetryPersistence)
    fun dismissPersistenceError() = dispatch(ExpenseEditorAction.DismissPersistenceError)
    fun resetForm() = dispatch(ExpenseEditorAction.ResetForm)
    fun requestDelete() = dispatch(ExpenseEditorAction.RequestDelete)
    fun dismissDelete() = dispatch(ExpenseEditorAction.DismissDelete)
    fun confirmDelete() = dispatch(ExpenseEditorAction.ConfirmDelete)
    fun requestBack() = dispatch(ExpenseEditorAction.RequestBack)
    fun dismissDiscard() = dispatch(ExpenseEditorAction.DismissDiscard)
    fun confirmDiscard() = dispatch(ExpenseEditorAction.ConfirmDiscard)
    fun restoreDraft(
        amount: String,
        categoryId: String?,
        attributionMonth: String,
        actualDate: String,
        detail: String,
        overspent: Boolean,
    ) = dispatch(
        ExpenseEditorAction.RestoreDraft(
            ExpenseEditorDraftSnapshot(amount, categoryId, attributionMonth, actualDate, detail, overspent),
        ),
    )

    fun close() {
        if (isClosed) return
        isClosed = true
        holder.close()
        storeJob.cancel()
    }

    private fun dispatch(action: ExpenseEditorAction) {
        if (!isClosed) holder.onAction(action)
    }
}

/** Swift-facing P13 adapter that owns the native asset-dashboard holder lifecycle. */
class IosAssetsStore internal constructor(
    private val holder: AssetsStateHolder,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private var isClosed = false

    val currentState: AssetsUiState get() = holder.state.value

    fun observe(observer: (AssetsUiState) -> Unit): IosObservation = IosObservation(
        scope.launch { holder.state.collect(observer) },
    )

    fun previousMonth() = dispatch(AssetsAction.PreviousMonth)
    fun nextMonth() = dispatch(AssetsAction.NextMonth)
    fun toggleSort() = dispatch(AssetsAction.ToggleSort)
    fun selectMonth(year: Int, month: Int) {
        if (year in YearMonthKey.MIN_YEAR..YearMonthKey.MAX_YEAR && month in 1..12) {
            dispatch(AssetsAction.SelectMonth(YearMonthKey(year, month)))
        }
    }
    fun retry() = dispatch(AssetsAction.Retry)

    fun close() {
        if (isClosed) return
        isClosed = true
        holder.close()
        storeJob.cancel()
    }

    private fun dispatch(action: AssetsAction) {
        if (!isClosed) holder.onAction(action)
    }
}

/** Swift-facing P14 adapter. */
class IosAssetDetailStore internal constructor(
    private val holder: AssetDetailStateHolder,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private var isClosed = false

    val currentState: AssetDetailUiState get() = holder.state.value
    fun observe(observer: (AssetDetailUiState) -> Unit): IosObservation = IosObservation(
        scope.launch { holder.state.collect(observer) },
    )
    fun previousMonth() = dispatch(AssetDetailAction.PreviousMonth)
    fun nextMonth() = dispatch(AssetDetailAction.NextMonth)
    fun selectMonth(year: Int, month: Int) {
        if (year in YearMonthKey.MIN_YEAR..YearMonthKey.MAX_YEAR && month in 1..12) {
            dispatch(AssetDetailAction.SelectMonth(YearMonthKey(year, month)))
        }
    }
    fun deleteAsset() = dispatch(AssetDetailAction.DeleteAsset)
    fun deleteSelectedValuation() = dispatch(AssetDetailAction.DeleteSelectedValuation)
    fun deleteGrowthRule() = dispatch(AssetDetailAction.DeleteGrowthRule)
    fun retryMutation() = dispatch(AssetDetailAction.RetryMutation)
    fun dismissMutationError() = dispatch(AssetDetailAction.DismissMutationError)
    fun retry() = dispatch(AssetDetailAction.Retry)
    fun close() {
        if (isClosed) return
        isClosed = true
        holder.close()
        storeJob.cancel()
    }
    private fun dispatch(action: AssetDetailAction) {
        if (!isClosed) holder.onAction(action)
    }
}

/** Swift-facing shared editor for P13/P14/P15. */
class IosAssetEditorStore internal constructor(
    private val holder: AssetEditorStateHolder,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private var isClosed = false

    val currentState: AssetEditorUiState get() = holder.state.value
    fun observe(observer: (AssetEditorUiState) -> Unit): IosObservation = IosObservation(
        scope.launch { holder.state.collect(observer) },
    )
    fun selectMode(mode: AssetEditorMode) = dispatch(AssetEditorAction.SelectMode(mode))
    fun changeName(value: String) = dispatch(AssetEditorAction.ChangeName(value))
    fun changeType(value: AssetType) = dispatch(AssetEditorAction.ChangeType(value))
    fun selectCommonOwner() = dispatch(AssetEditorAction.ChangeOwner(null))
    fun selectOwner(memberOrder: Int) = dispatch(AssetEditorAction.ChangeOwner(memberOrder))
    fun changeMemo(value: String) = dispatch(AssetEditorAction.ChangeMemo(value))
    fun changeKind(value: AssetKind) = dispatch(AssetEditorAction.ChangeKind(value))
    fun changeMonth(year: Int, month: Int) {
        if (year in YearMonthKey.MIN_YEAR..YearMonthKey.MAX_YEAR && month in 1..12) {
            dispatch(AssetEditorAction.ChangeMonth(YearMonthKey(year, month)))
        }
    }
    fun changeAmount(value: String) = dispatch(AssetEditorAction.ChangeAmount(value))
    fun changeDuration(value: String) = dispatch(AssetEditorAction.ChangeDuration(value))
    fun changeBaseAmount(value: String) = dispatch(AssetEditorAction.ChangeBaseAmount(value))
    fun changeMonthlyIncrease(value: String) = dispatch(AssetEditorAction.ChangeMonthlyIncrease(value))
    fun togglePreview() = dispatch(AssetEditorAction.TogglePreview)
    fun restoreDraft(
        selectedMode: AssetEditorMode,
        name: String,
        type: AssetType,
        ownerMemberOrder: Int?,
        memo: String,
        kind: AssetKind,
        year: Int,
        month: Int,
        amount: String,
        duration: String,
        baseAmount: String,
        monthlyIncrease: String,
    ) {
        if (year !in YearMonthKey.MIN_YEAR..YearMonthKey.MAX_YEAR || month !in 1..12) return
        dispatch(
            AssetEditorAction.RestoreDraft(
                kr.jm.moalog.feature.assets.presentation.AssetEditorDraftSnapshot(
                    selectedMode, name, type, ownerMemberOrder, memo, kind, YearMonthKey(year, month),
                    amount, duration, baseAmount, monthlyIncrease,
                ),
            ),
        )
    }
    fun save() = dispatch(AssetEditorAction.Save)
    fun retry() = dispatch(AssetEditorAction.Retry)
    fun close() {
        if (isClosed) return
        isClosed = true
        holder.close()
        storeJob.cancel()
    }
    private fun dispatch(action: AssetEditorAction) {
        if (!isClosed) holder.onAction(action)
    }
}

/** Swift-facing P15 adapter. */
class IosPurposeAccountsStore internal constructor(
    private val holder: PurposeAccountsStateHolder,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private var isClosed = false

    val currentState: PurposeAccountsUiState get() = holder.state.value
    fun observe(observer: (PurposeAccountsUiState) -> Unit): IosObservation = IosObservation(
        scope.launch { holder.state.collect(observer) },
    )
    fun previousMonth() = dispatch(PurposeAccountsAction.PreviousMonth)
    fun nextMonth() = dispatch(PurposeAccountsAction.NextMonth)
    fun selectMonth(year: Int, month: Int) {
        if (isClosed || year !in YearMonthKey.MIN_YEAR..YearMonthKey.MAX_YEAR || month !in 1..12) return
        dispatch(PurposeAccountsAction.SelectMonth(YearMonthKey(year, month)))
    }
    fun retry() = dispatch(PurposeAccountsAction.Retry)
    fun close() {
        if (isClosed) return
        isClosed = true
        holder.close()
        storeJob.cancel()
    }
    private fun dispatch(action: PurposeAccountsAction) {
        if (!isClosed) holder.onAction(action)
    }
}

/** Swift-facing monthly-plan store that keeps sealed [PlanAction] values in Kotlin. */
class IosPlanStore internal constructor(
    private val holder: PlanStateHolder,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private var isClosed = false

    val currentState: PlanUiState
        get() = holder.state.value

    init {
        holder.start()
    }

    fun observe(observer: (PlanUiState) -> Unit): IosObservation = IosObservation(
        scope.launch { holder.state.collect(observer) },
    )

    fun previousMonth() = dispatch(PlanAction.PreviousMonth)

    fun nextMonth() = dispatch(PlanAction.NextMonth)

    fun selectMonth(year: Int, month: Int) {
        if (!isClosed && year in YearMonthKey.MIN_YEAR..YearMonthKey.MAX_YEAR && month in 1..12) {
            holder.onAction(PlanAction.SelectMonth(YearMonthKey(year, month)))
        }
    }

    fun selectTab(tab: PlanTab) = dispatch(PlanAction.SelectTab(tab))

    fun selectMonthAndTab(year: Int, month: Int, tab: PlanTab) {
        if (!isClosed && year in YearMonthKey.MIN_YEAR..YearMonthKey.MAX_YEAR && month in 1..12) {
            holder.onAction(PlanAction.SelectMonth(YearMonthKey(year, month)))
            holder.onAction(PlanAction.SelectTab(tab))
        }
    }

    fun retry() = dispatch(PlanAction.Retry)

    fun close() {
        if (isClosed) return
        isClosed = true
        holder.close()
        storeJob.cancel()
    }

    private fun dispatch(action: PlanAction) {
        if (!isClosed) holder.onAction(action)
    }
}

/** Swift-facing plan editor that keeps sealed [PlanEditorAction] values in Kotlin. */
class IosPlanEditorStore internal constructor(
    private val holder: PlanEditorStateHolder,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private var isClosed = false

    val currentState: PlanEditorUiState
        get() = holder.state.value

    init {
        holder.start()
    }

    fun observe(observer: (PlanEditorUiState) -> Unit): IosObservation = IosObservation(
        scope.launch { holder.state.collect(observer) },
    )

    fun selectType(type: PlanItemType) = dispatch(PlanEditorAction.TypeChanged(type))
    fun clearCatalog() = dispatch(PlanEditorAction.CatalogSelected(null))
    fun selectCatalog(catalogId: Long) = dispatch(PlanEditorAction.CatalogSelected(catalogId))
    fun restoreDraft(
        type: PlanItemType,
        month: String,
        name: String,
        amount: String,
        category: String,
        status: PlanItemStatus,
        ownerMemberOrder: Int?,
        memo: String,
        includePurposeAccount: Boolean,
        includeNetSavings: Boolean,
        selectedCatalogId: Long?,
    ) = dispatch(
        PlanEditorAction.RestoreDraft(
            PlanEditorDraftSnapshot(
                type = type,
                month = month,
                name = name,
                amount = amount,
                category = category,
                status = status,
                ownerMemberOrder = ownerMemberOrder,
                memo = memo,
                includePurposeAccount = includePurposeAccount,
                includeNetSavings = includeNetSavings,
                errors = PlanEditorErrors(),
                showDiscardConfirmation = false,
                hasUnsavedChanges = true,
                selectedCatalogId = selectedCatalogId,
            ),
        ),
    )
    fun previousMonth() = dispatch(PlanEditorAction.PreviousMonth)
    fun nextMonth() = dispatch(PlanEditorAction.NextMonth)
    fun changeName(value: String) = dispatch(PlanEditorAction.NameChanged(value))
    fun changeAmount(value: String) = dispatch(PlanEditorAction.AmountChanged(value))
    fun addAmount(won: Long) = dispatch(PlanEditorAction.AddAmount(won))
    fun resetAmount() = dispatch(PlanEditorAction.ResetAmount)
    fun changeCategory(value: String) = dispatch(PlanEditorAction.CategoryChanged(value))
    fun changeStatus(value: PlanItemStatus) = dispatch(PlanEditorAction.StatusChanged(value))
    fun selectCommonOwner() = dispatch(PlanEditorAction.OwnerChanged(null))
    fun selectOwner(memberOrder: Int) = dispatch(PlanEditorAction.OwnerChanged(memberOrder))
    fun changeMemo(value: String) = dispatch(PlanEditorAction.MemoChanged(value))
    fun changePurposeAccount(value: Boolean) = dispatch(PlanEditorAction.PurposeChanged(value))
    fun changeNetSavings(value: Boolean) = dispatch(PlanEditorAction.NetChanged(value))
    fun save() = dispatch(PlanEditorAction.Save)
    fun saveAndApply() = dispatch(PlanEditorAction.SaveAndApply)
    fun retryLoad() = dispatch(PlanEditorAction.RetryLoad)
    fun requestBack() = dispatch(PlanEditorAction.RequestBack)
    fun dismissDiscard() = dispatch(PlanEditorAction.DismissDiscard)
    fun confirmDiscard() = dispatch(PlanEditorAction.ConfirmDiscard)
    fun requestDelete() = dispatch(PlanEditorAction.RequestDelete)
    fun dismissDelete() = dispatch(PlanEditorAction.DismissDelete)
    fun confirmDelete() = dispatch(PlanEditorAction.ConfirmDelete)

    fun close() {
        if (isClosed) return
        isClosed = true
        holder.close()
        storeJob.cancel()
    }

    private fun dispatch(action: PlanEditorAction) {
        if (!isClosed) holder.onAction(action)
    }
}

/** Swift-facing multi-month apply store that keeps sealed actions in Kotlin. */
class IosMultiMonthApplyStore internal constructor(
    private val holder: MultiMonthApplyStateHolder,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private var isClosed = false

    val currentState: MultiMonthApplyUiState
        get() = holder.state.value

    init {
        holder.start()
    }

    fun observe(observer: (MultiMonthApplyUiState) -> Unit): IosObservation = IosObservation(
        scope.launch { holder.state.collect(observer) },
    )

    fun previousYear() = dispatch(MultiMonthApplyAction.PreviousYear)
    fun nextYear() = dispatch(MultiMonthApplyAction.NextYear)

    fun toggleMonth(year: Int, month: Int) {
        if (!isClosed && year in YearMonthKey.MIN_YEAR..YearMonthKey.MAX_YEAR && month in 1..12) {
            holder.onAction(MultiMonthApplyAction.ToggleMonth(YearMonthKey(year, month)))
        }
    }

    fun selectDisplayedYear() = dispatch(MultiMonthApplyAction.SelectDisplayedYear)
    fun clearSelection() = dispatch(MultiMonthApplyAction.ClearSelection)
    fun continueToPreview() = dispatch(MultiMonthApplyAction.Continue)
    fun previousStep() = dispatch(MultiMonthApplyAction.PreviousStep)
    fun keepExisting() = dispatch(MultiMonthApplyAction.SelectPolicy(ExistingPlanPolicy.KeepExisting))
    fun overwrite() = dispatch(MultiMonthApplyAction.SelectPolicy(ExistingPlanPolicy.Overwrite))
    fun requestApply() = dispatch(MultiMonthApplyAction.RequestApply)
    fun dismissApply() = dispatch(MultiMonthApplyAction.DismissApply)
    fun confirmApply() = dispatch(MultiMonthApplyAction.ConfirmApply)
    fun retry() = dispatch(MultiMonthApplyAction.Retry)

    fun draftSnapshot(): MultiMonthApplyDraftSnapshot = currentState.toDraftSnapshot()

    fun restoreDraft(
        displayedYear: Int,
        selectedMonths: List<YearMonthKey>,
        overwrite: Boolean,
        previewRequested: Boolean,
    ) = dispatch(
        MultiMonthApplyAction.RestoreDraft(
            MultiMonthApplyDraftSnapshot(
                displayedYear = displayedYear,
                selectedMonths = selectedMonths,
                policy = if (overwrite) ExistingPlanPolicy.Overwrite else ExistingPlanPolicy.KeepExisting,
                previewRequested = previewRequested,
            ),
        ),
    )

    fun resultSummary(): IosMultiMonthApplyResultSummary? =
        currentState.result?.toIosMultiMonthApplyResultSummary()

    fun close() {
        if (isClosed) return
        isClosed = true
        holder.close()
        storeJob.cancel()
    }

    private fun dispatch(action: MultiMonthApplyAction) {
        if (!isClosed) holder.onAction(action)
    }
}

data class IosMultiMonthApplyResultSummary(
    val newlyInsertedItemCount: Int,
    val overwrittenItemCount: Int,
    val keptItemCount: Int,
    val allExistingKept: Boolean,
)

internal fun kr.jm.moalog.core.database.PlanCopyResult.toIosMultiMonthApplyResultSummary() =
    IosMultiMonthApplyResultSummary(
        newlyInsertedItemCount = insertedItemCount,
        overwrittenItemCount = overwrittenItemCount,
        keptItemCount = skippedConflictCount,
        allExistingKept = insertedItemCount == 0 && overwrittenItemCount == 0 && skippedConflictCount > 0,
    )

/** Owns the setup, annual-plan, and current-month observers used by native SwiftUI home. */
class IosHomeStore internal constructor(
    initialSetup: LedgerSetup,
    private val setupRepository: SetupRepository,
    private val planRepository: PlanRepository,
    private val recordsStateHolder: RecordsStateHolder,
) {
    private val storeJob = SupervisorJob()
    private val scope = CoroutineScope(storeJob + Dispatchers.Main.immediate)
    private var setup = initialSetup
    private var selectedYear = initialSetup.baseYear
    private val currentMonthNumber = recordsStateHolder.state.value.month.month
    private var recordsState = recordsStateHolder.state.value
    private var annualPlan: List<PlanYearMonth> = emptyList()
    private var annualPlanLoading = true
    private var annualPlanLoadError: String? = null
    private var setupJob: Job? = null
    private var planJob: Job? = null
    private val mutableState = MutableStateFlow(buildState())
    private var isClosed = false

    val currentState: HomeUiState
        get() = mutableState.value

    init {
        recordsStateHolder.start()
        scope.launch {
            recordsStateHolder.state.collect { records ->
                recordsState = records
                publish()
            }
        }
        observeSetup()
        selectYearInternal(initialSetup.baseYear, forceReload = true)
    }

    fun observe(observer: (HomeUiState) -> Unit): IosObservation {
        val job = scope.launch {
            mutableState.collect(observer)
        }
        return IosObservation(job)
    }

    /** Changes both the annual plan year and the monthly expense year shown on home. */
    fun selectYear(year: Int) {
        if (!isClosed && year in YearMonthKey.MIN_YEAR..YearMonthKey.MAX_YEAR) selectYearInternal(year)
    }

    fun retry() {
        if (isClosed) return
        recordsStateHolder.onAction(RecordsAction.Retry)
        observeSetup()
        observeAnnualPlan()
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        setupJob?.cancel()
        planJob?.cancel()
        recordsStateHolder.close()
        storeJob.cancel()
    }

    private fun selectYearInternal(year: Int, forceReload: Boolean = false) {
        if (!forceReload && year == selectedYear) return
        selectedYear = year
        annualPlan = emptyList()
        annualPlanLoading = true
        annualPlanLoadError = null
        recordsStateHolder.onAction(
            RecordsAction.SelectMonth(YearMonthKey(year, currentMonthNumber)),
        )
        publish()
        observeAnnualPlan()
    }

    private fun observeSetup() {
        setupJob?.cancel()
        setupJob = scope.launch {
            try {
                setupRepository.observeSetup().collect { updatedSetup ->
                    if (updatedSetup == null) return@collect
                    val previousBaseYear = setup.baseYear
                    val shouldFollowBaseYear = selectedYear == previousBaseYear &&
                        updatedSetup.baseYear != previousBaseYear
                    setup = updatedSetup
                    if (shouldFollowBaseYear) {
                        selectYearInternal(updatedSetup.baseYear)
                    } else {
                        publish()
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // The root store owns setup-load error presentation. Keep the
                // last valid setup here so an already-open home remains usable.
            }
        }
    }

    private fun observeAnnualPlan() {
        planJob?.cancel()
        val observedYear = selectedYear
        annualPlan = emptyList()
        annualPlanLoading = true
        annualPlanLoadError = null
        publish()
        planJob = scope.launch {
            try {
                planRepository.observeYear(observedYear).collect { snapshots ->
                    if (selectedYear != observedYear) return@collect
                    annualPlan = snapshots.filter { it.month.year == observedYear }
                    annualPlanLoading = false
                    annualPlanLoadError = null
                    publish()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (selectedYear == observedYear) {
                    annualPlanLoading = false
                    annualPlanLoadError = "연간 계획을 불러오지 못했어요. 다시 시도해 주세요"
                    publish()
                }
            }
        }
    }

    private fun publish() {
        if (!isClosed) mutableState.value = buildState()
    }

    private fun buildState(): HomeUiState = buildIosHomeState(
        setup = setup,
        selectedYear = selectedYear,
        currentMonthNumber = currentMonthNumber,
        annualPlan = annualPlan,
        annualPlanLoading = annualPlanLoading,
        annualPlanLoadError = annualPlanLoadError,
        records = recordsState,
    )
}

internal fun buildIosHomeState(
    setup: LedgerSetup,
    selectedYear: Int,
    currentMonthNumber: Int,
    annualPlan: List<PlanYearMonth>,
    annualPlanLoading: Boolean,
    annualPlanLoadError: String?,
    records: RecordsUiState,
): HomeUiState {
    val expectedMonth = YearMonthKey(selectedYear, currentMonthNumber)
    val matchingRecords = records.takeIf { it.month == expectedMonth }
    val annualSummary: HomeAnnualSummary = calculateHomeAnnualSummary(
        setup = setup,
        year = selectedYear,
        months = annualPlan.map { snapshot ->
            HomeAnnualMonthSnapshot(
                month = snapshot.month,
                items = snapshot.items,
                variableExpenses = snapshot.variableExpenses,
            )
        },
        isLoading = annualPlanLoading,
        loadError = annualPlanLoadError,
    )
    return setup.toHomeUiState(
        monthlySummary = HomeMonthlySummary(
            year = expectedMonth.year,
            month = expectedMonth.month,
            expenseTotalWon = matchingRecords?.fullMonthTotalWon ?: 0,
            overspentTotalWon = matchingRecords?.fullMonthOverspentWon ?: 0,
            overspentCount = matchingRecords?.records?.count { it.overspent } ?: 0,
            isLoading = matchingRecords?.isLoading ?: true,
            loadError = matchingRecords?.loadError,
        ),
        selectedYear = selectedYear,
        annualSummary = annualSummary,
    )
}
