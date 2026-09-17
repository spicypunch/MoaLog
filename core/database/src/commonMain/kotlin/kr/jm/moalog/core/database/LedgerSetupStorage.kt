package kr.jm.moalog.core.database

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.LedgerSetup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

private const val LOCAL_LEDGER_ID = 1L

@Entity(tableName = "ledgers")
internal data class LedgerEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val baseYear: Int,
    val annualSavingsTargetWon: Long?,
)

@Entity(
    tableName = "ledger_members",
    foreignKeys = [ForeignKey(
        entity = LedgerEntity::class,
        parentColumns = ["id"],
        childColumns = ["ledgerId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("ledgerId"), Index(value = ["ledgerId", "displayOrder"], unique = true)],
)
internal data class LedgerMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ledgerId: Long,
    val displayName: String,
    val displayOrder: Int,
)

@Entity(
    tableName = "annual_savings_targets",
    primaryKeys = ["ledgerId", "year"],
    foreignKeys = [ForeignKey(
        entity = LedgerEntity::class,
        parentColumns = ["id"],
        childColumns = ["ledgerId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("ledgerId")],
)
internal data class AnnualSavingsTargetEntity(
    val ledgerId: Long,
    val year: Int,
    val amountWon: Long,
)

internal data class LedgerWithMembers(
    @Embedded val ledger: LedgerEntity,
    @Relation(parentColumn = "id", entityColumn = "ledgerId")
    val members: List<LedgerMemberEntity>,
    @Relation(parentColumn = "id", entityColumn = "ledgerId")
    val savingsTargets: List<AnnualSavingsTargetEntity>,
)

@Dao
internal interface LedgerSetupDao {
    @Transaction
    @Query("SELECT * FROM ledgers WHERE id = :ledgerId LIMIT 1")
    fun observe(ledgerId: Long = LOCAL_LEDGER_ID): Flow<LedgerWithMembers?>

    @Upsert
    suspend fun upsertLedger(ledger: LedgerEntity)

    @Query("DELETE FROM ledger_members WHERE ledgerId = :ledgerId")
    suspend fun deleteMembers(ledgerId: Long)

    @Insert
    suspend fun insertMembers(members: List<LedgerMemberEntity>)

    @Upsert
    suspend fun upsertSavingsTargets(targets: List<AnnualSavingsTargetEntity>)

    @Query("DELETE FROM annual_savings_targets WHERE ledgerId = :ledgerId")
    suspend fun deleteSavingsTargets(ledgerId: Long)

    @Upsert
    suspend fun upsertSavingsTarget(target: AnnualSavingsTargetEntity)

    @Query("DELETE FROM annual_savings_targets WHERE ledgerId = :ledgerId AND year = :year")
    suspend fun deleteSavingsTarget(ledgerId: Long, year: Int)

    @Query("UPDATE ledgers SET annualSavingsTargetWon = :amountWon WHERE id = :ledgerId AND baseYear = :year")
    suspend fun updateLegacyBaseYearTarget(ledgerId: Long, year: Int, amountWon: Long?)

    @Transaction
    suspend fun save(setup: LedgerSetup) {
        require(setup.members.size == 2)
        upsertLedger(LedgerEntity(LOCAL_LEDGER_ID, setup.ledgerName, setup.baseYear, setup.savingsTargetFor(setup.baseYear)))
        deleteMembers(LOCAL_LEDGER_ID)
        insertMembers(setup.members.sortedBy(LedgerMember::order).map { member ->
            LedgerMemberEntity(
                ledgerId = LOCAL_LEDGER_ID,
                displayName = member.displayName,
                displayOrder = member.order,
            )
        })
        deleteSavingsTargets(LOCAL_LEDGER_ID)
        upsertSavingsTargets(setup.annualSavingsTargetsWon.map { (year, amountWon) ->
            AnnualSavingsTargetEntity(LOCAL_LEDGER_ID, year, amountWon)
        })
    }

    @Transaction
    suspend fun saveAnnualSavingsTarget(year: Int, targetWon: Long?) {
        require(year in 1900..9999)
        require(targetWon == null || targetWon >= 0L)
        if (targetWon == null) deleteSavingsTarget(LOCAL_LEDGER_ID, year)
        else upsertSavingsTarget(AnnualSavingsTargetEntity(LOCAL_LEDGER_ID, year, targetWon))
        updateLegacyBaseYearTarget(LOCAL_LEDGER_ID, year, targetWon)
    }
}

interface LedgerSetupLocalDataSource {
    fun observe(): Flow<LedgerSetup?>
    suspend fun save(setup: LedgerSetup)
    suspend fun saveAnnualSavingsTarget(year: Int, targetWon: Long?) {
        val current = observe().first() ?: error("가계부 설정을 찾을 수 없어요")
        val targets = current.annualSavingsTargetsWon.toMutableMap().apply {
            if (targetWon == null) remove(year) else put(year, targetWon)
        }
        save(current.copy(
            annualSavingsTargetWon = targets[current.baseYear],
            annualSavingsTargetsWon = targets,
        ))
    }
}

private class RoomLedgerSetupLocalDataSource(
    private val dao: LedgerSetupDao,
) : LedgerSetupLocalDataSource {
    override fun observe(): Flow<LedgerSetup?> = dao.observe().map { record ->
        record?.let {
            LedgerSetup(
                ledgerName = it.ledger.name,
                members = it.members.sortedBy(LedgerMemberEntity::displayOrder).map { member ->
                    LedgerMember(member.displayName, member.displayOrder)
                },
                baseYear = it.ledger.baseYear,
                annualSavingsTargetWon = it.savingsTargets.firstOrNull { target -> target.year == it.ledger.baseYear }?.amountWon
                    ?: it.ledger.annualSavingsTargetWon,
                annualSavingsTargetsWon = buildMap {
                    it.savingsTargets.forEach { target -> put(target.year, target.amountWon) }
                    if (it.ledger.annualSavingsTargetWon != null && it.ledger.baseYear !in this) {
                        put(it.ledger.baseYear, it.ledger.annualSavingsTargetWon)
                    }
                },
            )
        }
    }

    override suspend fun save(setup: LedgerSetup) = dao.save(setup)
    override suspend fun saveAnnualSavingsTarget(year: Int, targetWon: Long?) = dao.saveAnnualSavingsTarget(year, targetWon)
}

fun createLedgerSetupLocalDataSource(database: MoaLogDatabase): LedgerSetupLocalDataSource =
    RoomLedgerSetupLocalDataSource(database.ledgerSetupDao())
