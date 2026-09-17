package kr.jm.moalog.appshell

import androidx.navigation3.runtime.NavKey
import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.feature.plan.presentation.PlanTab
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kr.jm.moalog.feature.assets.presentation.AssetEditorMode
import kr.jm.moalog.feature.home.presentation.CompositionTab
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationTest {
    @Test
    fun salaryAllocationAndFixedCostCheckDelegateSystemBackToTheirCurrentMonthRoutes() {
        assertEquals(true, routeManagesSystemBack(MainDestination.SalaryAllocation(2026, 9)))
        assertEquals(true, routeManagesSystemBack(MainDestination.FixedCostCheck(2026, 9)))
        assertEquals(false, routeManagesSystemBack(MainDestination.SalaryAllocationDetail(7, 2026, 9)))
    }

    @Test
    fun startupDestinationFollowsDurableSetup() {
        assertEquals(RootDestination.Setup, destinationForSetup(null))
        assertEquals(
            RootDestination.Main,
            destinationForSetup(LedgerSetup("모아로그", listOf(LedgerMember("수아", 0), LedgerMember("종민", 1)), 2026, null)),
        )
    }

    @Test
    fun selectingTabKeepsSingleRootAndKeysRoundTrip() {
        val stack = mutableListOf<NavKey>(MainDestination.Home)
        stack.selectMainTab(MainDestination.Assets)
        assertEquals(2, stack.size)
        assertEquals(listOf<NavKey>(MainDestination.Home, MainDestination.Assets), stack)

        val encoded = Json.encodeToString<MainDestination>(MainDestination.Records)
        assertEquals(MainDestination.Records, Json.decodeFromString<MainDestination>(encoded))
    }

    @Test
    fun editorKeyKeepsRecordAndReturnMonthAndBackStackKeepsRecordsRoot() {
        val stack = mutableListOf<NavKey>(MainDestination.Records)
        stack.openExpenseEditor(recordId = 42, month = YearMonthKey(2027, 3))
        val editor = stack.last() as MainDestination.ExpenseEditor
        assertEquals(42, editor.recordId)
        assertEquals(YearMonthKey(2027, 3), editor.month)
        assertEquals(MainDestination.Records, mainTabFor(editor))
        assertEquals(editor, Json.decodeFromString<MainDestination>(Json.encodeToString<MainDestination>(editor)))

        stack.removeAt(stack.lastIndex)
        assertEquals(listOf<NavKey>(MainDestination.Records), stack)
    }

    @Test
    fun planEditorKeyKeepsTypeMonthAndPlanTab() {
        val stack = mutableListOf<NavKey>(MainDestination.Plan)
        stack.openPlanEditor(7, PlanItemType.Savings, YearMonthKey(2028, 2))
        val editor = stack.last() as MainDestination.PlanEditor
        assertEquals(7, editor.itemId)
        assertEquals(PlanItemType.Savings, editor.type)
        assertEquals(YearMonthKey(2028, 2), editor.month)
        assertEquals(MainDestination.Plan, mainTabFor(editor))
        assertEquals(editor, Json.decodeFromString<MainDestination>(Json.encodeToString<MainDestination>(editor)))
    }

    @Test
    fun annualPlanKeyKeepsReturnMonthAndPlanTab() {
        val stack = mutableListOf<NavKey>(MainDestination.Plan)
        stack.openAnnualPlan(YearMonthKey(2028, 7), PlanTab.Savings)
        val annual = stack.last() as MainDestination.AnnualPlan
        assertEquals(2028, annual.year)
        assertEquals(YearMonthKey(2028, 7), annual.month)
        assertEquals(PlanTab.Savings, annual.tab)
        assertEquals(MainDestination.Plan, mainTabFor(annual))
        assertEquals(annual, Json.decodeFromString<MainDestination>(Json.encodeToString<MainDestination>(annual)))
    }

    @Test
    fun multiMonthApplyKeyKeepsSourceContext() {
        val stack = mutableListOf<NavKey>(MainDestination.Plan)
        stack.openMultiMonthApply(YearMonthKey(2028, 7), PlanItemType.FixedExpense, 41L)
        val apply = stack.last() as MainDestination.MultiMonthApply
        assertEquals(YearMonthKey(2028, 7), apply.month)
        assertEquals(PlanItemType.FixedExpense, apply.type)
        assertEquals(41L, apply.sourceItemId)
        assertEquals(MainDestination.Plan, mainTabFor(apply))
        assertEquals(apply, Json.decodeFromString<MainDestination>(Json.encodeToString<MainDestination>(apply)))
    }

    @Test
    fun salaryAllocationDetailKeepsTheMonthSelectedOnTheAllocationScreen() {
        val stack = mutableListOf<NavKey>(MainDestination.Plan)
        stack.openSalaryAllocationDetail(17L, YearMonthKey(2027, 1))
        val detail = stack.last() as MainDestination.SalaryAllocationDetail
        assertEquals(17L, detail.categoryId)
        assertEquals(YearMonthKey(2027, 1), detail.key)
        assertEquals(detail, Json.decodeFromString<MainDestination>(Json.encodeToString<MainDestination>(detail)))
    }

    @Test
    fun compositionAnalysisKeyKeepsPeriodTabAndHomeOwnership() {
        val stack = mutableListOf<NavKey>(MainDestination.Home)
        stack.openCompositionAnalysis(YearMonthKey(2026, 5), CompositionTab.Savings)
        val analysis = stack.last() as MainDestination.CompositionAnalysis

        assertEquals(YearMonthKey(2026, 5), analysis.month)
        assertEquals(CompositionTab.Savings, analysis.tab)
        assertEquals(MainDestination.Home, mainTabFor(analysis))
        assertEquals(analysis, Json.decodeFromString<MainDestination>(Json.encodeToString<MainDestination>(analysis)))
    }

    @Test
    fun assetDetailAndEditorKeepAssetMonthModeAndAssetsTab() {
        val stack = mutableListOf<NavKey>(MainDestination.Assets)
        stack.openPurposeAccounts(YearMonthKey(2026, 8))
        val purposeAccounts = stack.last() as MainDestination.PurposeAccounts
        assertEquals(YearMonthKey(2026, 8), purposeAccounts.month)
        assertEquals(MainDestination.Assets, mainTabFor(purposeAccounts))
        assertEquals(purposeAccounts, Json.decodeFromString<MainDestination>(Json.encodeToString<MainDestination>(purposeAccounts)))

        stack.openAssetDetail(9L, YearMonthKey(2026, 8), purposeAccount = true)
        val detail = stack.last() as MainDestination.AssetDetail
        assertEquals(9L, detail.assetId)
        assertEquals(YearMonthKey(2026, 8), detail.month)
        assertEquals(true, detail.purposeAccount)
        assertEquals(MainDestination.Assets, mainTabFor(detail))
        assertEquals(detail, Json.decodeFromString<MainDestination>(Json.encodeToString<MainDestination>(detail)))

        stack.openAssetEditor(9L, detail.month, AssetEditorMode.GrowthRule, purposeAccount = true)
        val editor = stack.last() as MainDestination.AssetEditor
        assertEquals(AssetEditorMode.GrowthRule, editor.mode)
        assertEquals(true, editor.purposeAccount)
        assertEquals(MainDestination.Assets, mainTabFor(editor))
        assertEquals(editor, Json.decodeFromString<MainDestination>(Json.encodeToString<MainDestination>(editor)))
    }

    @Test
    fun maintenanceOverviewAndEditorKeepMonthAndMoreTab() {
        val stack = mutableListOf<NavKey>(MainDestination.More)
        stack.openMaintenanceOverview(YearMonthKey(2025, 12))
        val overview = stack.last() as MainDestination.MaintenanceOverview
        assertEquals(YearMonthKey(2025, 12), overview.month)
        assertEquals(MainDestination.More, mainTabFor(overview))
        assertEquals(overview, Json.decodeFromString<MainDestination>(Json.encodeToString<MainDestination>(overview)))

        stack.openMaintenanceEditor(YearMonthKey(2026, 1))
        val editor = stack.last() as MainDestination.MaintenanceEditor
        assertEquals(YearMonthKey(2026, 1), editor.month)
        assertEquals(MainDestination.More, mainTabFor(editor))
        assertEquals(editor, Json.decodeFromString<MainDestination>(Json.encodeToString<MainDestination>(editor)))
    }

    @Test
    fun stageFiveMoreDestinationsAreSerializableAndStayOnMoreTab() {
        val stack = mutableListOf<NavKey>(MainDestination.More)
        stack.openItemManagement()
        assertEquals(MainDestination.ItemManagement, stack.last())
        assertEquals(MainDestination.More, mainTabFor(MainDestination.ItemManagement))
        assertEquals(MainDestination.ItemManagement, Json.decodeFromString<MainDestination>(Json.encodeToString<MainDestination>(MainDestination.ItemManagement)))

        stack.openLedgerSettings()
        assertEquals(MainDestination.LedgerSettings, stack.last())
        assertEquals(MainDestination.More, mainTabFor(MainDestination.LedgerSettings))
        assertEquals(MainDestination.LedgerSettings, Json.decodeFromString<MainDestination>(Json.encodeToString<MainDestination>(MainDestination.LedgerSettings)))

        stack.openGuide()
        assertEquals(MainDestination.Guide, stack.last())
        assertEquals(MainDestination.More, mainTabFor(MainDestination.Guide))
        assertEquals(MainDestination.Guide, Json.decodeFromString<MainDestination>(Json.encodeToString<MainDestination>(MainDestination.Guide)))
    }
}
