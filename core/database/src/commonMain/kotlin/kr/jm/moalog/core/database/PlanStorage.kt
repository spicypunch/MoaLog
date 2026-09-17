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
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanCatalogItem
import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private const val PLAN_LOCAL_LEDGER_ID = 1L

@Entity(
    tableName = "plan_item_catalog",
    foreignKeys = [ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("ledgerId"), Index(value = ["ledgerId", "type", "archived", "displayOrder"])],
)
internal data class PlanCatalogItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ledgerId: Long,
    val type: String,
    val classification: String,
    val name: String,
    val ownerMemberOrder: Int?,
    val includePurposeAccount: Boolean,
    val includeNetSavings: Boolean,
    val displayOrder: Int,
    val archived: Boolean,
)

@Entity(
    tableName = "monthly_plan_items",
    foreignKeys = [
        ForeignKey(entity = LedgerEntity::class, parentColumns = ["id"], childColumns = ["ledgerId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PlanCatalogItemEntity::class, parentColumns = ["id"], childColumns = ["catalogId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["ledgerId", "attributionYear", "attributionMonth"]),
        Index("ledgerId"),
        Index("catalogId"),
        Index(value = ["catalogId", "attributionYear", "attributionMonth"]),
    ],
)
internal data class MonthlyPlanItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ledgerId: Long,
    val catalogId: Long,
    val attributionYear: Int,
    val attributionMonth: Int,
    val amountWon: Long?,
    val status: String,
    val memo: String?,
)

internal data class MonthlyPlanItemRow(
    val id: Long,
    val ledgerId: Long,
    val catalogId: Long,
    val attributionYear: Int,
    val attributionMonth: Int,
    val amountWon: Long?,
    val status: String,
    val memo: String?,
    val type: String,
    val classification: String,
    val name: String,
    val ownerMemberOrder: Int?,
    val includePurposeAccount: Boolean,
    val includeNetSavings: Boolean,
    val displayOrder: Int,
    val archived: Boolean,
)

private const val PLAN_ROW_SELECT = """
    SELECT p.id, p.ledgerId, p.catalogId, p.attributionYear, p.attributionMonth,
           p.amountWon, p.status, p.memo, c.type, c.classification, c.name,
           c.ownerMemberOrder, c.includePurposeAccount, c.includeNetSavings,
           c.displayOrder, c.archived
    FROM monthly_plan_items p
    INNER JOIN plan_item_catalog c ON c.id = p.catalogId
"""

@Dao
internal interface PlanDao {
    @Query("$PLAN_ROW_SELECT WHERE p.ledgerId = :ledgerId AND p.attributionYear = :year AND p.attributionMonth = :month ORDER BY c.displayOrder, p.id")
    fun observeMonth(year: Int, month: Int, ledgerId: Long = PLAN_LOCAL_LEDGER_ID): Flow<List<MonthlyPlanItemRow>>
    @Query("$PLAN_ROW_SELECT WHERE p.ledgerId = :ledgerId AND p.attributionYear = :year AND p.attributionMonth = :month ORDER BY c.displayOrder, p.id")
    suspend fun loadMonth(year: Int, month: Int, ledgerId: Long = PLAN_LOCAL_LEDGER_ID): List<MonthlyPlanItemRow>
    @Query("$PLAN_ROW_SELECT WHERE p.id = :id AND p.ledgerId = :ledgerId LIMIT 1")
    suspend fun find(id: Long, ledgerId: Long = PLAN_LOCAL_LEDGER_ID): MonthlyPlanItemRow?
    @Query("SELECT * FROM monthly_plan_items WHERE id = :id AND ledgerId = :ledgerId LIMIT 1")
    suspend fun findEntity(id: Long, ledgerId: Long): MonthlyPlanItemEntity?
    @Query("SELECT * FROM plan_item_catalog WHERE id = :id AND ledgerId = :ledgerId LIMIT 1")
    suspend fun findCatalog(id: Long, ledgerId: Long = PLAN_LOCAL_LEDGER_ID): PlanCatalogItemEntity?
    @Query("SELECT * FROM plan_item_catalog WHERE ledgerId = :ledgerId ORDER BY type, archived, displayOrder, id")
    fun observeCatalog(ledgerId: Long = PLAN_LOCAL_LEDGER_ID): Flow<List<PlanCatalogItemEntity>>
    @Query("SELECT * FROM plan_item_catalog WHERE ledgerId = :ledgerId ORDER BY type, archived, displayOrder, id")
    suspend fun loadCatalog(ledgerId: Long = PLAN_LOCAL_LEDGER_ID): List<PlanCatalogItemEntity>
    @Query("SELECT COALESCE(MAX(displayOrder), -1) FROM plan_item_catalog WHERE ledgerId = :ledgerId AND type = :type")
    suspend fun maxCatalogOrder(ledgerId: Long, type: String): Int
    @Query("""SELECT * FROM plan_item_catalog
        WHERE ledgerId = :ledgerId AND type = :type AND archived = 0 AND trim(name) = trim(:name)
          AND classification = :classification
          AND ((ownerMemberOrder IS NULL AND :ownerMemberOrder IS NULL) OR ownerMemberOrder = :ownerMemberOrder)
          AND includePurposeAccount = :includePurposeAccount AND includeNetSavings = :includeNetSavings
        ORDER BY archived, displayOrder, id LIMIT 1""")
    suspend fun findCatalogByLegacyIdentity(
        ledgerId: Long, type: String, name: String, classification: String, ownerMemberOrder: Int?,
        includePurposeAccount: Boolean, includeNetSavings: Boolean,
    ): PlanCatalogItemEntity?
    @Insert suspend fun insertCatalog(item: PlanCatalogItemEntity): Long
    @Update suspend fun updateCatalog(item: PlanCatalogItemEntity): Int
    @Insert suspend fun insert(item: MonthlyPlanItemEntity): Long
    @Update suspend fun update(item: MonthlyPlanItemEntity): Int
    @Query("DELETE FROM monthly_plan_items WHERE id = :id AND ledgerId = :ledgerId")
    suspend fun delete(id: Long, ledgerId: Long = PLAN_LOCAL_LEDGER_ID): Int
    @Query("UPDATE plan_item_catalog SET archived = :archived WHERE id = :id AND ledgerId = :ledgerId")
    suspend fun setCatalogArchived(id: Long, archived: Boolean, ledgerId: Long): Int
    @Query("UPDATE plan_item_catalog SET displayOrder = :displayOrder WHERE id = :id AND ledgerId = :ledgerId")
    suspend fun setCatalogOrder(id: Long, displayOrder: Int, ledgerId: Long): Int

