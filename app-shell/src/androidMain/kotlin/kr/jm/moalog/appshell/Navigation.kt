package kr.jm.moalog.appshell

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.feature.home.presentation.CompositionTab

@Serializable
sealed interface RootDestination : NavKey {
    @Serializable data object Loading : RootDestination
    @Serializable data object Setup : RootDestination
    @Serializable data object Main : RootDestination
}

@Serializable
sealed interface MainDestination : NavKey {
    @Serializable data object Home : MainDestination
    @Serializable data object Plan : MainDestination
    @Serializable data object Records : MainDestination
    @Serializable data object Assets : MainDestination
    @Serializable data object More : MainDestination
    @Serializable data object ItemManagement : MainDestination
    @Serializable data object LedgerSettings : MainDestination
    @Serializable data object Guide : MainDestination
    @Serializable data class CompositionAnalysis(
        val year: Int,
        val monthNumber: Int,
        val tabName: String = CompositionTab.VariableExpense.name,
    ) : MainDestination {
        val month: YearMonthKey get() = YearMonthKey(year, monthNumber)
        val tab: CompositionTab get() = CompositionTab.valueOf(tabName)
    }
    @Serializable data class MaintenanceOverview(val year: Int, val monthNumber: Int) : MainDestination { val month: YearMonthKey get() = YearMonthKey(year, monthNumber) }
    @Serializable data class MaintenanceEditor(val year: Int, val monthNumber: Int) : MainDestination { val month: YearMonthKey get() = YearMonthKey(year, monthNumber) }
    @Serializable data class PurposeAccounts(val year: Int, val monthNumber: Int) : MainDestination { val month: YearMonthKey get() = YearMonthKey(year, monthNumber) }
    @Serializable data class AssetDetail(val assetId: Long, val year: Int, val monthNumber: Int, val purposeAccount: Boolean = false) : MainDestination { val month: YearMonthKey get() = YearMonthKey(year, monthNumber) }
    @Serializable data class AssetEditor(val assetId: Long?, val year: Int, val monthNumber: Int, val modeName: String, val purposeAccount: Boolean = false) : MainDestination {
        val month: YearMonthKey get() = YearMonthKey(year, monthNumber)
        val mode: kr.jm.moalog.feature.assets.presentation.AssetEditorMode get() = kr.jm.moalog.feature.assets.presentation.AssetEditorMode.valueOf(modeName)
    }
    @Serializable data class AnnualPlan(
        val year: Int,
        val returnMonth: Int,
        val returnTabName: String,
    ) : MainDestination {
        val month: YearMonthKey get() = YearMonthKey(year, returnMonth)
        val tab: kr.jm.moalog.feature.plan.presentation.PlanTab get() = kr.jm.moalog.feature.plan.presentation.PlanTab.valueOf(returnTabName)
    }
    @Serializable data class PlanEditor(
        val itemId: Long?,
        val typeName: String,
        val returnYear: Int,
        val returnMonth: Int,
    ) : MainDestination {
        val month: YearMonthKey get() = YearMonthKey(returnYear, returnMonth)
        val type: PlanItemType get() = PlanItemType.valueOf(typeName)
    }
    @Serializable data class MultiMonthApply(
        val sourceYear: Int,
        val sourceMonth: Int,
        val typeName: String,
        val sourceItemId: Long?,
    ) : MainDestination {
        val month: YearMonthKey get() = YearMonthKey(sourceYear, sourceMonth)
        val type: PlanItemType get() = PlanItemType.valueOf(typeName)
    }
    @Serializable data class SalaryAllocation(val year:Int,val month:Int):MainDestination { val key:YearMonthKey get()=YearMonthKey(year,month) }
    @Serializable data class SalaryAllocationDetail(val categoryId:Long,val year:Int,val month:Int):MainDestination { val key:YearMonthKey get()=YearMonthKey(year,month) }
    @Serializable data class FixedCostCheck(val year:Int,val month:Int):MainDestination { val key:YearMonthKey get()=YearMonthKey(year,month) }
    @Serializable data class ExpenseEditor(
        val recordId: Long?,
        val returnYear: Int,
        val returnMonth: Int,
    ) : MainDestination {
        val month: YearMonthKey get() = YearMonthKey(returnYear, returnMonth)
    }
}

val NavigationSavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(RootDestination.Loading::class, RootDestination.Loading.serializer())
            subclass(RootDestination.Setup::class, RootDestination.Setup.serializer())
            subclass(RootDestination.Main::class, RootDestination.Main.serializer())
            subclass(MainDestination.Home::class, MainDestination.Home.serializer())
            subclass(MainDestination.Plan::class, MainDestination.Plan.serializer())
            subclass(MainDestination.Records::class, MainDestination.Records.serializer())
            subclass(MainDestination.Assets::class, MainDestination.Assets.serializer())
            subclass(MainDestination.More::class, MainDestination.More.serializer())
            subclass(MainDestination.ItemManagement::class, MainDestination.ItemManagement.serializer())
            subclass(MainDestination.LedgerSettings::class, MainDestination.LedgerSettings.serializer())
            subclass(MainDestination.Guide::class, MainDestination.Guide.serializer())
            subclass(MainDestination.CompositionAnalysis::class, MainDestination.CompositionAnalysis.serializer())
            subclass(MainDestination.MaintenanceOverview::class, MainDestination.MaintenanceOverview.serializer())
            subclass(MainDestination.MaintenanceEditor::class, MainDestination.MaintenanceEditor.serializer())
            subclass(MainDestination.PurposeAccounts::class, MainDestination.PurposeAccounts.serializer())
            subclass(MainDestination.AssetDetail::class, MainDestination.AssetDetail.serializer())
            subclass(MainDestination.AssetEditor::class, MainDestination.AssetEditor.serializer())
            subclass(MainDestination.AnnualPlan::class, MainDestination.AnnualPlan.serializer())
            subclass(MainDestination.PlanEditor::class, MainDestination.PlanEditor.serializer())
            subclass(MainDestination.MultiMonthApply::class, MainDestination.MultiMonthApply.serializer())
            subclass(MainDestination.SalaryAllocation::class, MainDestination.SalaryAllocation.serializer())
            subclass(MainDestination.SalaryAllocationDetail::class, MainDestination.SalaryAllocationDetail.serializer())
            subclass(MainDestination.FixedCostCheck::class, MainDestination.FixedCostCheck.serializer())
            subclass(MainDestination.ExpenseEditor::class, MainDestination.ExpenseEditor.serializer())
        }
    }
}

