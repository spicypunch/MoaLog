package kr.jm.moalog.di

import io.ktor.client.HttpClient
import kr.jm.moalog.auth.AuthClock
import kr.jm.moalog.auth.MoaLogAuthSessionManager
import kr.jm.moalog.auth.PersistentMoaLogTokenProvider
import kr.jm.moalog.auth.SecureAuthSessionStore
import kr.jm.moalog.core.contracts.ClientPlatform
import kr.jm.moalog.core.network.MoaLogAuthApi
import kr.jm.moalog.core.network.MoaLogHouseholdApi
import kr.jm.moalog.core.network.MoaLogSyncApi
import kr.jm.moalog.household.ActiveSyncSessionContextProvider
import kr.jm.moalog.household.MoaLogHouseholdSessionManager
import kr.jm.moalog.sync.MoaLogSyncCoordinator
import kr.jm.moalog.sync.MoaLogSyncManager
import kr.jm.moalog.sync.MoaLogSyncRemoteGateway
import kr.jm.moalog.sync.PlatformSyncClock
import kr.jm.moalog.sync.PlatformSyncUuidFactory
import kr.jm.moalog.sync.SyncClock
import kr.jm.moalog.sync.SyncRemoteGateway
import kr.jm.moalog.sync.SyncSessionContextProvider
import kr.jm.moalog.sync.SyncOperationGuard
import org.koin.core.qualifier.named
import org.koin.dsl.module

data class MobileAppInfo(val platform: ClientPlatform, val version: String) {
    init { require(version.isNotBlank()) }
}

internal val PublicApiClient = named("moalog-public-api")
internal val AuthenticatedApiClient = named("moalog-authenticated-api")

internal val backendModule = module {
    single { PersistentMoaLogTokenProvider(get<SecureAuthSessionStore>(), get<AuthClock>()) }
    single {
        MoaLogAuthApi(
            publicClient = get<HttpClient>(PublicApiClient),
            authenticatedClient = get<HttpClient>(AuthenticatedApiClient),
        ).also { api -> get<PersistentMoaLogTokenProvider>().attachRefreshRequest(api::refresh) }
    }
    single { MoaLogHouseholdApi(get<HttpClient>(AuthenticatedApiClient)) }
    single { MoaLogSyncApi(get<HttpClient>(AuthenticatedApiClient)) }
    single {
        val app = get<MobileAppInfo>()
        MoaLogAuthSessionManager(get(), get(), get(), app.platform, app.version, PlatformSyncUuidFactory)
    }
    single { SyncOperationGuard() }
    single { MoaLogHouseholdSessionManager(get(), get(), get(), get()) }
    single<SyncSessionContextProvider> { ActiveSyncSessionContextProvider(get(), get()) }
    single<SyncRemoteGateway> { MoaLogSyncRemoteGateway(get()) }
    single<SyncClock> { PlatformSyncClock }
    single { MoaLogSyncCoordinator(get(), get(), get(), get(), PlatformSyncUuidFactory, get()) }
    single { MoaLogSyncManager(get(), get(), get(), get()) }
}