    @Transaction
    suspend fun saveCatalog(item: PlanCatalogItemEntity): Long {
        if (item.id == 0L) return insertCatalog(item.copy(displayOrder = maxCatalogOrder(item.ledgerId, item.type) + 1))
        check(updateCatalog(item) == 1) { "Catalog item ${item.id} no longer exists" }
        return item.id
    }

    @Transaction
    suspend fun save(item: MonthlyPlanItem, catalog: PlanCatalogItemEntity): Long {
        val existing = item.id.takeIf { it > 0 }?.let { findEntity(it, item.ledgerId) }
        val catalogId = when {
            existing != null -> {
                val current = checkNotNull(findCatalog(existing.catalogId, item.ledgerId))
                check(updateCatalog(catalog.copy(id = current.id, displayOrder = current.displayOrder, archived = current.archived)) == 1)
                current.id
            }
            item.catalogId > 0 -> {
                val current = checkNotNull(findCatalog(item.catalogId, item.ledgerId))
                check(!current.archived) { "Archived catalog item ${current.id} cannot be attached to a new monthly plan" }
                check(updateCatalog(catalog.copy(id = current.id, displayOrder = current.displayOrder, archived = current.archived)) == 1)
                current.id
            }
            else -> findCatalogByLegacyIdentity(
                item.ledgerId,
                item.type.name,
                item.name,
                item.category,
                item.ownerMemberOrder,
                item.includePurposeAccount,
                item.includeNetSavings,
            )?.id ?: insertCatalog(catalog.copy(displayOrder = maxCatalogOrder(item.ledgerId, item.type.name) + 1))
        }
        check(loadMonth(item.attributionMonth.year, item.attributionMonth.month, item.ledgerId).none {
            it.catalogId == catalogId && it.id != item.id
        }) { "Catalog item $catalogId already has a plan for ${item.attributionMonth}" }
        val entity = item.toEntity(catalogId)
        return if (entity.id == 0L) insert(entity) else {
            check(update(entity) == 1) { "Plan item ${entity.id} no longer exists" }
            entity.id
        }
    }

    @Transaction
    suspend fun save(item: MonthlyPlanItem): Long = save(item, item.toCatalogEntity())

