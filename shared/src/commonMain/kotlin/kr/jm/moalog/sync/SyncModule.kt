package kr.jm.moalog.sync

import kr.jm.moalog.core.database.SyncLocalStore
import kr.jm.moalog.core.database.SyncUuidFactory
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Installs the production sync singletons after the host supplies its authenticated HTTP gateway
 * and active login/household context. The platform Room module already owns [SyncLocalStore].
 */
fun moaLogSyncModule(
    remoteGateway: SyncRemoteGateway,
    contextProvider: SyncSessionContextProvider,
    clock: SyncClock = PlatformSyncClock,
    uuidFactory: SyncUuidFactory = PlatformSyncUuidFactory,
): Module = module {
    single<SyncRemoteGateway> { remoteGateway }
    single<SyncSessionContextProvider> { contextProvider }
    single<SyncClock> { clock }
    single<SyncUuidFactory> { uuidFactory }
    single { SyncOperationGuard() }
    single { MoaLogSyncCoordinator(get(), get(), get(), get(), get(), get()) }
    single { MoaLogSyncManager(get(), get(), get(), get()) }
}
