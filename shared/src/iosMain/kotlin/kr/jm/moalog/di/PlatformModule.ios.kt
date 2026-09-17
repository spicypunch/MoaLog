package kr.jm.moalog.di

import io.ktor.client.HttpClient
import kr.jm.moalog.auth.AuthClock
import kr.jm.moalog.auth.IosBackendRuntime
import kr.jm.moalog.auth.IosSecureAuthSessionStore
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
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.timeIntervalSince1970

internal actual val platformModule: Module = module {
    single { IosBackendRuntime() }
    single<SecureAuthSessionStore> { IosSecureAuthSessionStore() }
    single<AuthClock> { AuthClock { (platform.Foundation.NSDate().timeIntervalSince1970).toLong() } }
    single { MobileAppInfo(ClientPlatform.IOS, get<IosBackendRuntime>().appVersion) }
    single<HttpClient>(PublicApiClient) { get<IosBackendRuntime>().publicClient() }
    single<HttpClient>(AuthenticatedApiClient) {
        get<IosBackendRuntime>().authenticatedClient(get())
    }
    single<MoaLogDatabase> { buildMoaLogDatabase(createDatabaseBuilder()) }
    single<LedgerSetupLocalDataSource> { createLedgerSetupLocalDataSource(get()) }
    single<ExpenseLocalDataSource> { createExpenseLocalDataSource(get()) }
    single<PlanLocalDataSource> { createPlanLocalDataSource(get()) }
    single<SalaryAllocationLocalDataSource> { createSalaryAllocationLocalDataSource(get()) }
    single<FixedCostCheckLocalDataSource> { createFixedCostCheckLocalDataSource(get()) }
    single<AssetLocalDataSource> { createAssetLocalDataSource(get()) }
    single<MaintenanceFeeLocalDataSource> { createMaintenanceFeeLocalDataSource(get()) }
    single<SyncLocalStore> { createSyncLocalStore(get()) }
}
