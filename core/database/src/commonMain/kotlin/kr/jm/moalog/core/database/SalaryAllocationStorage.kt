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
import kr.jm.moalog.core.model.SalaryAllocationCategory
import kr.jm.moalog.core.model.SalaryAllocationChild
import kr.jm.moalog.core.model.SalaryAllocationMethod
import kr.jm.moalog.core.model.SalaryAllocationGrandchild
import kr.jm.moalog.core.model.SalaryAllocationSheet
import kr.jm.moalog.core.model.SalaryIncome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

private const val LOCAL_LEDGER_ID = 1L

@Entity(tableName = "salary_incomes", primaryKeys = ["ledgerId", "attributionYear", "attributionMonth", "memberOrder"], foreignKeys = [ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["ledgerId", "attributionYear", "attributionMonth"])])
internal data class SalaryIncomeEntity(val ledgerId: Long, val attributionYear: Int, val attributionMonth: Int, val memberOrder: Int, val amountWon: Long?)

@Entity(tableName = "salary_allocation_categories", foreignKeys = [ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["ledgerId", "attributionYear", "attributionMonth"])])
internal data class SalaryAllocationCategoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val ledgerId: Long, val attributionYear: Int, val attributionMonth: Int, val name: String, val sourceMemberOrder: Int?, val method: String, val amountWon: Long?, val rateBasisPoints: Int?, val memo: String?, val displayOrder: Int, val deductedCategoryIds: String? = null)

@Entity(tableName = "salary_allocation_children", foreignKeys = [ForeignKey(entity = SalaryAllocationCategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE)], indices = [Index("categoryId")])
internal data class SalaryAllocationChildEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val categoryId: Long, val attributionYear: Int, val attributionMonth: Int, val name: String, val amountWon: Long?, val memo: String?, val displayOrder: Int)

@Entity(tableName = "salary_allocation_grandchildren", foreignKeys = [ForeignKey(entity = SalaryAllocationChildEntity::class, parentColumns = ["id"], childColumns = ["childId"], onDelete = ForeignKey.CASCADE)], indices = [Index("childId")])
internal data class SalaryAllocationGrandchildEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val childId: Long, val attributionYear: Int, val attributionMonth: Int, val name: String, val amountWon: Long?, val memo: String?, val displayOrder: Int)

