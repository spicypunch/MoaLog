package kr.jm.moalog.feature.records

import kr.jm.moalog.feature.records.domain.CurrentMonthProvider
import kr.jm.moalog.feature.records.domain.CurrentDateProvider
import kr.jm.moalog.feature.records.data.DefaultExpenseRepository
import kr.jm.moalog.feature.records.domain.ExpenseRepository
import kr.jm.moalog.feature.records.domain.platformCurrentMonthProvider
import kr.jm.moalog.feature.records.domain.platformCurrentDateProvider
import kr.jm.moalog.feature.records.presentation.ExpenseEditorArgs
import kr.jm.moalog.feature.records.presentation.ExpenseEditorStateHolder
import kr.jm.moalog.feature.records.presentation.RecordsStateHolder
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

val recordsModule: Module = module {
    single<ExpenseRepository> { DefaultExpenseRepository(get()) }
    single<CurrentMonthProvider> { platformCurrentMonthProvider() }
    single<CurrentDateProvider> { platformCurrentDateProvider() }
    factory { RecordsStateHolder(get(), get(), get(), get<CoroutineScope>()) }
    factory { (args: ExpenseEditorArgs) -> ExpenseEditorStateHolder(args, get(), get<CoroutineScope>(), get()) }
}
