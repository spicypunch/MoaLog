package kr.jm.moalog.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kr.jm.moalog.core.model.MaintenanceFeeEntry
import kr.jm.moalog.core.model.MaintenanceFeeItemKey
import kr.jm.moalog.core.model.MaintenanceFeeMonth
import kr.jm.moalog.core.model.YearMonthKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val MAINTENANCE_FEE_LEDGER_ID = 1L

@Entity(
    tableName = "maintenance_fee_entries",
    primaryKeys = ["ledgerId", "billYear", "billMonth", "itemKey"],
    foreignKeys = [
        ForeignKey(
            entity = LedgerEntity::class,
            parentColumns = ["id"],
            childColumns = ["ledgerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["ledgerId", "billYear", "billMonth"])],
)
internal data class MaintenanceFeeEntryEntity(
    val ledgerId: Long,
    val billYear: Int,
    val billMonth: Int,
    val itemKey: String,
    val amountWon: Long?,
)

@Dao
internal interface MaintenanceFeeDao {
    @Query(
        "SELECT * FROM maintenance_fee_entries " +
            "WHERE ledgerId = :ledgerId AND billYear = :year AND billMonth = :month " +
            "ORDER BY CASE itemKey " +
            "WHEN 'GeneralManagement' THEN 0 WHEN 'Cleaning' THEN 1 WHEN 'Disinfection' THEN 2 " +
            "WHEN 'ElevatorMaintenance' THEN 3 WHEN 'RepairMaintenance' THEN 4 WHEN 'LongTermRepairReserve' THEN 5 " +
            "WHEN 'BuildingInsurance' THEN 6 WHEN 'SecurityService' THEN 7 WHEN 'ManagementCommission' THEN 8 " +
            "WHEN 'ResidentsCommittee' THEN 9 WHEN 'ElectionCommittee' THEN 10 WHEN 'HouseholdElectricity' THEN 11 " +
            "WHEN 'CommonElectricity' THEN 12 WHEN 'ElevatorElectricity' THEN 13 WHEN 'TvLicense' THEN 14 " +
            "WHEN 'HouseholdWater' THEN 15 WHEN 'HouseholdHeating' THEN 16 WHEN 'BasicHeating' THEN 17 " +
            "WHEN 'HouseholdHotWater' THEN 18 WHEN 'Deduction' THEN 19 WHEN 'HouseholdWaste' THEN 20 ELSE 21 END",
    )
    fun observeMonth(
        year: Int,
        month: Int,
        ledgerId: Long = MAINTENANCE_FEE_LEDGER_ID,
    ): Flow<List<MaintenanceFeeEntryEntity>>

    @Query(
        "SELECT * FROM maintenance_fee_entries " +
            "WHERE ledgerId = :ledgerId AND (billYear * 100 + billMonth) BETWEEN :startKey AND :endKey " +
            "ORDER BY billYear, billMonth, itemKey",
    )
    fun observeRange(
        startKey: Int,
        endKey: Int,
        ledgerId: Long = MAINTENANCE_FEE_LEDGER_ID,
    ): Flow<List<MaintenanceFeeEntryEntity>>

    @Query("DELETE FROM maintenance_fee_entries WHERE ledgerId = :ledgerId AND billYear = :year AND billMonth = :month")
    suspend fun deleteMonth(ledgerId: Long, year: Int, month: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(values: List<MaintenanceFeeEntryEntity>)

    @Transaction
    suspend fun replaceMonth(month: MaintenanceFeeMonth) {
        deleteMonth(month.ledgerId, month.billMonth.year, month.billMonth.month)
        insertAll(month.entries.map { entry ->
            MaintenanceFeeEntryEntity(
                ledgerId = month.ledgerId,
                billYear = month.billMonth.year,
                billMonth = month.billMonth.month,
                itemKey = entry.key.name,
                amountWon = entry.amountWon,
            )
        })
    }
}

interface MaintenanceFeeLocalDataSource {
    fun observeMonth(month: YearMonthKey): Flow<MaintenanceFeeMonth>
    fun observeRange(startInclusive: YearMonthKey, endInclusive: YearMonthKey): Flow<List<MaintenanceFeeMonth>>
    suspend fun saveMonth(month: MaintenanceFeeMonth)
}

private class RoomMaintenanceFeeLocalDataSource(
    private val dao: MaintenanceFeeDao,
) : MaintenanceFeeLocalDataSource {
    override fun observeMonth(month: YearMonthKey): Flow<MaintenanceFeeMonth> =
        dao.observeMonth(month.year, month.month).map { rows -> rows.toMonth(month) }

    override fun observeRange(
        startInclusive: YearMonthKey,
        endInclusive: YearMonthKey,
    ): Flow<List<MaintenanceFeeMonth>> {
        val months = monthsBetween(startInclusive, endInclusive)
        return dao.observeRange(startInclusive.databaseKey(), endInclusive.databaseKey()).map { rows ->
            val rowsByMonth = rows.groupBy { YearMonthKey(it.billYear, it.billMonth) }
            months.map { month -> rowsByMonth[month].orEmpty().toMonth(month) }
        }
    }

    override suspend fun saveMonth(month: MaintenanceFeeMonth) = dao.replaceMonth(month)
}

fun createMaintenanceFeeLocalDataSource(database: MoaLogDatabase): MaintenanceFeeLocalDataSource =
    RoomMaintenanceFeeLocalDataSource(database.maintenanceFeeDao())

private fun List<MaintenanceFeeEntryEntity>.toMonth(month: YearMonthKey): MaintenanceFeeMonth {
    val amounts = associate { row -> MaintenanceFeeItemKey.valueOf(row.itemKey) to row.amountWon }
    return MaintenanceFeeMonth.fromAmounts(month, amounts)
}

private fun monthsBetween(start: YearMonthKey, end: YearMonthKey): List<YearMonthKey> {
    require(start.databaseKey() <= end.databaseKey()) { "Range start must not be after end" }
    return buildList {
        var current = start
        while (true) {
            add(current)
            if (current == end) break
            current = current.plusMonths(1)
        }
    }
}

private fun YearMonthKey.databaseKey(): Int = year * 100 + month
