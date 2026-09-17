package kr.jm.moalog.core.database

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kr.jm.moalog.core.model.AssetGrowthRule
import kr.jm.moalog.core.model.AssetKind
import kr.jm.moalog.core.model.AssetItem
import kr.jm.moalog.core.model.AssetPortfolio
import kr.jm.moalog.core.model.AssetType
import kr.jm.moalog.core.model.AssetValuation
import kr.jm.moalog.core.model.YearMonthKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

@Entity(
    tableName = "asset_items",
    foreignKeys = [ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("ledgerId")],
)
internal data class AssetItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ledgerId: Long,
    val name: String,
    val type: String,
    val ownerMemberOrder: Int?,
    val memo: String?,
    @ColumnInfo(defaultValue = "'Ordinary'") val kind: String,
)

@Entity(
    tableName = "asset_valuations",
    primaryKeys = ["assetId", "valuationYear", "valuationMonth"],
    foreignKeys = [ForeignKey(entity = AssetItemEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("assetId"), Index(value = ["valuationYear", "valuationMonth"])],
)
internal data class AssetValuationEntity(
    val assetId: Long,
    val valuationYear: Int,
    val valuationMonth: Int,
    val amountWon: Long,
)

@Entity(
    tableName = "asset_growth_rules",
    foreignKeys = [ForeignKey(entity = AssetItemEntity::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("assetId")],
)
internal data class AssetGrowthRuleEntity(
    @PrimaryKey val assetId: Long,
    val startYear: Int,
    val startMonth: Int,
    val durationMonths: Int,
    val baseAmountWon: Long,
    val monthlyIncreaseWon: Long,
)

@Dao
internal interface AssetDao {
    @Query("SELECT * FROM asset_items WHERE ledgerId = 1 ORDER BY id")
    fun observeAssets(): Flow<List<AssetItemEntity>>

    @Query("SELECT * FROM asset_valuations ORDER BY valuationYear, valuationMonth, assetId")
    fun observeValuations(): Flow<List<AssetValuationEntity>>

    @Query("SELECT * FROM asset_growth_rules ORDER BY assetId")
    fun observeGrowthRules(): Flow<List<AssetGrowthRuleEntity>>

    @Insert suspend fun insertAsset(asset: AssetItemEntity): Long
    @Update suspend fun updateAsset(asset: AssetItemEntity): Int
    @Upsert suspend fun upsertValuation(valuation: AssetValuationEntity)
    @Upsert suspend fun upsertGrowthRule(rule: AssetGrowthRuleEntity)
    @Query("DELETE FROM asset_items WHERE id = :assetId") suspend fun deleteAsset(assetId: Long): Int
    @Query("DELETE FROM asset_valuations WHERE assetId = :assetId AND valuationYear = :year AND valuationMonth = :month")
    suspend fun deleteValuation(assetId: Long, year: Int, month: Int): Int
    @Query("DELETE FROM asset_growth_rules WHERE assetId = :assetId") suspend fun deleteGrowthRule(assetId: Long): Int

    @Transaction
    suspend fun insertAssetWithInitial(asset: AssetItemEntity, year: Int, month: Int, amountWon: Long): Long {
        val id = insertAsset(asset)
        upsertValuation(AssetValuationEntity(id, year, month, amountWon))
        return id
    }

    @Transaction
    suspend fun updateAssetWithValuation(asset: AssetItemEntity, valuation: AssetValuationEntity) {
        check(updateAsset(asset) == 1) { "Asset ${asset.id} no longer exists" }
        upsertValuation(valuation)
    }
}

interface AssetLocalDataSource {
    fun observePortfolio(): Flow<AssetPortfolio>
    suspend fun addAsset(asset: AssetItem): Long
    suspend fun addAssetWithInitial(asset: AssetItem, valuationMonth: YearMonthKey, amountWon: Long): Long
    suspend fun updateAsset(asset: AssetItem)
    suspend fun updateAssetWithValuation(asset: AssetItem, valuation: AssetValuation)
    suspend fun saveValuation(valuation: AssetValuation)
    suspend fun saveGrowthRule(rule: AssetGrowthRule)
    suspend fun deleteAsset(assetId: Long)
    suspend fun deleteValuation(assetId: Long, month: YearMonthKey)
    suspend fun deleteGrowthRule(assetId: Long)
}

private class RoomAssetLocalDataSource(private val dao: AssetDao) : AssetLocalDataSource {
    override fun observePortfolio(): Flow<AssetPortfolio> = combine(dao.observeAssets(), dao.observeValuations(), dao.observeGrowthRules()) { assets, valuations, rules ->
        AssetPortfolio(
            assets.map { AssetItem(it.id, it.name, AssetType.valueOf(it.type), it.ownerMemberOrder, it.memo, AssetKind.valueOf(it.kind)) },
            valuations.map { AssetValuation(it.assetId, YearMonthKey(it.valuationYear, it.valuationMonth), it.amountWon) },
            rules.map { AssetGrowthRule(it.assetId, YearMonthKey(it.startYear, it.startMonth), it.durationMonths, it.baseAmountWon, it.monthlyIncreaseWon) },
        )
    }

    override suspend fun addAsset(asset: AssetItem): Long = dao.insertAsset(
        AssetItemEntity(asset.id, 1L, asset.name, asset.type.name, asset.ownerMemberOrder, asset.memo, asset.kind.name),
    )

    override suspend fun addAssetWithInitial(asset: AssetItem, valuationMonth: YearMonthKey, amountWon: Long): Long =
        dao.insertAssetWithInitial(
            AssetItemEntity(asset.id, 1L, asset.name, asset.type.name, asset.ownerMemberOrder, asset.memo, asset.kind.name),
            valuationMonth.year,
            valuationMonth.month,
            amountWon,
        )

    override suspend fun updateAsset(asset: AssetItem) {
        check(dao.updateAsset(AssetItemEntity(asset.id, 1L, asset.name, asset.type.name, asset.ownerMemberOrder, asset.memo, asset.kind.name)) == 1) {
            "Asset no longer exists"
        }
    }

    override suspend fun updateAssetWithValuation(asset: AssetItem, valuation: AssetValuation) {
        require(asset.id == valuation.assetId)
        dao.updateAssetWithValuation(
            AssetItemEntity(asset.id, 1L, asset.name, asset.type.name, asset.ownerMemberOrder, asset.memo, asset.kind.name),
            AssetValuationEntity(valuation.assetId, valuation.valuationMonth.year, valuation.valuationMonth.month, valuation.amountWon),
        )
    }

    override suspend fun saveValuation(valuation: AssetValuation) = dao.upsertValuation(
        AssetValuationEntity(valuation.assetId, valuation.valuationMonth.year, valuation.valuationMonth.month, valuation.amountWon),
    )

    override suspend fun saveGrowthRule(rule: AssetGrowthRule) = dao.upsertGrowthRule(
        AssetGrowthRuleEntity(rule.assetId, rule.startMonth.year, rule.startMonth.month, rule.durationMonths, rule.baseAmountWon, rule.monthlyIncreaseWon),
    )

    override suspend fun deleteAsset(assetId: Long) {
        check(dao.deleteAsset(assetId) == 1) { "Asset $assetId no longer exists" }
    }

    override suspend fun deleteValuation(assetId: Long, month: YearMonthKey) {
        check(dao.deleteValuation(assetId, month.year, month.month) == 1) { "Asset valuation no longer exists" }
    }

    override suspend fun deleteGrowthRule(assetId: Long) {
        check(dao.deleteGrowthRule(assetId) == 1) { "Asset growth rule no longer exists" }
    }
}

fun createAssetLocalDataSource(database: MoaLogDatabase): AssetLocalDataSource = RoomAssetLocalDataSource(database.assetDao())
