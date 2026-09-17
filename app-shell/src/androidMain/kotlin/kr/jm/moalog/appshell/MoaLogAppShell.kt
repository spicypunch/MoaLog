package kr.jm.moalog.appshell

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import kr.jm.moalog.core.designsystem.MoaLogTheme
import kr.jm.moalog.core.designsystem.MoaLogColors
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.auth.AuthSessionStatus
import kr.jm.moalog.auth.MoaLogAuthSessionManager
import kr.jm.moalog.household.HouseholdSessionStatus
import kr.jm.moalog.household.MoaLogHouseholdSessionManager
import kr.jm.moalog.sync.MoaLogSyncManager
import kr.jm.moalog.feature.home.presentation.HomeRoute
import kr.jm.moalog.feature.home.presentation.CompositionAnalysisRoute
import kr.jm.moalog.feature.home.presentation.CompositionTab
import kr.jm.moalog.feature.plan.presentation.PlanEditorArgs
import kr.jm.moalog.feature.plan.presentation.PlanEditorRoute
import kr.jm.moalog.feature.plan.presentation.PlanRoute
import kr.jm.moalog.feature.plan.presentation.AnnualPlanArgs
import kr.jm.moalog.feature.plan.presentation.AnnualPlanRoute
import kr.jm.moalog.feature.plan.presentation.AnnualPlanSection
import kr.jm.moalog.feature.plan.presentation.PlanTab
import kr.jm.moalog.feature.plan.presentation.MultiMonthApplyArgs
import kr.jm.moalog.feature.plan.presentation.MultiMonthApplyRoute
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationArgs
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationRoute
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationDetailArgs
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationDetailRoute
import kr.jm.moalog.feature.plan.presentation.FixedCostCheckArgs
import kr.jm.moalog.feature.plan.presentation.FixedCostCheckRoute
import kr.jm.moalog.feature.records.presentation.ExpenseEditorArgs
import kr.jm.moalog.feature.records.presentation.ExpenseEditorRoute
import kr.jm.moalog.feature.records.presentation.RecordsRoute
import kr.jm.moalog.feature.records.presentation.RecordsAction
import kr.jm.moalog.feature.records.presentation.RecordsStateHolder
import kr.jm.moalog.feature.maintenance.presentation.MaintenanceEditorArgs
import kr.jm.moalog.feature.maintenance.presentation.MaintenanceEditorRoute
import kr.jm.moalog.feature.maintenance.presentation.MaintenanceOverviewArgs
import kr.jm.moalog.feature.maintenance.presentation.MaintenanceOverviewRoute
import kr.jm.moalog.feature.assets.presentation.AssetsRoute
import kr.jm.moalog.feature.assets.presentation.AssetDetailArgs
import kr.jm.moalog.feature.assets.presentation.AssetDetailRoute
import kr.jm.moalog.feature.assets.presentation.AssetEditorArgs
import kr.jm.moalog.feature.assets.presentation.AssetEditorMode
import kr.jm.moalog.feature.assets.presentation.AssetEditorRoute
import kr.jm.moalog.feature.assets.presentation.PurposeAccountsRoute
import kr.jm.moalog.feature.setup.presentation.SetupRoute
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import org.koin.compose.koinInject
import org.koin.core.module.Module
import org.koin.dsl.module

val appShellModule: Module = module {
    single { AppShellStateHolder(get(), get()) }
}

