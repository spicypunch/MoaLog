package kr.jm.moalog.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kr.jm.moalog.core.model.FixedCostCheckItem
import kr.jm.moalog.core.model.FixedCostCheckSheet
import kr.jm.moalog.core.model.YearMonthKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val FIXED_COST_LEDGER_ID = 1L

@Entity(
    tableName = "fixed_cost_check_items",
    foreignKeys = [ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["ledgerId", "attributionYear", "attributionMonth"])],
)
internal data class FixedCostCheckItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ledgerId: Long,
    val attributionYear: Int,
    val attributionMonth: Int,
    val payerMemberOrder: Int?,
    val name: String,
    val amountWon: Long?,
    val displayOrder: Int,
)

@Dao
internal interface FixedCostCheckDao {
    @Query("SELECT * FROM fixed_cost_check_items WHERE ledgerId = :ledgerId AND attributionYear = :year AND attributionMonth = :month ORDER BY displayOrder, id")
    fun observeMonth(year: Int, month: Int, ledgerId: Long = FIXED_COST_LEDGER_ID): Flow<List<FixedCostCheckItemEntity>>

    @Query("SELECT * FROM fixed_cost_check_items WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): FixedCostCheckItemEntity?

    @Insert suspend fun insert(value: FixedCostCheckItemEntity): Long
    @Update suspend fun update(value: FixedCostCheckItemEntity)
    @Query("DELETE FROM fixed_cost_check_items WHERE id = :id") suspend fun delete(id: Long)

    @Transaction
    suspend fun save(value: FixedCostCheckItemEntity): Long = if (value.id == 0L) insert(value) else {
        update(value)
        value.id
    }
}

interface FixedCostCheckLocalDataSource {
    fun observe(month: YearMonthKey): Flow<FixedCostCheckSheet>
    suspend fun find(id: Long): FixedCostCheckItem?
    suspend fun save(value: FixedCostCheckItem): Long
    suspend fun delete(id: Long)
}

private class RoomFixedCostCheckLocalDataSource(private val dao: FixedCostCheckDao) : FixedCostCheckLocalDataSource {
    override fun observe(month: YearMonthKey): Flow<FixedCostCheckSheet> =
        dao.observeMonth(month.year, month.month).map { rows -> FixedCostCheckSheet(month, rows.map { it.toModel() }) }

    override suspend fun find(id: Long) = dao.find(id)?.toModel()
    override suspend fun save(value: FixedCostCheckItem) = dao.save(value.toEntity())
    override suspend fun delete(id: Long) = dao.delete(id)
}

fun createFixedCostCheckLocalDataSource(database: MoaLogDatabase): FixedCostCheckLocalDataSource =
    RoomFixedCostCheckLocalDataSource(database.fixedCostCheckDao())

private fun FixedCostCheckItemEntity.toModel() = FixedCostCheckItem(
    id, ledgerId, YearMonthKey(attributionYear, attributionMonth), payerMemberOrder, name, amountWon, displayOrder,
)

private fun FixedCostCheckItem.toEntity() = FixedCostCheckItemEntity(
    id, ledgerId, attributionMonth.year, attributionMonth.month, payerMemberOrder, name, amountWon, displayOrder,
)