@Dao
internal interface SalaryAllocationDao {
    @Query("SELECT * FROM salary_incomes WHERE ledgerId = :ledgerId AND attributionYear = :year AND attributionMonth = :month ORDER BY memberOrder") fun observeIncomes(year: Int, month: Int, ledgerId: Long = LOCAL_LEDGER_ID): Flow<List<SalaryIncomeEntity>>
    @Query("SELECT * FROM salary_allocation_categories WHERE ledgerId = :ledgerId AND attributionYear = :year AND attributionMonth = :month ORDER BY displayOrder, id") fun observeCategories(year: Int, month: Int, ledgerId: Long = LOCAL_LEDGER_ID): Flow<List<SalaryAllocationCategoryEntity>>
    @Query("SELECT * FROM salary_allocation_children WHERE attributionYear = :year AND attributionMonth = :month ORDER BY displayOrder, id") fun observeChildren(year: Int, month: Int): Flow<List<SalaryAllocationChildEntity>>
    @Query("SELECT * FROM salary_allocation_grandchildren WHERE attributionYear = :year AND attributionMonth = :month ORDER BY displayOrder, id") fun observeGrandchildren(year: Int, month: Int): Flow<List<SalaryAllocationGrandchildEntity>>
    @Query("SELECT * FROM salary_allocation_categories WHERE id = :id") suspend fun findCategory(id: Long): SalaryAllocationCategoryEntity?
    @Query("SELECT * FROM salary_allocation_children WHERE id = :id") suspend fun findChild(id: Long): SalaryAllocationChildEntity?
    @Query("SELECT * FROM salary_allocation_grandchildren WHERE id = :id") suspend fun findGrandchild(id: Long): SalaryAllocationGrandchildEntity?
    @Query("SELECT COUNT(*) FROM salary_allocation_grandchildren WHERE childId = :childId") suspend fun countGrandchildren(childId: Long): Int
    @Insert suspend fun insertCategory(value: SalaryAllocationCategoryEntity): Long
    @Update suspend fun updateCategory(value: SalaryAllocationCategoryEntity)
    @Insert suspend fun insertChild(value: SalaryAllocationChildEntity): Long
    @Update suspend fun updateChild(value: SalaryAllocationChildEntity)
    @Insert suspend fun insertGrandchild(value: SalaryAllocationGrandchildEntity): Long
    @Update suspend fun updateGrandchild(value: SalaryAllocationGrandchildEntity)
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun saveIncome(value: SalaryIncomeEntity)
    @Query("DELETE FROM salary_incomes WHERE ledgerId = :ledgerId AND attributionYear = :year AND attributionMonth = :month") suspend fun deleteIncomes(ledgerId:Long,year:Int,month:Int)
    @Transaction suspend fun saveIncomes(values: List<SalaryIncomeEntity>) {
        require(values.isNotEmpty())
        val first=values.first()
        require(values.all { it.ledgerId==first.ledgerId && it.attributionYear==first.attributionYear && it.attributionMonth==first.attributionMonth })
        require(values.map { it.memberOrder }.distinct().size==values.size)
        deleteIncomes(first.ledgerId,first.attributionYear,first.attributionMonth)
        values.forEach { saveIncome(it) }
    }
    @Query("DELETE FROM salary_allocation_categories WHERE id = :id") suspend fun deleteCategory(id: Long)
    @Query("DELETE FROM salary_allocation_children WHERE id = :id") suspend fun deleteChild(id: Long)
    @Query("DELETE FROM salary_allocation_grandchildren WHERE id = :id") suspend fun deleteGrandchild(id: Long)
    @Transaction suspend fun saveCategory(value: SalaryAllocationCategoryEntity): Long = if (value.id == 0L) insertCategory(value) else { updateCategory(value); value.id }
    @Transaction suspend fun saveChild(value: SalaryAllocationChildEntity): Long = if (value.id == 0L) {
        insertChild(value)
    } else {
        updateChild(if (countGrandchildren(value.id) > 0) value.copy(amountWon = null) else value)
        value.id
    }
    @Transaction suspend fun saveGrandchild(value: SalaryAllocationGrandchildEntity): Long = if (value.id == 0L) {
        val parent = requireNotNull(findChild(value.childId)) { "상위 배분 항목을 찾을 수 없어요" }
        require(parent.attributionYear == value.attributionYear && parent.attributionMonth == value.attributionMonth) {
            "상위 항목과 같은 달의 소분류만 저장할 수 있어요"
        }
        if (countGrandchildren(value.childId) == 0 && parent.amountWon != null) {
            updateChild(parent.copy(amountWon = null))
        }
        insertGrandchild(value)
    } else {
        updateGrandchild(value)
        value.id
    }
}

interface SalaryAllocationLocalDataSource {
    fun observe(month: kr.jm.moalog.core.model.YearMonthKey): Flow<SalaryAllocationSheet>
    suspend fun saveIncomes(values: List<SalaryIncome>)
    suspend fun findCategory(id: Long): SalaryAllocationCategory?
    suspend fun saveCategory(value: SalaryAllocationCategory): Long
    suspend fun deleteCategory(id: Long)
    suspend fun findChild(id: Long): SalaryAllocationChild?
    suspend fun saveChild(value: SalaryAllocationChild): Long
    suspend fun deleteChild(id: Long)
    suspend fun findGrandchild(id: Long): SalaryAllocationGrandchild?
    suspend fun saveGrandchild(value: SalaryAllocationGrandchild): Long
    suspend fun deleteGrandchild(id: Long)
}

