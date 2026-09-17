package kr.jm.moalog.feature.home

import kr.jm.moalog.feature.home.domain.DefaultHomeRepository
import kr.jm.moalog.feature.home.domain.HomeRepository
import kr.jm.moalog.feature.home.presentation.CompositionAnalysisStateHolder
import kr.jm.moalog.feature.home.presentation.CompositionTab
import kr.jm.moalog.feature.home.presentation.HomeStateHolder
import kr.jm.moalog.core.model.YearMonthKey
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

val homeModule: Module = module {
    single<HomeRepository> { DefaultHomeRepository(get(), get(), get()) }
    factory { (month: YearMonthKey) -> HomeStateHolder(get(), month, get<CoroutineScope>()) }
    factory { (month: YearMonthKey, tab: CompositionTab) ->
        CompositionAnalysisStateHolder(get(), month, tab, get<CoroutineScope>())
    }
}
