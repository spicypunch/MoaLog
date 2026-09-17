package kr.jm.moalog.di

import io.ktor.client.HttpClient
import kr.jm.moalog.auth.AndroidBackendRuntime
import kr.jm.moalog.auth.AndroidSecureAuthSessionStore
import kr.jm.moalog.auth.AuthClock
import kr.jm.moalog.auth.SecureAuthSessionStore
import kr.jm.moalog.core.contracts.ClientPlatform
import kr.jm.moalog.core.database.MoaLogDatabase
import kr.jm.moalog.core.database.LedgerSetupLocalDataSource
import kr.jm.moalog.core.database.ExpenseLocalDataSource
import kr.jm.moalog.core.database.buildMoaLogDatabase
import kr.jm.moalog.core.database.createDatabaseBuilder
import kr.jm.moalog.core.database.createLedgerSetupLocalDataSource
import kr.jm.moalog.core.database.createExpenseLocalDataSource
import kr.jm.moalog.core.database.PlanLocalDataSource
import kr.jm.moalog.core.database.createPlanLocalDataSource
import kr.jm.moalog.core.database.SalaryAllocationLocalDataSource
import kr.jm.moalog.core.database.createSalaryAllocationLocalDataSource
import kr.jm.moalog.core.database.FixedCostCheckLocalDataSource
import kr.jm.moalog.core.database.createFixedCostCheckLocalDataSource
import kr.jm.moalog.core.database.AssetLocalDataSource
import kr.jm.moalog.core.database.createAssetLocalDataSource
import kr.jm.moalog.core.database.MaintenanceFeeLocalDataSource
import kr.jm.moalog.core.database.SyncLocalStore
import kr.jm.moalog.core.database.createMaintenanceFeeLocalDataSource
import kr.jm.moalog.core.database.createSyncLocalStore
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

internal actual val platformModule: Module = module {
    single { AndroidBackendRuntime(androidContext()) }
    single<SecureAuthSessionStore> { AndroidSecureAuthSessionStore(androidContext()) }
    single<AuthClock> { AuthClock { System.currentTimeMillis() / 1_000L } }
    single { MobileAppInfo(ClientPlatform.ANDROID, get<AndroidBackendRuntime>().appVersion) }
    single<HttpClient>(PublicApiClient) { get<AndroidBackendRuntime>().publicClient() }
    single<HttpClient>(AuthenticatedApiClient) {
        get<AndroidBackendRuntime>().authenticatedClient(get())
    }
    single<MoaLogDatabase> { buildMoaLogDatabase(createDatabaseBuilder(androidContext())) }
    single<LedgerSetupLocalDataSource> { createLedgerSetupLocalDataSource(get()) }
    single<ExpenseLocalDataSource> { createExpenseLocalDataSource(get()) }
    single<PlanLocalDataSource> { createPlanLocalDataSource(get()) }
    single<SalaryAllocationLocalDataSource> { createSalaryAllocationLocalDataSource(get()) }
    single<FixedCostCheckLocalDataSource> { createFixedCostCheckLocalDataSource(get()) }
    single<AssetLocalDataSource> { createAssetLocalDataSource(get()) }
    single<MaintenanceFeeLocalDataSource> { createMaintenanceFeeLocalDataSource(get()) }
    single<SyncLocalStore> { createSyncLocalStore(get()) }
}