private class RoomSalaryAllocationLocalDataSource(private val dao: SalaryAllocationDao) : SalaryAllocationLocalDataSource {
    override fun observe(month: kr.jm.moalog.core.model.YearMonthKey) = combine(dao.observeIncomes(month.year, month.month), dao.observeCategories(month.year, month.month), dao.observeChildren(month.year, month.month), dao.observeGrandchildren(month.year, month.month)) { incomes, categories, children, grandchildren ->
        SalaryAllocationSheet(month, incomes.map { SalaryIncome(month, it.memberOrder, it.amountWon) }, categories.map { row -> row.toModel(children.filter { it.categoryId == row.id },grandchildren) })
    }
    override suspend fun saveIncomes(values: List<SalaryIncome>) = dao.saveIncomes(values.map { SalaryIncomeEntity(LOCAL_LEDGER_ID, it.attributionMonth.year, it.attributionMonth.month, it.memberOrder, it.amountWon) })
    override suspend fun findCategory(id: Long) = dao.findCategory(id)?.toModel(emptyList(),emptyList())
    override suspend fun saveCategory(value: SalaryAllocationCategory) = dao.saveCategory(value.toEntity())
    override suspend fun deleteCategory(id: Long) = dao.deleteCategory(id)
    override suspend fun findChild(id: Long) = dao.findChild(id)?.toModel(emptyList())
    override suspend fun saveChild(value: SalaryAllocationChild) = dao.saveChild(value.toEntity())
    override suspend fun deleteChild(id: Long) = dao.deleteChild(id)
    override suspend fun findGrandchild(id:Long)=dao.findGrandchild(id)?.toModel()
    override suspend fun saveGrandchild(value:SalaryAllocationGrandchild)=dao.saveGrandchild(value.toEntity())
    override suspend fun deleteGrandchild(id:Long)=dao.deleteGrandchild(id)
}

fun createSalaryAllocationLocalDataSource(database: MoaLogDatabase): SalaryAllocationLocalDataSource = RoomSalaryAllocationLocalDataSource(database.salaryAllocationDao())

private fun SalaryAllocationCategoryEntity.toModel(children: List<SalaryAllocationChildEntity>,grandchildren:List<SalaryAllocationGrandchildEntity>) = SalaryAllocationCategory(id, ledgerId, kr.jm.moalog.core.model.YearMonthKey(attributionYear, attributionMonth), name, sourceMemberOrder, SalaryAllocationMethod.valueOf(method), amountWon, rateBasisPoints, memo, displayOrder, children.map { child -> child.toModel(grandchildren.filter { it.childId==child.id }) }, parseIds(deductedCategoryIds))
private fun SalaryAllocationChildEntity.toModel(grandchildren:List<SalaryAllocationGrandchildEntity>) = SalaryAllocationChild(id, categoryId, kr.jm.moalog.core.model.YearMonthKey(attributionYear, attributionMonth), name, amountWon, memo, displayOrder,grandchildren.map { it.toModel() })
private fun SalaryAllocationGrandchildEntity.toModel()=SalaryAllocationGrandchild(id,childId,kr.jm.moalog.core.model.YearMonthKey(attributionYear,attributionMonth),name,amountWon,memo,displayOrder)
private fun SalaryAllocationCategory.toEntity() = SalaryAllocationCategoryEntity(id, ledgerId, attributionMonth.year, attributionMonth.month, name, sourceMemberOrder, method.name, amountWon, rateBasisPoints, memo, displayOrder,deductedCategoryIds?.sorted()?.joinToString(","))
private fun SalaryAllocationChild.toEntity() = SalaryAllocationChildEntity(id, categoryId, attributionMonth.year, attributionMonth.month, name, amountWon, memo, displayOrder)
private fun SalaryAllocationGrandchild.toEntity()=SalaryAllocationGrandchildEntity(id,childId,attributionMonth.year,attributionMonth.month,name,amountWon,memo,displayOrder)
private fun parseIds(value:String?):Set<Long>?=value?.takeIf { it.isNotBlank() }?.split(',')?.mapNotNull(String::toLongOrNull)?.toSet() ?: value?.let { emptySet() }
