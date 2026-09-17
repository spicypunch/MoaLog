package kr.jm.moalog.feature.assets

import kr.jm.moalog.feature.assets.data.DefaultAssetRepository
import kr.jm.moalog.feature.assets.domain.AssetRepository
import kr.jm.moalog.feature.assets.presentation.AssetsStateHolder
import kr.jm.moalog.feature.assets.presentation.AssetDetailStateHolder
import kr.jm.moalog.feature.assets.presentation.AssetEditorStateHolder
import kr.jm.moalog.feature.assets.presentation.PurposeAccountsStateHolder
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module

val assetsModule: Module = module {
    single<AssetRepository> { DefaultAssetRepository(get()) }
    factory { (initialMonth: kr.jm.moalog.core.model.YearMonthKey) -> AssetsStateHolder(initialMonth, get(), get<CoroutineScope>()) }
    factory { (args: kr.jm.moalog.feature.assets.presentation.AssetDetailArgs) -> AssetDetailStateHolder(args, get(), get<CoroutineScope>()) }
    factory { (args: kr.jm.moalog.feature.assets.presentation.AssetEditorArgs) -> AssetEditorStateHolder(args, get(), get<CoroutineScope>()) }
    factory { (initialMonth: kr.jm.moalog.core.model.YearMonthKey) -> PurposeAccountsStateHolder(initialMonth, get(), get<CoroutineScope>()) }
}
