package kr.jm.moalog.appshell

import kr.jm.moalog.core.database.LedgerSetupLocalDataSource
import kr.jm.moalog.core.database.ExpenseLocalDataSource
import kr.jm.moalog.core.database.ExpenseMonthSnapshot
import kr.jm.moalog.core.database.PlanLocalDataSource
import kr.jm.moalog.core.database.AssetLocalDataSource
import kr.jm.moalog.core.database.PlanCopyPreview
import kr.jm.moalog.core.database.PlanCopyRequest
import kr.jm.moalog.core.database.PlanCopyResult
import kr.jm.moalog.core.database.FixedCostApplyResult
import kr.jm.moalog.core.database.FixedCostPlanPreview
import kr.jm.moalog.core.database.ExistingPlanPolicy
import kr.jm.moalog.core.model.FixedCostCheckItem
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.records.presentation.RecordsStateHolder
import kr.jm.moalog.feature.plan.presentation.PlanStateHolder
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.AssetItem
import kr.jm.moalog.core.model.AssetPortfolio
import kr.jm.moalog.core.model.AssetValuation
import kr.jm.moalog.core.model.AssetGrowthRule
import kr.jm.moalog.feature.assets.presentation.AssetsStateHolder
import kr.jm.moalog.feature.assets.presentation.AssetDetailArgs
import kr.jm.moalog.feature.assets.presentation.AssetDetailStateHolder
import kr.jm.moalog.feature.assets.presentation.AssetEditorArgs
import kr.jm.moalog.feature.assets.presentation.AssetEditorMode
import kr.jm.moalog.feature.assets.presentation.AssetEditorStateHolder
import kr.jm.moalog.feature.assets.presentation.PurposeAccountsStateHolder
import kr.jm.moalog.feature.assets.assetsModule
import kr.jm.moalog.feature.plan.planModule
import kr.jm.moalog.feature.records.recordsModule
import kr.jm.moalog.feature.setup.domain.SetupRepository
import kr.jm.moalog.feature.setup.domain.setupModule
import kr.jm.moalog.feature.setup.presentation.SetupStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

class KoinModuleTest {
    @Test
    fun graphResolvesRootAndSetupStateHolders() {
        val application = startKoin {
            modules(
                appShellModule,
                setupModule,
                recordsModule,
                planModule,
                assetsModule,
                module {
                    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
                    single<LedgerSetupLocalDataSource> { FakeLocalDataSource() }
                    single<ExpenseLocalDataSource> { FakeExpenseLocalDataSource() }
                    single<PlanLocalDataSource> { FakePlanLocalDataSource() }
                    single<AssetLocalDataSource> { FakeAssetLocalDataSource() }
                },
            )
        }
        try {
            assertNotNull(application.koin.get<SetupRepository>())
            assertNotNull(application.koin.get<SetupStateHolder>())
            assertNotNull(application.koin.get<AppShellStateHolder>())
            val first = application.koin.get<RecordsStateHolder>()
            val second = application.koin.get<RecordsStateHolder>()
            assertNotSame(first, second)
            assertNotNull(application.koin.get<PlanStateHolder>())
            assertNotNull(application.koin.get<AssetsStateHolder> { org.koin.core.parameter.parametersOf(YearMonthKey(2026, 5)) })
            assertNotNull(application.koin.get<PurposeAccountsStateHolder> { org.koin.core.parameter.parametersOf(YearMonthKey(2026, 5)) })
            assertNotNull(application.koin.get<AssetDetailStateHolder> { org.koin.core.parameter.parametersOf(AssetDetailArgs(1, YearMonthKey(2026, 5))) })
            assertNotNull(application.koin.get<AssetEditorStateHolder> { org.koin.core.parameter.parametersOf(AssetEditorArgs(null, YearMonthKey(2026, 5), AssetEditorMode.NewAsset)) })
        } finally {
            stopKoin()
        }
    }

    private class FakeAssetLocalDataSource : AssetLocalDataSource {
        override fun observePortfolio(): Flow<AssetPortfolio> = flowOf(AssetPortfolio(emptyList(), emptyList()))
        override suspend fun addAsset(asset: AssetItem): Long = 1L
        override suspend fun addAssetWithInitial(asset: AssetItem, valuationMonth: YearMonthKey, amountWon: Long): Long = 1L
        override suspend fun updateAsset(asset: AssetItem) = Unit
        override suspend fun updateAssetWithValuation(asset: AssetItem, valuation: AssetValuation) = Unit
        override suspend fun saveValuation(valuation: AssetValuation) = Unit
        override suspend fun saveGrowthRule(rule: AssetGrowthRule) = Unit
        override suspend fun deleteAsset(assetId: Long) = Unit
        override suspend fun deleteValuation(assetId: Long, month: YearMonthKey) = Unit
        override suspend fun deleteGrowthRule(assetId: Long) = Unit
    }

    private class FakePlanLocalDataSource : PlanLocalDataSource {
        override fun observeMonth(month: YearMonthKey): Flow<List<MonthlyPlanItem>> = flowOf(emptyList())
        override suspend fun previewCopy(request: PlanCopyRequest): PlanCopyPreview = error("unused")
        override suspend fun copyToMonths(request: PlanCopyRequest): PlanCopyResult = error("unused")
        override suspend fun find(id: Long): MonthlyPlanItem? = null
        override suspend fun save(item: MonthlyPlanItem): Long = 1
        override suspend fun delete(id: Long) = Unit
        override suspend fun previewFixedCosts(month: YearMonthKey, items: List<FixedCostCheckItem>): FixedCostPlanPreview = error("unused")
        override suspend fun applyFixedCosts(month: YearMonthKey, items: List<FixedCostCheckItem>, policy: ExistingPlanPolicy): FixedCostApplyResult = error("unused")
    }

    private class FakeExpenseLocalDataSource : ExpenseLocalDataSource {
        override suspend fun ensureDefaultCategories() = Unit
        override fun observeMonth(month: YearMonthKey): Flow<ExpenseMonthSnapshot> = flowOf(ExpenseMonthSnapshot(emptyList(), emptyList()))
        override suspend fun findRecord(id: Long): ExpenseRecord? = null
        override suspend fun saveRecord(record: ExpenseRecord): Long = 1
        override suspend fun deleteRecord(id: Long) = Unit
        override suspend fun renameCategory(categoryId: String, name: String) = Unit
    }

    private class FakeLocalDataSource : LedgerSetupLocalDataSource {
        override fun observe(): Flow<LedgerSetup?> = flowOf(null)
        override suspend fun save(setup: LedgerSetup) = Unit
    }
}
