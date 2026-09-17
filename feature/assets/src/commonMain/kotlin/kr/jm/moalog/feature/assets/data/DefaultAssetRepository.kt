package kr.jm.moalog.feature.assets.data

import kr.jm.moalog.core.database.AssetLocalDataSource
import kr.jm.moalog.feature.assets.domain.AssetRepository
import kr.jm.moalog.core.model.*

internal class DefaultAssetRepository(private val localDataSource: AssetLocalDataSource) : AssetRepository {
    override fun observePortfolio() = localDataSource.observePortfolio()
    override suspend fun addAsset(asset: AssetItem, month: YearMonthKey, amountWon: Long) = localDataSource.addAssetWithInitial(asset, month, amountWon)
    override suspend fun updateAsset(asset: AssetItem) = localDataSource.updateAsset(asset)
    override suspend fun updateAssetWithValuation(asset: AssetItem, valuation: AssetValuation) = localDataSource.updateAssetWithValuation(asset, valuation)
    override suspend fun saveValuation(valuation: AssetValuation) = localDataSource.saveValuation(valuation)
    override suspend fun saveGrowthRule(rule: AssetGrowthRule) = localDataSource.saveGrowthRule(rule)
    override suspend fun deleteAsset(assetId: Long) = localDataSource.deleteAsset(assetId)
    override suspend fun deleteValuation(assetId: Long, month: YearMonthKey) = localDataSource.deleteValuation(assetId, month)
    override suspend fun deleteGrowthRule(assetId: Long) = localDataSource.deleteGrowthRule(assetId)
}