    @Transaction
    suspend fun reorderCatalog(ledgerId: Long, orderedIds: List<Long>) {
        require(orderedIds.isNotEmpty() && orderedIds.distinct().size == orderedIds.size)
        val selected = orderedIds.map { checkNotNull(findCatalog(it, ledgerId)) }
        val type = selected.map { it.type }.distinct().single()
        val current = loadCatalog(ledgerId).filter { !it.archived && it.type == type }
        require(current.map { it.id }.toSet() == orderedIds.toSet()) { "Active catalog order is stale" }
        orderedIds.forEachIndexed { index, id -> check(setCatalogOrder(id, index, ledgerId) == 1) }
    }

    @Transaction
    suspend fun copyToMonths(request: PlanCopyRequest): PlanCopyResult {
        val source = loadMonth(request.sourceMonth.year, request.sourceMonth.month)
            .filter { it.type == request.type.name && (request.sourceItemId == null || it.id == request.sourceItemId) }
        check(source.isNotEmpty()) { "복사할 원본 계획 항목이 없어요" }
        check(source.none { it.archived }) { "보관된 관리 항목은 다른 달에 적용할 수 없어요" }
        require(source.map { it.catalogId }.distinct().size == source.size) { "기준월에 같은 계획 항목이 중복되어 있어요" }
        var inserted = 0
        var overwritten = 0
        var skipped = 0
        request.targetMonths.distinct().forEach { target ->
            require(target != request.sourceMonth)
            val targetItems = loadMonth(target.year, target.month).toMutableList()
            source.forEach { sourceItem ->
                val matches = targetItems.filter { it.catalogId == sourceItem.catalogId }
                if (matches.isNotEmpty() && request.policy == ExistingPlanPolicy.KeepExisting) skipped++
                else {
                    if (matches.isNotEmpty()) {
                        matches.forEach { check(delete(it.id, it.ledgerId) == 1) }
                        targetItems.removeAll(matches.toSet())
                        overwritten++
                    }
                    val copied = sourceItem.toEntity().copy(id = 0, attributionYear = target.year, attributionMonth = target.month)
                    val id = insert(copied)
                    targetItems += sourceItem.copy(id = id, attributionYear = target.year, attributionMonth = target.month)
                    if (matches.isEmpty()) inserted++
                }
            }
        }
        return PlanCopyResult(request.targetMonths.distinct().size, inserted, overwritten, skipped)
    }

    @Transaction
    suspend fun applyFixedCosts(targetYear: Int, targetMonth: Int, inputs: List<FixedCostApplyInput>, policy: ExistingPlanPolicy): FixedCostApplyResult {
        val existing = loadMonth(targetYear, targetMonth).toMutableList()
        var inserted = 0
        var overwritten = 0
        var skipped = 0
        inputs.forEach { input ->
            val catalog = findCatalogByLegacyIdentity(input.ledgerId, PlanItemType.FixedExpense.name, input.name, "고정비", input.ownerMemberOrder, false, false)
                ?: PlanCatalogItemEntity(
                    ledgerId = input.ledgerId, type = PlanItemType.FixedExpense.name, classification = "고정비",
                    name = input.name, ownerMemberOrder = input.ownerMemberOrder, includePurposeAccount = false,
                    includeNetSavings = false, displayOrder = maxCatalogOrder(input.ledgerId, PlanItemType.FixedExpense.name) + 1, archived = false,
                ).let { it.copy(id = insertCatalog(it)) }
            val conflicts = existing.filter { it.catalogId == catalog.id }
            if (conflicts.isNotEmpty() && policy == ExistingPlanPolicy.KeepExisting) skipped++
            else {
                if (conflicts.isNotEmpty()) {
                    conflicts.forEach { check(delete(it.id, it.ledgerId) == 1) }
                    existing.removeAll(conflicts.toSet())
                    overwritten++
                }
                val entity = MonthlyPlanItemEntity(
                    ledgerId = input.ledgerId, catalogId = catalog.id, attributionYear = targetYear,
                    attributionMonth = targetMonth, amountWon = input.amountWon, status = PlanItemStatus.Estimated.name, memo = null,
                )
                val id = insert(entity)
                existing += entity.toRow(catalog).copy(id = id)
                if (conflicts.isEmpty()) inserted++
            }
        }
        return FixedCostApplyResult(inserted, overwritten, skipped)
    }
}

internal data class FixedCostApplyInput(val ledgerId: Long, val name: String, val amountWon: Long?, val ownerMemberOrder: Int?)

enum class ExistingPlanPolicy { KeepExisting, Overwrite }

data class PlanCopyRequest(
    val sourceMonth: YearMonthKey,
    val type: PlanItemType,
    val sourceItemId: Long?,
    val targetMonths: List<YearMonthKey>,
    val policy: ExistingPlanPolicy,
) {
    init {
        require(targetMonths.isNotEmpty()) { "적용할 달을 선택해 주세요" }
        require(targetMonths.none { it == sourceMonth }) { "기준월은 대상월이 될 수 없어요" }
    }
}