@Composable
fun MoaLogAppShell(
    stateHolder: AppShellStateHolder = koinInject(),
    authManager: MoaLogAuthSessionManager = koinInject(),
    householdManager: MoaLogHouseholdSessionManager = koinInject(),
    syncManager: MoaLogSyncManager = koinInject(),
) {
    val state by stateHolder.state.collectAsStateWithLifecycle()
    val authState by authManager.state.collectAsStateWithLifecycle()
    val householdState by householdManager.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { authManager.restore() }
    LaunchedEffect(authState.status) { householdManager.restore(authState.status) }
    LaunchedEffect(householdState.activeHousehold?.householdId) {
        if (householdState.status == HouseholdSessionStatus.READY) syncManager.synchronize()
    }
    LaunchedEffect(authState.status, householdState.status, householdState.activeHousehold?.householdId) {
        if (authState.status == AuthSessionStatus.SIGNED_IN &&
            householdState.status == HouseholdSessionStatus.READY
        ) {
            // 연결 직후 서버 설정을 로컬 DB에 반영하는 첫 값은 다시 업로드하지 않는다.
            // 이후 설정 화면에서 저장된 값만 서버 가계부 설정으로 전파한다.
            androidx.compose.runtime.snapshotFlow { (state as? AppShellUiState.Ready)?.setup }
                .filterNotNull()
                .drop(1)
                .collect { householdManager.updateFromLocal(it) }
        }
    }
    SyncLifecycleEffect(
        enabled = authState.status == AuthSessionStatus.SIGNED_IN &&
            householdState.status == HouseholdSessionStatus.READY,
        syncManager = syncManager,
        householdManager = householdManager,
    )

    MoaLogTheme {
        when {
            authState.status == AuthSessionStatus.RESTORING -> StartupScreen(false) { }
            authState.status != AuthSessionStatus.SIGNED_IN -> LoginScreen(authState, authManager)
            householdState.status == HouseholdSessionStatus.IDLE ||
                householdState.status == HouseholdSessionStatus.LOADING -> StartupScreen(false) { }
            householdState.status == HouseholdSessionStatus.NEEDS_CONNECTION ||
                householdState.status == HouseholdSessionStatus.FAILED -> {
                val setup = (state as? AppShellUiState.Ready)?.setup
                if (setup == null && householdState.availableHouseholds.isEmpty() &&
                    householdState.status != HouseholdSessionStatus.FAILED
                ) {
                    SetupRoute()
                } else {
                    HouseholdConnectionScreen(householdState, setup, householdManager, authManager)
                }
            }
            else -> LedgerRootNavigation(
                state = state,
                stateHolder = stateHolder,
                authManager = authManager,
                householdManager = householdManager,
                syncManager = syncManager,
            )
        }
    }
}

