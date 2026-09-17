package kr.jm.moalog.feature.assets.domain

import kr.jm.moalog.core.model.*
import kotlinx.coroutines.flow.Flow

interface AssetRepository {
    fun observePortfolio(): Flow<AssetPortfolio>
    suspend fun addAsset(asset: AssetItem, month: YearMonthKey, amountWon: Long): Long
    suspend fun updateAsset(asset: AssetItem)
    /** Updates metadata and its confirmed valuation in one atomic persistence operation. */
    suspend fun updateAssetWithValuation(asset: AssetItem, valuation: AssetValuation)
    suspend fun saveValuation(valuation: AssetValuation)
    suspend fun saveGrowthRule(rule: AssetGrowthRule)
    suspend fun deleteAsset(assetId: Long): Unit = error("Asset deletion is not implemented")
    suspend fun deleteValuation(assetId: Long, month: YearMonthKey): Unit = error("Valuation deletion is not implemented")
    suspend fun deleteGrowthRule(assetId: Long): Unit = error("Growth-rule deletion is not implemented")
}