data class PlanCopyResult(
    val targetMonthCount: Int,
    val insertedItemCount: Int,
    val overwrittenItemCount: Int,
    val skippedConflictCount: Int,
) {
    val changedItemCount: Int get() = insertedItemCount + overwrittenItemCount
}

data class PlanCopyPreview(val sourceItems: List<MonthlyPlanItem>, val targetMonths: List<PlanCopyTargetPreview>) {
    val conflictCount: Int get() = targetMonths.sumOf { target -> target.rows.count { it.existingItem != null } }
}
data class PlanCopyTargetPreview(val month: YearMonthKey, val rows: List<PlanCopyPreviewRow>)
data class PlanCopyPreviewRow(val sourceItem: MonthlyPlanItem, val existingItem: MonthlyPlanItem?)
data class FixedCostPlanPreview(val month: YearMonthKey, val rows: List<FixedCostPlanPreviewRow>) {
    val conflictCount: Int get() = rows.count { it.existingItem != null }
}
data class FixedCostPlanPreviewRow(val sourceItem: FixedCostCheckItem, val existingItem: MonthlyPlanItem?)
data class FixedCostApplyResult(val insertedItemCount: Int, val overwrittenItemCount: Int, val skippedConflictCount: Int)

interface PlanLocalDataSource {
    fun observeMonth(month: YearMonthKey): Flow<List<MonthlyPlanItem>>
    suspend fun previewCopy(request: PlanCopyRequest): PlanCopyPreview
    suspend fun copyToMonths(request: PlanCopyRequest): PlanCopyResult
    suspend fun find(id: Long): MonthlyPlanItem?
    suspend fun save(item: MonthlyPlanItem): Long
    suspend fun delete(id: Long)
    suspend fun previewFixedCosts(month: YearMonthKey, items: List<FixedCostCheckItem>): FixedCostPlanPreview
    suspend fun applyFixedCosts(month: YearMonthKey, items: List<FixedCostCheckItem>, policy: ExistingPlanPolicy): FixedCostApplyResult
    fun observeCatalog(): Flow<List<PlanCatalogItem>> = flowOf(emptyList())
    suspend fun findCatalog(id: Long): PlanCatalogItem? = null
    suspend fun saveCatalog(item: PlanCatalogItem): Long = error("Catalog management is unavailable")
    suspend fun setCatalogArchived(id: Long, archived: Boolean): Unit = error("Catalog management is unavailable")
    suspend fun reorderCatalog(orderedIds: List<Long>): Unit = error("Catalog management is unavailable")
}

private class RoomPlanLocalDataSource(private val dao: PlanDao) : PlanLocalDataSource {
    override fun observeMonth(month: YearMonthKey) =
        dao.observeMonth(month.year, month.month).map { rows -> rows.map(MonthlyPlanItemRow::toModel) }

    override suspend fun previewCopy(request: PlanCopyRequest): PlanCopyPreview {
        val sourceRows = dao.loadMonth(request.sourceMonth.year, request.sourceMonth.month)
            .filter { it.type == request.type.name && (request.sourceItemId == null || it.id == request.sourceItemId) }
        check(sourceRows.isNotEmpty()) { "복사할 원본 계획 항목이 없어요" }
        check(sourceRows.none { it.archived }) { "보관된 관리 항목은 다른 달에 적용할 수 없어요" }
        require(sourceRows.map { it.catalogId }.distinct().size == sourceRows.size) { "기준월에 같은 계획 항목이 중복되어 있어요" }
        val source = sourceRows.map(MonthlyPlanItemRow::toModel)
        val targets = request.targetMonths.distinct()
            .sortedWith(compareBy(YearMonthKey::year, YearMonthKey::month))
            .map { target ->
                val existing = dao.loadMonth(target.year, target.month).map(MonthlyPlanItemRow::toModel)
                PlanCopyTargetPreview(target, source.map { sourceItem ->
                    PlanCopyPreviewRow(sourceItem, existing.firstOrNull { it.catalogId == sourceItem.catalogId })
                })
            }
        return PlanCopyPreview(source, targets)
    }

    override suspend fun copyToMonths(request: PlanCopyRequest) = dao.copyToMonths(request)
    override suspend fun find(id: Long) = dao.find(id)?.toModel()
    override suspend fun save(item: MonthlyPlanItem) = dao.save(item, item.toCatalogEntity())
    override suspend fun delete(id: Long) {
        check(dao.delete(id) == 1) { "Plan item $id no longer exists" }
    }