@Composable
private fun LedgerRootNavigation(
    state: AppShellUiState,
    stateHolder: AppShellStateHolder,
    authManager: MoaLogAuthSessionManager,
    householdManager: MoaLogHouseholdSessionManager,
    syncManager: MoaLogSyncManager,
) {
    val rootBackStack = rememberNavBackStack(
        configuration = NavigationSavedStateConfiguration,
        RootDestination.Loading,
    )
    val destination = when (state) {
        AppShellUiState.Loading, AppShellUiState.LoadFailed -> RootDestination.Loading
        AppShellUiState.NeedsSetup -> RootDestination.Setup
        is AppShellUiState.Ready -> RootDestination.Main
    }
    LaunchedEffect(destination) {
        if (rootBackStack.lastOrNull() != destination) {
            rootBackStack.clear()
            rootBackStack.add(destination)
        }
    }

    NavDisplay(
        backStack = rootBackStack,
        modifier = Modifier.fillMaxSize(),
        onBack = {},
        entryProvider = { key ->
            NavEntry(key) {
                when (key) {
                    RootDestination.Loading -> StartupScreen(
                        failed = state == AppShellUiState.LoadFailed,
                        onRetry = stateHolder::retry,
                    )
                    RootDestination.Setup -> SetupRoute()
                    RootDestination.Main -> {
                        val setup = (state as? AppShellUiState.Ready)?.setup
                        if (setup == null) {
                            StartupScreen(false, stateHolder::retry)
                        } else {
                            MainScreen(setup, authManager, householdManager, syncManager)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun StartupScreen(failed: Boolean, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!failed) {
            CircularProgressIndicator()
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("가계부를 불러오지 못했어요", style = MaterialTheme.typography.titleMedium)
                Button(onClick = onRetry) { Text("다시 시도") }
            }
        }
    }
}

private data class TabItem(val destination: MainDestination, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(MainDestination.Home, "홈", Icons.Default.Home),
    TabItem(MainDestination.Plan, "계획", Icons.Default.CalendarMonth),
    TabItem(MainDestination.Records, "기록", Icons.Default.AddCircle),
    TabItem(MainDestination.Assets, "자산", Icons.Default.AccountBalanceWallet),
    TabItem(MainDestination.More, "더보기", Icons.Default.MoreHoriz),
)

@Composable
private fun MainScreen(
    setup: LedgerSetup,
    authManager: MoaLogAuthSessionManager,
    householdManager: MoaLogHouseholdSessionManager,
    syncManager: MoaLogSyncManager,
) {
    val backStack = rememberNavBackStack(
        configuration = NavigationSavedStateConfiguration,
        MainDestination.Home,
    )
    val recordsStateHolder: RecordsStateHolder = koinInject()
    val recordsState by recordsStateHolder.state.collectAsStateWithLifecycle()
    DisposableEffect(recordsStateHolder) {
        onDispose(recordsStateHolder::close)
    }
    var editorBackRequest by remember { mutableStateOf<(() -> Unit)?>(null) }
    var requestedPlanMonth by remember { mutableStateOf<kr.jm.moalog.core.model.YearMonthKey?>(null) }
    var requestedPlanTab by remember { mutableStateOf<PlanTab?>(null) }
    var requestedAssetsMonthCode by rememberSaveable { mutableStateOf<String?>(null) }
    val requestedAssetsMonth = requestedAssetsMonthCode?.let { code ->
        runCatching {
            val (year, month) = code.split('-', limit = 2)
            kr.jm.moalog.core.model.YearMonthKey(year.toInt(), month.toInt())
        }.getOrNull()
    }
    val rememberAssetsMonth: (kr.jm.moalog.core.model.YearMonthKey) -> Unit = { month ->
        requestedAssetsMonthCode = "${month.year}-${month.month}"
    }
    var requestedSalaryEditCategoryId by remember { mutableStateOf<Long?>(null) }
    val destination = backStack.lastOrNull() as? MainDestination ?: MainDestination.Home
    val selectedTab = mainTabFor(destination)
    val isTabRoot = destination !is MainDestination.ExpenseEditor && destination !is MainDestination.PlanEditor && destination !is MainDestination.AnnualPlan && destination !is MainDestination.MultiMonthApply && destination !is MainDestination.SalaryAllocation && destination !is MainDestination.SalaryAllocationDetail && destination !is MainDestination.FixedCostCheck && destination !is MainDestination.PurposeAccounts && destination !is MainDestination.AssetDetail && destination !is MainDestination.AssetEditor && destination !is MainDestination.CompositionAnalysis && destination !is MainDestination.MaintenanceOverview && destination !is MainDestination.MaintenanceEditor && destination != MainDestination.ItemManagement && destination != MainDestination.LedgerSettings && destination != MainDestination.Guide
    val activity = LocalContext.current as? Activity
    val entryDecorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
        rememberViewModelStoreNavEntryDecorator<NavKey>(
            removeViewModelStoreOnPop = { activity?.isChangingConfigurations != true },
        ),
    )
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (isTabRoot) {
                MoaLogBottomBar(selectedTab) { backStack.selectMainTab(it) }
            }
        },
    ) { padding ->
        NavDisplay(
            backStack = backStack,
            entryDecorators = entryDecorators,
            modifier = Modifier.fillMaxSize().padding(padding),
            onBack = back@{
                val currentDestination = backStack.lastOrNull()
                if (routeManagesSystemBack(currentDestination)) {
                    editorBackRequest?.invoke()
                    return@back
                }
                when (currentDestination) {
                    is MainDestination.AnnualPlan -> {
                        val annual = backStack.lastOrNull() as MainDestination.AnnualPlan
                        requestedPlanMonth = annual.month
                        requestedPlanTab = annual.tab
                        backStack.removeAt(backStack.lastIndex)
                    }
                    is MainDestination.SalaryAllocationDetail, is MainDestination.PurposeAccounts, is MainDestination.AssetDetail, is MainDestination.AssetEditor, is MainDestination.CompositionAnalysis, is MainDestination.MaintenanceOverview,
                    MainDestination.ItemManagement, MainDestination.Guide -> if(backStack.size>1) backStack.removeAt(backStack.lastIndex) else Unit
                    MainDestination.Home -> Unit
                    else -> backStack.selectMainTab(MainDestination.Home)
                }
            },
            entryProvider = { key ->
                NavEntry(key) {
                    when (key) {
                        MainDestination.Home -> {
                            HomeRoute(
                                initialMonth = recordsState.month,
                                onAddExpense = { month -> backStack.openExpenseEditor(null, month) },
                                onOpenOverspentExpenses = { month ->
                                    recordsStateHolder.onAction(RecordsAction.ClearFilters)
                                    recordsStateHolder.onAction(RecordsAction.SelectMonth(month))
                                    recordsStateHolder.onAction(RecordsAction.OverspentOnlyToggled)
                                    backStack.selectMainTab(MainDestination.Records)
                                },
                                onOpenComposition = { month -> backStack.openCompositionAnalysis(month) },
                                onOpenAnnualIncome = { month ->
                                    requestedPlanMonth = month
                                    requestedPlanTab = PlanTab.Income
                                    backStack.openAnnualPlan(month, PlanTab.Income)
                                },
                                onOpenAnnualExpense = { month ->
                                    requestedPlanMonth = month
                                    requestedPlanTab = PlanTab.FixedExpense
                                    backStack.openAnnualPlan(month, PlanTab.FixedExpense)
                                },
                                onOpenAnnualSavings = { month ->
                                    requestedPlanMonth = month
                                    requestedPlanTab = PlanTab.Savings
                                    backStack.openAnnualPlan(month, PlanTab.Savings)
                                },
                            )
                        }
                        is MainDestination.CompositionAnalysis -> CompositionAnalysisRoute(
                            initialMonth = key.month,
                            initialTab = key.tab,
                            onBack = { if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex) },
                            onOpenAnnual = { tab, year ->
                                val planTab = when (tab) {
                                    CompositionTab.FixedExpense -> PlanTab.FixedExpense
                                    CompositionTab.Savings -> PlanTab.Savings
                                    CompositionTab.VariableExpense -> PlanTab.VariableExpense
                                }
                                val selectedMonth = kr.jm.moalog.core.model.YearMonthKey(year, key.month.month)
                                requestedPlanMonth = selectedMonth
                                requestedPlanTab = planTab
                                if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex)
                                backStack.openAnnualPlan(selectedMonth, planTab)
                            },
                            onOpenRecords = { categoryId, month ->
                                recordsStateHolder.onAction(RecordsAction.ClearFilters)
                                recordsStateHolder.onAction(RecordsAction.SelectMonth(month))
                                categoryId?.let { recordsStateHolder.onAction(RecordsAction.CategoryToggled(it)) }
                                backStack.selectMainTab(MainDestination.Records)
                            },
                        )
                        MainDestination.Plan -> PlanRoute(
                            setup = setup,
                            onOpenEditor = { id, type, month -> backStack.openPlanEditor(id, type, month) },
                            onOpenAnnual = { month, tab ->
                                requestedPlanMonth = month
                                requestedPlanTab = tab
                                backStack.openAnnualPlan(month, tab)
                            },
                            onOpenMultiMonthApply = { month, type, itemId ->
                                backStack.openMultiMonthApply(month, type, itemId)
                            },
                            onOpenSalaryAllocation = { month -> backStack.openSalaryAllocation(month) },
                            onOpenFixedCostCheck = { month -> backStack.openFixedCostCheck(month) },
                            onOpenRecords = { month ->
                                recordsStateHolder.onAction(RecordsAction.ClearFilters)
                                recordsStateHolder.onAction(RecordsAction.SelectMonth(month))
                                backStack.selectMainTab(MainDestination.Records)
                            },
                            initialMonth = requestedPlanMonth,
                            initialTab = requestedPlanTab,
                        )
                        MainDestination.Records -> RecordsRoute(
                            stateHolder = recordsStateHolder,
                            onOpenEditor = { id, month -> backStack.openExpenseEditor(id, month) },
                        )
                        MainDestination.Assets -> AssetsRoute(
                            initialMonth = requestedAssetsMonth ?: recordsState.month,
                            members = setup.members,
                            onAddAsset = { month -> rememberAssetsMonth(month); backStack.openAssetEditor(null, month, AssetEditorMode.NewAsset) },
                            onOpenAsset = { id, month -> rememberAssetsMonth(month); backStack.openAssetDetail(id, month) },
                            onOpenPurposeAccounts = { month -> rememberAssetsMonth(month); backStack.openPurposeAccounts(month) },
                            onOpenSavings = { month -> requestedPlanMonth = month; requestedPlanTab = PlanTab.Savings; backStack.selectMainTab(MainDestination.Plan) },
                        )
                        MainDestination.More -> {
                            val context = LocalContext.current
                            val versionName = remember(context) {
                                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
                            }
                            MoreRoute(
                                setup = setup,
                                versionName = versionName,
                                authManager = authManager,
                                householdManager = householdManager,
                                syncManager = syncManager,
                                onOpenItemManagement = backStack::openItemManagement,
                                onOpenGuide = backStack::openGuide,
                                onOpenLedgerSettings = backStack::openLedgerSettings,
                                onOpenMaintenance = { backStack.openMaintenanceOverview(recordsState.month) },
                            )
                        }
                        is MainDestination.MaintenanceOverview -> MaintenanceOverviewRoute(
                            args = MaintenanceOverviewArgs(key.month),
                            onBack = { if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex) },
                            onEdit = backStack::openMaintenanceEditor,
                        )
                        is MainDestination.MaintenanceEditor -> MaintenanceEditorRoute(
                            args = MaintenanceEditorArgs(key.month),
                            onBack = { if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex) },
                            onSaved = { if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex) },
                        )
                        MainDestination.ItemManagement -> ItemManagementRoute(
                            members = setup.members,
                            onBack = { if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex) },
                        )
                        MainDestination.LedgerSettings -> LedgerSettingsRoute(
                            onBack = { if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex) },
                            onSaved = { if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex) },
                            registerSystemBackRequest = { editorBackRequest = it },
                        )
                        MainDestination.Guide -> GuideScreen(
                            onBack = { if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex) },
                            onOpenDestination = { guideDestination ->
                                val selectedMonth = recordsState.month
                                when (guideDestination) {
                                    GuideDestination.SalaryAllocation -> {
                                        backStack.selectMainTab(MainDestination.Plan)
                                        backStack.openSalaryAllocation(selectedMonth)
                                    }
                                    GuideDestination.FixedCostCheck -> {
                                        backStack.selectMainTab(MainDestination.Plan)
                                        backStack.openFixedCostCheck(selectedMonth)
                                    }
                                    GuideDestination.MonthlyPlan -> {
                                        requestedPlanMonth = selectedMonth
                                        backStack.selectMainTab(MainDestination.Plan)
                                    }
                                    GuideDestination.Records -> {
                                        recordsStateHolder.onAction(RecordsAction.SelectMonth(selectedMonth))
                                        backStack.selectMainTab(MainDestination.Records)
                                    }
                                    GuideDestination.Assets -> {
                                        rememberAssetsMonth(selectedMonth)
                                        backStack.selectMainTab(MainDestination.Assets)
                                    }
                                }
                            },
                        )
                        is MainDestination.PurposeAccounts -> PurposeAccountsRoute(
                            initialMonth = key.month,
                            onBack = { month -> rememberAssetsMonth(month); if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex) },
                            onManage = { month -> backStack.openAssetEditor(null, month, AssetEditorMode.NewAsset, purposeAccount = true) },
                            onAdd = { month -> backStack.openAssetEditor(null, month, AssetEditorMode.NewAsset, purposeAccount = true) },
                            onOpenAccount = { id, month -> backStack.openAssetDetail(id, month, purposeAccount = true) },
                            onFillMissing = { id, month -> backStack.openAssetEditor(id, month, AssetEditorMode.MonthlyValue, purposeAccount = true) },
                        )
                        is MainDestination.AssetDetail -> AssetDetailRoute(
                            args = AssetDetailArgs(key.assetId, key.month),
                            onBack = { month -> rememberAssetsMonth(month); if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex) },
                            onEditAsset = { month -> backStack.openAssetEditor(key.assetId, month, AssetEditorMode.NewAsset, key.purposeAccount) },
                            onEditMonth = { backStack.openAssetEditor(key.assetId, it, AssetEditorMode.MonthlyValue, key.purposeAccount) },
                            onEditRule = { month -> backStack.openAssetEditor(key.assetId, month, AssetEditorMode.GrowthRule, key.purposeAccount) },
                            onDeleted = { month -> rememberAssetsMonth(month); if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex) },
                        )
                        is MainDestination.AssetEditor -> AssetEditorRoute(
                            args = AssetEditorArgs(key.assetId, key.month, key.mode, key.purposeAccount),
                            members = setup.members,
                            onBack = { if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex) },
                            onSaved = { savedAssetId, savedMonth ->
                                rememberAssetsMonth(savedMonth)
                                if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex)
                                if (key.assetId == null && savedAssetId != null) backStack.openAssetDetail(savedAssetId, savedMonth, key.purposeAccount)
                            },
                        )
                        is MainDestination.ExpenseEditor -> ExpenseEditorRoute(
                            args = ExpenseEditorArgs(key.recordId, key.month),
                            registerSystemBackRequest = { editorBackRequest = it },
                            onBack = {
                                if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex)
                            },
                            onFinished = { month ->
                                if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex)
                                recordsStateHolder.onAction(RecordsAction.ClearFilters)
                                recordsStateHolder.onAction(RecordsAction.SelectMonth(month))
                                backStack.selectMainTab(MainDestination.Records)
                            },
                        )
                        is MainDestination.PlanEditor -> PlanEditorRoute(
                            args = PlanEditorArgs(key.itemId, key.type, key.month),
                            members = setup.members,
                            registerSystemBackRequest = { editorBackRequest = it },
                            onBack = { if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex) },
                            onFinished = { if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex) },
                            onSavedAndApply = { itemId, month, type ->
                                if (backStack.lastOrNull() == key) {
                                    backStack.removeAt(backStack.lastIndex)
                                    backStack.openMultiMonthApply(month, type, itemId)
                                }
                            },
                        )
                        is MainDestination.MultiMonthApply -> MultiMonthApplyRoute(
                            args = MultiMonthApplyArgs(key.month, key.type, key.sourceItemId),
                            onBack = {
                                requestedPlanMonth = key.month
                                requestedPlanTab = key.type.toPlanTab()
                                if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex)
                            },
                            onComplete = { month, tab ->
                                requestedPlanMonth = month
                                requestedPlanTab = tab
                                if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex)
                            },
                            registerSystemBackRequest = { editorBackRequest = it },
                        )
                        is MainDestination.AnnualPlan -> AnnualPlanRoute(
                            args = AnnualPlanArgs(key.year),
                            members = setup.members,
                            onBack = {
                                requestedPlanMonth = key.month
                                requestedPlanTab = key.tab
                                if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex)
                            },
                            onOpenMonth = { month, section ->
                                requestedPlanMonth = month
                                requestedPlanTab = section.toPlanTab()
                                if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex)
                            },
                            onOpenEditor = { id, type, month -> backStack.openPlanEditor(id, type, month) },
                            onOpenVariableMonth = { month ->
                                recordsStateHolder.onAction(RecordsAction.ClearFilters)
                                recordsStateHolder.onAction(RecordsAction.SelectMonth(month))
                                backStack.selectMainTab(MainDestination.Records)
                            },
                        )
                        is MainDestination.SalaryAllocation -> SalaryAllocationRoute(
                            args = SalaryAllocationArgs(key.key, setup.members.sortedBy { it.order }.map { it.order }),
                            setup = setup,
                            initialEditCategoryId = requestedSalaryEditCategoryId,
                            onInitialEditConsumed = { requestedSalaryEditCategoryId = null },
                            onBack = { selectedMonth ->
                                requestedPlanMonth = selectedMonth
                                if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex)
                            },
                            onOpenDetail = { categoryId, selectedMonth -> requestedSalaryEditCategoryId = null; backStack.openSalaryAllocationDetail(categoryId,selectedMonth) },
                            registerSystemBackRequest = { editorBackRequest = it },
                        )
                        is MainDestination.SalaryAllocationDetail -> SalaryAllocationDetailRoute(
                            args = SalaryAllocationDetailArgs(key.categoryId,key.key),
                            onBack = { if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex) },
                            onEditCategory = { id ->
                                requestedSalaryEditCategoryId = id
                                if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex)
                            },
                        )
                        is MainDestination.FixedCostCheck -> FixedCostCheckRoute(
                            args = FixedCostCheckArgs(key.key, setup.members.sortedBy { it.order }.map { it.order }),
                            setup = setup,
                            onBack = { selectedMonth ->
                                requestedPlanMonth = selectedMonth
                                requestedPlanTab = PlanTab.FixedExpense
                                if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex)
                            },
                            onApplied = { selectedMonth ->
                                requestedPlanMonth = selectedMonth
                                requestedPlanTab = PlanTab.FixedExpense
                                if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex)
                            },
                            registerSystemBackRequest = { editorBackRequest = it },
                        )
                    }
                }
            },
        )
    }
}

