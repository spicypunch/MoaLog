package kr.jm.moalog.di

import kr.jm.moalog.feature.assets.assetsModule
import kr.jm.moalog.feature.maintenance.maintenanceModule
import kr.jm.moalog.feature.plan.planModule
import kr.jm.moalog.feature.records.recordsModule
import kr.jm.moalog.feature.setup.domain.setupModule
import kr.jm.moalog.feature.home.homeModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

fun initKoin(appDeclaration: KoinApplication.() -> Unit = {}) {
    if (KoinPlatform.getKoinOrNull() != null) return
    startKoin {
        appDeclaration()
        modules(platformModule, commonModule)
    }
}

internal expect val platformModule: Module

internal val commonModule = module {
    includes(setupModule, recordsModule, planModule, assetsModule, maintenanceModule, homeModule, backendModule)
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
}