    override suspend fun previewFixedCosts(month: YearMonthKey, items: List<FixedCostCheckItem>): FixedCostPlanPreview {
        val existing = dao.loadMonth(month.year, month.month).map(MonthlyPlanItemRow::toModel)
        return FixedCostPlanPreview(month, items.map { source ->
            FixedCostPlanPreviewRow(source, existing.firstOrNull {
                it.type == PlanItemType.FixedExpense &&
                    it.name.trim() == source.name.trim() &&
                    it.ownerMemberOrder == source.payerMemberOrder
            })
        })
    }

    override suspend fun applyFixedCosts(
        month: YearMonthKey,
        items: List<FixedCostCheckItem>,
        policy: ExistingPlanPolicy,
    ): FixedCostApplyResult {
        require(items.isNotEmpty()) { "적용할 고정비를 선택해 주세요" }
        require(items.all { it.attributionMonth == month }) { "선택한 달의 고정비만 적용할 수 있어요" }
        require(items.distinctBy { it.name.trim() to it.payerMemberOrder }.size == items.size) {
            "같은 결제 주체의 중복 고정비 항목이 있어요"
        }
        return dao.applyFixedCosts(
            month.year,
            month.month,
            items.map { FixedCostApplyInput(it.ledgerId, it.name, it.amountWon, it.payerMemberOrder) },
            policy,
        )
    }

    override fun observeCatalog() = dao.observeCatalog().map { rows -> rows.map(PlanCatalogItemEntity::toModel) }
    override suspend fun findCatalog(id: Long) = dao.findCatalog(id)?.toModel()
    override suspend fun saveCatalog(item: PlanCatalogItem) = dao.saveCatalog(item.toEntity())
    override suspend fun setCatalogArchived(id: Long, archived: Boolean) {
        check(dao.setCatalogArchived(id, archived, PLAN_LOCAL_LEDGER_ID) == 1) { "Catalog item $id no longer exists" }
    }
    override suspend fun reorderCatalog(orderedIds: List<Long>) = dao.reorderCatalog(PLAN_LOCAL_LEDGER_ID, orderedIds)
}

fun createPlanLocalDataSource(database: MoaLogDatabase): PlanLocalDataSource =
    RoomPlanLocalDataSource(database.planDao())

private fun MonthlyPlanItemRow.toModel() = MonthlyPlanItem(
    id = id,
    ledgerId = ledgerId,
    type = PlanItemType.valueOf(type),
    attributionMonth = YearMonthKey(attributionYear, attributionMonth),
    name = name,
    amountWon = amountWon,
    category = classification,
    status = PlanItemStatus.valueOf(status),
    ownerMemberOrder = ownerMemberOrder,
    memo = memo,
    includePurposeAccount = includePurposeAccount,
    includeNetSavings = includeNetSavings,
    catalogId = catalogId,
)

private fun MonthlyPlanItem.toEntity(resolvedCatalogId: Long) = MonthlyPlanItemEntity(
    id, ledgerId, resolvedCatalogId, attributionMonth.year, attributionMonth.month, amountWon, status.name, memo,
)

private fun MonthlyPlanItem.toCatalogEntity() = PlanCatalogItemEntity(
    id = catalogId,
    ledgerId = ledgerId,
    type = type.name,
    classification = category,
    name = name,
    ownerMemberOrder = ownerMemberOrder,
    includePurposeAccount = includePurposeAccount,
    includeNetSavings = includeNetSavings,
    displayOrder = 0,
    archived = false,
)

private fun MonthlyPlanItemRow.toEntity() =
    MonthlyPlanItemEntity(id, ledgerId, catalogId, attributionYear, attributionMonth, amountWon, status, memo)

private fun MonthlyPlanItemEntity.toRow(catalog: PlanCatalogItemEntity) = MonthlyPlanItemRow(
    id, ledgerId, catalogId, attributionYear, attributionMonth, amountWon, status, memo,
    catalog.type, catalog.classification, catalog.name, catalog.ownerMemberOrder,
    catalog.includePurposeAccount, catalog.includeNetSavings, catalog.displayOrder, catalog.archived,
)

private fun PlanCatalogItemEntity.toModel() = PlanCatalogItem(
    id, ledgerId, PlanItemType.valueOf(type), classification, name, ownerMemberOrder,
    includePurposeAccount, includeNetSavings, displayOrder, archived,
)

private fun PlanCatalogItem.toEntity() = PlanCatalogItemEntity(
    id, ledgerId, type.name, classification, name, ownerMemberOrder,
    includePurposeAccount, includeNetSavings, displayOrder, archived,
)
