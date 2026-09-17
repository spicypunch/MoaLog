package kr.jm.moalog.feature.plan

import kr.jm.moalog.feature.plan.domain.DefaultPlanRepository
import kr.jm.moalog.feature.plan.domain.DefaultSalaryAllocationRepository
import kr.jm.moalog.feature.plan.domain.SalaryAllocationRepository
import kr.jm.moalog.feature.plan.domain.PlanCurrentMonthProvider
import kr.jm.moalog.feature.plan.domain.PlanRepository
import kr.jm.moalog.feature.plan.domain.FixedCostCheckRepository
import kr.jm.moalog.feature.plan.domain.DefaultFixedCostCheckRepository
import kr.jm.moalog.feature.plan.domain.ItemManagementRepository
import kr.jm.moalog.feature.plan.domain.DefaultItemManagementRepository
import kr.jm.moalog.feature.plan.domain.platformPlanCurrentMonthProvider
import kr.jm.moalog.feature.plan.presentation.PlanEditorArgs
import kr.jm.moalog.feature.plan.presentation.PlanEditorStateHolder
import kr.jm.moalog.feature.plan.presentation.PlanStateHolder
import kr.jm.moalog.feature.plan.presentation.MultiMonthApplyArgs
import kr.jm.moalog.feature.plan.presentation.MultiMonthApplyStateHolder
import kr.jm.moalog.feature.plan.presentation.AnnualPlanArgs
import kr.jm.moalog.feature.plan.presentation.AnnualPlanStateHolder
import kotlinx.coroutines.CoroutineScope
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationArgs
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationStateHolder
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationDetailArgs
import kr.jm.moalog.feature.plan.presentation.SalaryAllocationDetailStateHolder
import kr.jm.moalog.feature.plan.presentation.FixedCostCheckArgs
import kr.jm.moalog.feature.plan.presentation.FixedCostCheckStateHolder
import kr.jm.moalog.feature.plan.presentation.ItemManagementStateHolder
import org.koin.core.module.Module
import org.koin.dsl.module

val planModule: Module = module {
    single<PlanRepository> { DefaultPlanRepository(get(), get()) }
    single<ItemManagementRepository> { DefaultItemManagementRepository(get(), get()) }
    single<SalaryAllocationRepository> { DefaultSalaryAllocationRepository(get()) }
    single<FixedCostCheckRepository> { DefaultFixedCostCheckRepository(get(), get()) }
    single<PlanCurrentMonthProvider> { platformPlanCurrentMonthProvider() }
    factory { PlanStateHolder(get(), get(), get<CoroutineScope>()) }
    factory { (args: MultiMonthApplyArgs) -> MultiMonthApplyStateHolder(args, get(), get<CoroutineScope>()) }
    factory { (args: AnnualPlanArgs) -> AnnualPlanStateHolder(args, get(), get(), get<CoroutineScope>()) }
    factory { (args: PlanEditorArgs) -> PlanEditorStateHolder(args, get(), get<CoroutineScope>()) }
    factory { (args: SalaryAllocationArgs) -> SalaryAllocationStateHolder(args, get(), get<CoroutineScope>()) }
    factory { (args: SalaryAllocationDetailArgs) -> SalaryAllocationDetailStateHolder(args, get(), get<CoroutineScope>()) }
    factory { (args: FixedCostCheckArgs) -> FixedCostCheckStateHolder(args, get(), get<CoroutineScope>()) }
    factory { ItemManagementStateHolder(get(), get<CoroutineScope>()) }
}
