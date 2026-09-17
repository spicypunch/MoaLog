package kr.jm.moalog.di

import kr.jm.moalog.core.model.AssetGrowthRule
import kr.jm.moalog.core.model.AssetItem
import kr.jm.moalog.core.model.AssetKind
import kr.jm.moalog.core.model.AssetPortfolio
import kr.jm.moalog.core.model.AssetType
import kr.jm.moalog.core.model.AssetValuation
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.feature.assets.domain.AssetRepository
import kr.jm.moalog.feature.assets.presentation.AssetDetailArgs
import kr.jm.moalog.feature.assets.presentation.AssetDetailStateHolder
import kr.jm.moalog.feature.assets.presentation.AssetEditorArgs
import kr.jm.moalog.feature.assets.presentation.AssetEditorMode
import kr.jm.moalog.feature.assets.presentation.AssetEditorStateHolder
import kr.jm.moalog.feature.assets.presentation.AssetSort
import kr.jm.moalog.feature.assets.presentation.AssetsStateHolder
import kr.jm.moalog.feature.assets.presentation.PurposeAccountsStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosStage4StoresTest {
    private val may = YearMonthKey(2026, 5)
    private val ordinary = AssetItem(1, "비상금", AssetType.Cash, memo = "공동 비상금")
    private val purpose = AssetItem(2, "여행 통장", AssetType.Deposit, kind = AssetKind.PurposeAccount)

    @Test
    fun assetsAndPurposeStoresRestoreMonthAndSorting() = runBlocking {
        val repository = FakeAssetRepository(
            AssetPortfolio(
                listOf(ordinary, purpose),
                listOf(AssetValuation(1, may, 100L), AssetValuation(2, may, 30L)),
            ),
        )
        val assets = IosAssetsStore(AssetsStateHolder(may, repository, this))
        settle()
        assets.toggleSort()
        assets.selectMonth(1900, 1)
        assertEquals(YearMonthKey(1900, 1), assets.currentState.month)
        assertEquals(AssetSort.Name, assets.currentState.sort)
        assets.close()

        val accounts = IosPurposeAccountsStore(PurposeAccountsStateHolder(may, repository, this))
        settle()
        accounts.selectMonth(1900, 1)
        assertEquals(YearMonthKey(1900, 1), accounts.currentState.month)
        assertEquals(listOf(purpose.id), accounts.currentState.rows.map { it.asset.id })
        accounts.close()
    }

    @Test
    fun editorRestoresMetadataAndSavesCorrectMonth() = runBlocking {
        val repository = FakeAssetRepository(AssetPortfolio(emptyList(), emptyList()))
        val store = IosAssetEditorStore(
            AssetEditorStateHolder(AssetEditorArgs(null, may, AssetEditorMode.NewAsset), repository, this),
        )
        store.changeMonthlyIncrease("-3000")
        assertEquals("-3000", store.currentState.monthlyIncrease)
        store.restoreDraft(
            selectedMode = AssetEditorMode.NewAsset,
            name = "수아 ISA",
            type = AssetType.Investment,
            ownerMemberOrder = 0,
            memo = "장기 투자",
            kind = AssetKind.Ordinary,
            year = 2026,
            month = 6,
            amount = "1200000",
            duration = "12",
            baseAmount = "",
            monthlyIncrease = "",
        )
        store.save()
        settle()
        assertEquals("수아 ISA", repository.added?.first?.name)
        assertEquals(AssetType.Investment, repository.added?.first?.type)
        assertEquals("장기 투자", repository.added?.first?.memo)
        assertEquals(YearMonthKey(2026, 6), repository.added?.second)
        assertTrue(store.currentState.saved)
        assertEquals(YearMonthKey(2026, 6), store.currentState.savedMonth)
        store.close()
    }

    @Test
    fun assetsStorePreservesOverflowInsteadOfPresentingZero() = runBlocking {
        val second = AssetItem(3, "투자금", AssetType.Investment)
        val repository = FakeAssetRepository(
            AssetPortfolio(
                listOf(ordinary, second),
                listOf(AssetValuation(ordinary.id, may, Long.MAX_VALUE), AssetValuation(second.id, may, 1L)),
            ),
        )
        val store = IosAssetsStore(AssetsStateHolder(may, repository, this))
        settle()

        assertTrue(store.currentState.hasTotalOverflow)
        assertNull(store.currentState.totalWon)
        store.close()
    }

    @Test
    fun detailStoreDeletesSelectedConfirmedValuation() = runBlocking {
        val repository = FakeAssetRepository(AssetPortfolio(listOf(ordinary), listOf(AssetValuation(1, may, 100L))))
        val store = IosAssetDetailStore(AssetDetailStateHolder(AssetDetailArgs(ordinary.id, may), repository, this))
        settle()
        store.deleteSelectedValuation()
        settle()
        assertEquals(ordinary.id to may, repository.deletedValuation)
        store.close()
    }

    private suspend fun settle() { repeat(30) { yield() } }
}

private class FakeAssetRepository(initial: AssetPortfolio) : AssetRepository {
    private val data = MutableStateFlow(initial)
    var added: Pair<AssetItem, YearMonthKey>? = null
    var deletedValuation: Pair<Long, YearMonthKey>? = null
    override fun observePortfolio(): Flow<AssetPortfolio> = data
    override suspend fun addAsset(asset: AssetItem, month: YearMonthKey, amountWon: Long): Long {
        added = asset to month
        data.value = data.value.copy(assets = data.value.assets + asset.copy(id = 9), valuations = data.value.valuations + AssetValuation(9, month, amountWon))
        return 9L
    }
    override suspend fun updateAsset(asset: AssetItem) = Unit
    override suspend fun updateAssetWithValuation(asset: AssetItem, valuation: AssetValuation) {
        data.value = data.value.copy(
            assets = data.value.assets.map { if (it.id == asset.id) asset else it },
            valuations = data.value.valuations.filterNot {
                it.assetId == valuation.assetId && it.valuationMonth == valuation.valuationMonth
            } + valuation,
        )
    }
    override suspend fun saveValuation(valuation: AssetValuation) = Unit
    override suspend fun saveGrowthRule(rule: AssetGrowthRule) = Unit
    override suspend fun deleteAsset(assetId: Long) = Unit
    override suspend fun deleteValuation(assetId: Long, month: YearMonthKey) { deletedValuation = assetId to month }
    override suspend fun deleteGrowthRule(assetId: Long) = Unit
}