private fun AnnualPlanSection.toPlanTab() = when (this) {
    AnnualPlanSection.Income -> PlanTab.Income
    AnnualPlanSection.FixedExpense -> PlanTab.FixedExpense
    AnnualPlanSection.VariableExpense -> PlanTab.VariableExpense
    AnnualPlanSection.Savings -> PlanTab.Savings
}

private fun kr.jm.moalog.core.model.PlanItemType.toPlanTab() = when (this) {
    kr.jm.moalog.core.model.PlanItemType.Income -> PlanTab.Income
    kr.jm.moalog.core.model.PlanItemType.FixedExpense -> PlanTab.FixedExpense
    kr.jm.moalog.core.model.PlanItemType.Savings -> PlanTab.Savings
}

@Composable
private fun MoaLogBottomBar(selected: MainDestination, onSelect: (MainDestination) -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().height(64.dp), color = Color.White, shadowElevation = 3.dp) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { tab ->
                val isSelected = selected == tab.destination
                Column(
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).selectable(selected = isSelected, role = Role.Tab) { onSelect(tab.destination) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (tab.destination == MainDestination.Records && isSelected) {
                        Box(Modifier.height(34.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Surface(shape = CircleShape, color = MoaLogColors.DeepTeal, shadowElevation = 4.dp) {
                                Box(Modifier.height(34.dp).fillMaxWidth(.48f), contentAlignment = Alignment.Center) {
                                    Icon(tab.icon, contentDescription = null, modifier = Modifier.clearAndSetSemantics { }, tint = Color.White)
                                }
                            }
                        }
                    } else {
                        Icon(tab.icon, contentDescription = null, modifier = Modifier.clearAndSetSemantics { }, tint = if (isSelected) MoaLogColors.DeepTeal else MoaLogColors.MutedInk)
                    }
                    Text(tab.label, color = if (isSelected) MoaLogColors.DeepTeal else MoaLogColors.MutedInk, style = MaterialTheme.typography.labelSmall, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}
