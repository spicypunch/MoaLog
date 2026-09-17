package kr.jm.moalog.feature.maintenance

import kr.jm.moalog.feature.maintenance.data.DefaultMaintenanceRepository
import kr.jm.moalog.feature.maintenance.domain.MaintenanceRepository
import kr.jm.moalog.feature.maintenance.presentation.MaintenanceEditorArgs
import kr.jm.moalog.feature.maintenance.presentation.MaintenanceEditorStateHolder
import kr.jm.moalog.feature.maintenance.presentation.MaintenanceOverviewArgs
import kr.jm.moalog.feature.maintenance.presentation.MaintenanceOverviewStateHolder
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

val maintenanceModule: Module = module {
    single<MaintenanceRepository> { DefaultMaintenanceRepository(get()) }
    factory { (args: MaintenanceOverviewArgs) -> MaintenanceOverviewStateHolder(args, get(), get<CoroutineScope>()) }
    factory { (args: MaintenanceEditorArgs) -> MaintenanceEditorStateHolder(args, get(), get<CoroutineScope>()) }
}
