package kr.jm.moalog.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kr.jm.moalog.core.model.ExpenseCategory
import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.LocalDateKey
import kr.jm.moalog.core.model.YearMonthKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private const val EXPENSE_LOCAL_LEDGER_ID = 1L

@Entity(
    tableName = "expense_categories",
    foreignKeys = [ForeignKey(
        entity = LedgerEntity::class,
        parentColumns = ["id"],
        childColumns = ["ledgerId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("ledgerId")],
)
internal data class ExpenseCategoryEntity(
    @PrimaryKey val id: String,
    val ledgerId: Long,
    val name: String,
    val displayOrder: Int,
    val archived: Boolean = false,
)

@Entity(
    tableName = "expense_records",
    foreignKeys = [
        ForeignKey(
            entity = LedgerEntity::class,
            parentColumns = ["id"],
            childColumns = ["ledgerId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ExpenseCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["ledgerId", "attributionYear", "attributionMonth"]), Index("categoryId")],
)
internal data class ExpenseRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ledgerId: Long,
    val categoryId: String,
    val attributionYear: Int,
    val attributionMonth: Int,
    val actualDate: String?,
    val detail: String?,
    val amountWon: Long,
    val overspent: Boolean,
)

internal data class ExpenseRecordRow(
    val id: Long,
    val ledgerId: Long,
    val categoryId: String,
    val categoryName: String,
    val attributionYear: Int,
    val attributionMonth: Int,
    val actualDate: String?,
    val detail: String?,
    val amountWon: Long,
    val overspent: Boolean,
)

@Dao
internal interface ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategories(categories: List<ExpenseCategoryEntity>)

    @Query("SELECT * FROM expense_categories WHERE ledgerId = :ledgerId AND archived = 0 ORDER BY displayOrder, id")
    fun observeCategories(ledgerId: Long = EXPENSE_LOCAL_LEDGER_ID): Flow<List<ExpenseCategoryEntity>>

    @Query("SELECT * FROM expense_categories WHERE ledgerId = :ledgerId ORDER BY archived, displayOrder, id")
    fun observeAllCategories(ledgerId: Long = EXPENSE_LOCAL_LEDGER_ID): Flow<List<ExpenseCategoryEntity>>

    @Query("SELECT * FROM expense_categories WHERE ledgerId = :ledgerId ORDER BY archived, displayOrder, id")
    suspend fun loadAllCategories(ledgerId: Long): List<ExpenseCategoryEntity>

    @Query("UPDATE expense_categories SET name = :name WHERE id = :categoryId AND ledgerId = :ledgerId")
    suspend fun renameCategory(categoryId: String, name: String, ledgerId: Long): Int

    @Query("UPDATE expense_categories SET archived = :archived WHERE id = :categoryId AND ledgerId = :ledgerId")
    suspend fun setCategoryArchived(categoryId: String, archived: Boolean, ledgerId: Long): Int

    @Query("UPDATE expense_categories SET displayOrder = :displayOrder WHERE id = :categoryId AND ledgerId = :ledgerId")
    suspend fun setCategoryOrder(categoryId: String, displayOrder: Int, ledgerId: Long): Int

    @Insert
    suspend fun insertCategory(category: ExpenseCategoryEntity)

    @Transaction
    suspend fun createCategory(ledgerId: Long, name: String): ExpenseCategoryEntity {
        val all = loadAllCategories(ledgerId)
        val idPrefix = "custom-$ledgerId-"
        val nextNumber = all.mapNotNull { it.id.removePrefix(idPrefix).toIntOrNull() }.maxOrNull()?.plus(1) ?: 1
        val created = ExpenseCategoryEntity(
            id = "$idPrefix$nextNumber",
            ledgerId = ledgerId,
            name = name,
            displayOrder = (all.filter { !it.archived }.maxOfOrNull { it.displayOrder } ?: -1) + 1,
            archived = false,
        )
        insertCategory(created)
        return created
    }

    @Query("""
        SELECT r.*, c.name AS categoryName FROM expense_records r
        INNER JOIN expense_categories c ON c.id = r.categoryId
        WHERE r.ledgerId = :ledgerId AND r.attributionYear = :year AND r.attributionMonth = :month
    """)
    fun observeRecords(year: Int, month: Int, ledgerId: Long = EXPENSE_LOCAL_LEDGER_ID): Flow<List<ExpenseRecordRow>>

    @Query("""
        SELECT r.*, c.name AS categoryName FROM expense_records r
        INNER JOIN expense_categories c ON c.id = r.categoryId
        WHERE r.id = :id LIMIT 1
    """)
    suspend fun findRecord(id: Long): ExpenseRecordRow?

    @Insert
    suspend fun insertRecord(record: ExpenseRecordEntity): Long

    @Update
    suspend fun updateRecord(record: ExpenseRecordEntity): Int

    @Query("DELETE FROM expense_records WHERE id = :id")
    suspend fun deleteRecord(id: Long): Int

    @Transaction
    suspend fun saveRecord(record: ExpenseRecordEntity): Long =
        if (record.id == 0L) insertRecord(record) else {
            check(updateRecord(record) == 1) { "Expense record ${record.id} no longer exists" }
            record.id
        }

    @Transaction
    suspend fun reorderActiveCategories(ledgerId: Long, orderedIds: List<String>) {
        require(orderedIds.isNotEmpty() && orderedIds.distinct().size == orderedIds.size)
        val active = loadAllCategories(ledgerId).filter { !it.archived }
        require(active.map { it.id }.toSet() == orderedIds.toSet()) { "Active expense category order is stale" }
        orderedIds.forEachIndexed { index, id -> check(setCategoryOrder(id, index, ledgerId) == 1) }
    }
}

val DefaultExpenseCategories = listOf(
    ExpenseCategory("default-living-overrun", "생활비 초과", 0),
    ExpenseCategory("default-date", "데이트비", 1),
    ExpenseCategory("default-travel", "여행비", 2),
    ExpenseCategory("default-family-event", "경조사비", 3),
    ExpenseCategory("default-parents", "효도비", 4),
    ExpenseCategory("default-other-budget", "기타 예산", 5),
)

data class ExpenseMonthSnapshot(
    val categories: List<ExpenseCategory>,
    val records: List<ExpenseRecord>,
)

interface ExpenseLocalDataSource {
    suspend fun ensureDefaultCategories()
    fun observeMonth(month: YearMonthKey): Flow<ExpenseMonthSnapshot>
    suspend fun findRecord(id: Long): ExpenseRecord?
    suspend fun saveRecord(record: ExpenseRecord): Long
    suspend fun deleteRecord(id: Long)
    suspend fun renameCategory(categoryId: String, name: String)
    fun observeAllCategories(): Flow<List<ExpenseCategory>> = flowOf(emptyList())
    suspend fun saveCategory(category: ExpenseCategory): Unit = error("Category management is unavailable")
    suspend fun createCategory(name: String): ExpenseCategory = error("Category management is unavailable")
    suspend fun setCategoryArchived(categoryId: String, archived: Boolean): Unit = error("Category management is unavailable")
    suspend fun reorderCategories(orderedIds: List<String>): Unit = error("Category management is unavailable")
}

private class RoomExpenseLocalDataSource(private val dao: ExpenseDao) : ExpenseLocalDataSource {
    override suspend fun ensureDefaultCategories() {
        dao.insertCategories(DefaultExpenseCategories.map { ExpenseCategoryEntity(it.id, EXPENSE_LOCAL_LEDGER_ID, it.name, it.displayOrder, false) })
    }

    override fun observeMonth(month: YearMonthKey): Flow<ExpenseMonthSnapshot> = combine(
        dao.observeAllCategories(),
        dao.observeRecords(month.year, month.month),
    ) { categories, records ->
        val referenced = records.map { it.categoryId }.toSet()
        ExpenseMonthSnapshot(
            categories.filter { !it.archived || it.id in referenced }
                .map { ExpenseCategory(it.id, it.name, it.displayOrder, it.archived, it.ledgerId) },
            records.map(ExpenseRecordRow::toModel),
        )
    }

    override suspend fun findRecord(id: Long): ExpenseRecord? = dao.findRecord(id)?.toModel()

    override suspend fun saveRecord(record: ExpenseRecord): Long = dao.saveRecord(record.toEntity())

    override suspend fun deleteRecord(id: Long) {
        check(dao.deleteRecord(id) == 1) { "Expense record $id no longer exists" }
    }

    override suspend fun renameCategory(categoryId: String, name: String) {
        check(dao.renameCategory(categoryId, name.trim(), EXPENSE_LOCAL_LEDGER_ID) == 1) { "Expense category $categoryId no longer exists" }
    }

    override fun observeAllCategories() = dao.observeAllCategories().map { rows ->
        rows.map { ExpenseCategory(it.id, it.name, it.displayOrder, it.archived, it.ledgerId) }
    }

    override suspend fun saveCategory(category: ExpenseCategory) {
        require(category.ledgerId == EXPENSE_LOCAL_LEDGER_ID)
        dao.insertCategory(ExpenseCategoryEntity(category.id, category.ledgerId, category.name.trim(), category.displayOrder, category.archived))
    }

    override suspend fun createCategory(name: String): ExpenseCategory {
        val value = dao.createCategory(EXPENSE_LOCAL_LEDGER_ID, name.trim())
        return ExpenseCategory(value.id, value.name, value.displayOrder, value.archived, value.ledgerId)
    }

    override suspend fun setCategoryArchived(categoryId: String, archived: Boolean) {
        check(dao.setCategoryArchived(categoryId, archived, EXPENSE_LOCAL_LEDGER_ID) == 1) { "Expense category $categoryId no longer exists" }
    }

    override suspend fun reorderCategories(orderedIds: List<String>) =
        dao.reorderActiveCategories(EXPENSE_LOCAL_LEDGER_ID, orderedIds)
}

fun createExpenseLocalDataSource(database: MoaLogDatabase): ExpenseLocalDataSource =
    RoomExpenseLocalDataSource(database.expenseDao())

private fun ExpenseRecordRow.toModel() = ExpenseRecord(
    id = id,
    ledgerId = ledgerId,
    categoryId = categoryId,
    categoryName = categoryName,
    attributionMonth = YearMonthKey(attributionYear, attributionMonth),
    actualDate = actualDate?.toLocalDateKey(),
    detail = detail,
    amountWon = amountWon,
    overspent = overspent,
)

private fun ExpenseRecord.toEntity() = ExpenseRecordEntity(
    id = id,
    ledgerId = ledgerId,
    categoryId = categoryId,
    attributionYear = attributionMonth.year,
    attributionMonth = attributionMonth.month,
    actualDate = actualDate?.toString(),
    detail = detail,
    amountWon = amountWon,
    overspent = overspent,
)

private fun String.toLocalDateKey(): LocalDateKey {
    val parts = split('-')
    return LocalDateKey(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `expense_categories` (
                `id` TEXT NOT NULL,
                `ledgerId` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `displayOrder` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_categories_ledgerId` ON `expense_categories` (`ledgerId`)")
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `expense_records` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `ledgerId` INTEGER NOT NULL,
                `categoryId` TEXT NOT NULL,
                `attributionYear` INTEGER NOT NULL,
                `attributionMonth` INTEGER NOT NULL,
                `actualDate` TEXT,
                `detail` TEXT,
                `amountWon` INTEGER NOT NULL,
                `overspent` INTEGER NOT NULL,
                FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`categoryId`) REFERENCES `expense_categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
        """.trimIndent())
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_records_ledgerId_attributionYear_attributionMonth` ON `expense_records` (`ledgerId`, `attributionYear`, `attributionMonth`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_records_categoryId` ON `expense_records` (`categoryId`)")
    }
}