fun destinationForSetup(setup: kr.jm.moalog.core.model.LedgerSetup?): RootDestination =
    if (setup == null) RootDestination.Setup else RootDestination.Main

fun MutableList<NavKey>.selectMainTab(destination: MainDestination) {
    clear()
    add(MainDestination.Home)
    if (destination != MainDestination.Home) add(destination)
}

fun mainTabFor(destination: MainDestination): MainDestination = when (destination) {
    is MainDestination.ExpenseEditor -> MainDestination.Records
    is MainDestination.CompositionAnalysis -> MainDestination.Home
    is MainDestination.PlanEditor -> MainDestination.Plan
    is MainDestination.MultiMonthApply -> MainDestination.Plan
    is MainDestination.AnnualPlan -> MainDestination.Plan
    is MainDestination.SalaryAllocation -> MainDestination.Plan
    is MainDestination.SalaryAllocationDetail -> MainDestination.Plan
    is MainDestination.FixedCostCheck -> MainDestination.Plan
    is MainDestination.PurposeAccounts, is MainDestination.AssetDetail, is MainDestination.AssetEditor -> MainDestination.Assets
    is MainDestination.MaintenanceOverview, is MainDestination.MaintenanceEditor,
    MainDestination.ItemManagement, MainDestination.LedgerSettings, MainDestination.Guide -> MainDestination.More
    else -> destination
}

internal fun routeManagesSystemBack(destination: NavKey?): Boolean = when (destination) {
    is MainDestination.ExpenseEditor,
    is MainDestination.PlanEditor,
    is MainDestination.MultiMonthApply,
    is MainDestination.SalaryAllocation,
    is MainDestination.FixedCostCheck -> true
    MainDestination.LedgerSettings -> true
    else -> false
}

fun MutableList<NavKey>.openPlanEditor(itemId: Long?, type: PlanItemType, month: YearMonthKey) {
    add(MainDestination.PlanEditor(itemId, type.name, month.year, month.month))
}

fun MutableList<NavKey>.openMultiMonthApply(month: YearMonthKey, type: PlanItemType, sourceItemId: Long?) {
    add(MainDestination.MultiMonthApply(month.year, month.month, type.name, sourceItemId))
}

fun MutableList<NavKey>.openAnnualPlan(month: YearMonthKey, tab: kr.jm.moalog.feature.plan.presentation.PlanTab) {
    add(MainDestination.AnnualPlan(month.year, month.month, tab.name))
}

fun MutableList<NavKey>.openExpenseEditor(recordId: Long?, month: YearMonthKey) {
    add(MainDestination.ExpenseEditor(recordId, month.year, month.month))
}
fun MutableList<NavKey>.openCompositionAnalysis(month: YearMonthKey, tab: CompositionTab = CompositionTab.VariableExpense) {
    add(MainDestination.CompositionAnalysis(month.year, month.month, tab.name))
}
fun MutableList<NavKey>.openSalaryAllocation(month:YearMonthKey){add(MainDestination.SalaryAllocation(month.year,month.month))}
fun MutableList<NavKey>.openSalaryAllocationDetail(categoryId:Long,month:YearMonthKey){add(MainDestination.SalaryAllocationDetail(categoryId,month.year,month.month))}
fun MutableList<NavKey>.openFixedCostCheck(month:YearMonthKey){add(MainDestination.FixedCostCheck(month.year,month.month))}
fun MutableList<NavKey>.openPurposeAccounts(month: YearMonthKey) { add(MainDestination.PurposeAccounts(month.year, month.month)) }
fun MutableList<NavKey>.openMaintenanceOverview(month: YearMonthKey) { add(MainDestination.MaintenanceOverview(month.year, month.month)) }
fun MutableList<NavKey>.openMaintenanceEditor(month: YearMonthKey) { add(MainDestination.MaintenanceEditor(month.year, month.month)) }
fun MutableList<NavKey>.openAssetDetail(assetId: Long, month: YearMonthKey, purposeAccount: Boolean = false) { add(MainDestination.AssetDetail(assetId, month.year, month.month, purposeAccount)) }
fun MutableList<NavKey>.openAssetEditor(assetId: Long?, month: YearMonthKey, mode: kr.jm.moalog.feature.assets.presentation.AssetEditorMode, purposeAccount: Boolean = false) { add(MainDestination.AssetEditor(assetId, month.year, month.month, mode.name, purposeAccount)) }
fun MutableList<NavKey>.openItemManagement() { add(MainDestination.ItemManagement) }
fun MutableList<NavKey>.openLedgerSettings() { add(MainDestination.LedgerSettings) }
fun MutableList<NavKey>.openGuide() { add(MainDestination.Guide) }
