package kr.jm.moalog.feature.setup.domain

import kr.jm.moalog.core.database.LedgerSetupLocalDataSource
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.feature.setup.presentation.SetupStateHolder
import kr.jm.moalog.feature.setup.presentation.LedgerSettingsStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import org.koin.core.module.Module
import org.koin.dsl.module

interface SetupRepository {
    fun observeSetup(): Flow<LedgerSetup?>
    suspend fun saveSetup(setup: LedgerSetup)
}

internal class DefaultSetupRepository(
    private val localDataSource: LedgerSetupLocalDataSource,
) : SetupRepository {
    override fun observeSetup(): Flow<LedgerSetup?> = localDataSource.observe()
    override suspend fun saveSetup(setup: LedgerSetup) = localDataSource.save(setup)
}

val setupModule: Module = module {
    single<SetupRepository> { DefaultSetupRepository(get()) }
    factory { SetupStateHolder(get(), get<CoroutineScope>()) }
    factory { LedgerSettingsStateHolder(get(), get<CoroutineScope>()) }
}
