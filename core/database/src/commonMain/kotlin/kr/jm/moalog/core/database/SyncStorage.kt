package kr.jm.moalog.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.Transactor
import androidx.room.useWriterConnection
import kr.jm.moalog.core.contracts.AppliedMutationDto
import kr.jm.moalog.core.contracts.AssetGrowthRuleSyncPayload
import kr.jm.moalog.core.contracts.SyncAssetKind as WireAssetKind
import kr.jm.moalog.core.contracts.AssetSyncPayload
import kr.jm.moalog.core.contracts.SyncAssetType as WireAssetType
import kr.jm.moalog.core.contracts.AssetValuationSyncPayload
import kr.jm.moalog.core.contracts.ExpenseCategorySyncPayload
import kr.jm.moalog.core.contracts.ExpenseRecordSyncPayload
import kr.jm.moalog.core.contracts.FixedCostItemSyncPayload
import kr.jm.moalog.core.contracts.MonthlyPlanItemSyncPayload
import kr.jm.moalog.core.contracts.MaintenanceFeeEntrySyncPayload
import kr.jm.moalog.core.contracts.MaintenanceFeeMonthSyncPayload
import kr.jm.moalog.core.contracts.PlanCatalogItemSyncPayload
import kr.jm.moalog.core.contracts.SalaryAllocationCategorySyncPayload
import kr.jm.moalog.core.contracts.SalaryAllocationChildSyncPayload
import kr.jm.moalog.core.contracts.SalaryAllocationDeductionSyncPayload
import kr.jm.moalog.core.contracts.SalaryAllocationGrandchildSyncPayload
import kr.jm.moalog.core.contracts.SyncSalaryAllocationMethod as WireSalaryMethod
import kr.jm.moalog.core.contracts.SalaryIncomeSyncPayload
import kr.jm.moalog.core.contracts.SyncChangeDto
import kr.jm.moalog.core.contracts.SyncDatePayload
import kr.jm.moalog.core.contracts.SyncEntityType
import kr.jm.moalog.core.contracts.SyncMutationDto
import kr.jm.moalog.core.contracts.SyncMaintenanceFeeItemKey
import kr.jm.moalog.core.contracts.SyncOperation
import kr.jm.moalog.core.contracts.SyncPlanItemStatus
import kr.jm.moalog.core.contracts.SyncPlanItemType
import kr.jm.moalog.core.contracts.SyncYearMonthPayload
import kr.jm.moalog.core.contracts.UuidString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private const val SYNC_LOCAL_LEDGER_ID = 1L

@Entity(tableName = "sync_installation", indices = [Index(value = ["deviceId"], unique = true)])
internal data class SyncInstallationEntity(@PrimaryKey val slot: Int = 0, val deviceId: String)

@Entity(tableName = "sync_household_bindings", indices = [Index(value = ["householdId"], unique = true)])
internal data class SyncHouseholdBindingEntity(
    @PrimaryKey val localLedgerId: Long,
    val householdId: String,
)

@Entity(
    tableName = "sync_identities",
    primaryKeys = ["householdId", "entityType", "localKey"],
    indices = [Index(value = ["householdId", "entityType", "entityUuid"], unique = true)],
)
internal data class SyncIdentityEntity(
    val householdId: String,
    val entityType: String,
    val localKey: String,
    val entityUuid: String,
    val serverVersion: Long?,
    val syncedPayload: String?,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["householdId", "nextAttemptAtEpochMillis", "createdAtEpochMillis"]),
        Index(value = ["householdId", "entityType", "entityUuid"], unique = true),
    ],
)
internal data class SyncOutboxEntity(
    @PrimaryKey val mutationId: String,
    val householdId: String,
    val deviceId: String,
    val entityType: String,
    val entityUuid: String,
    val operation: String,
    val baseVersion: Long?,
    val payloadJson: String?,
    val attemptCount: Int,
    val nextAttemptAtEpochMillis: Long,
    val lastError: String?,
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "sync_cursors")
internal data class SyncCursorEntity(@PrimaryKey val householdId: String, val cursor: Long)

@Entity(tableName = "sync_pending_household_settings")
internal data class SyncPendingHouseholdSettingsEntity(
    @PrimaryKey val slot: Int = 0,
    val householdId: String,
    val baseVersion: Long,
)

data class PendingHouseholdSettings(val householdId: UuidString, val baseVersion: Long)

data class PersistentSyncMutation(
    val mutationId: UuidString,
    val householdId: UuidString,
    val deviceId: UuidString,
    val mutation: SyncMutationDto,
    val attemptCount: Int,
)

fun interface SyncUuidFactory {
    fun create(): UuidString
}

interface SyncLocalStore {
    /** Returns the installation UUID, creating it once in Room for the authentication challenge. */
    suspend fun persistentDeviceId(uuidFactory: SyncUuidFactory): UuidString
    /** Persists the device UUID used during authentication and rejects a different active session. */
    suspend fun requirePersistentDeviceId(deviceId: UuidString)
    suspend fun boundHouseholdId(): UuidString?
    /** Binds the single-ledger database once and refuses accidental cross-household upload. */
    suspend fun bindHousehold(householdId: UuidString)
    suspend fun pendingHouseholdSettings(): PendingHouseholdSettings?
    suspend fun markHouseholdSettingsPending(householdId: UuidString, baseVersion: Long)
    suspend fun clearPendingHouseholdSettings(householdId: UuidString)
    /**
     * Removes the locally bound ledger and all sync bookkeeping before this installation can bind
     * another household. Keeping the old ledger rows would upload one household's records into the
     * next household when local changes are captured.
     */
    suspend fun resetCloudBinding()
    suspend fun captureLocalChanges(
        householdId: UuidString,
        deviceId: UuidString,
        nowEpochMillis: Long,
        uuidFactory: SyncUuidFactory,
    )
    suspend fun readyMutations(householdId: UuidString, nowEpochMillis: Long, limit: Int = 100): List<PersistentSyncMutation>
    suspend fun nextPendingRetryAt(householdId: UuidString): Long?
    fun observeLocalChanges(): Flow<Unit>
    suspend fun acknowledgePush(householdId: UuidString, acknowledgements: List<AppliedMutationDto>)
    suspend fun markPushFailed(mutationIds: List<UuidString>, nextAttemptAtEpochMillis: Long, error: String)
    suspend fun pullCursor(householdId: UuidString): Long
    suspend fun applyPulledChanges(
        householdId: UuidString,
        changes: List<SyncChangeDto>,
        nextCursor: Long,
        resolvePendingConflicts: Boolean = false,
    )
}

@Dao
internal interface SyncDao {
    @Query("SELECT * FROM sync_installation WHERE slot = 0 LIMIT 1") suspend fun installation(): SyncInstallationEntity?
    @Insert suspend fun insertInstallation(value: SyncInstallationEntity)
    @Query("SELECT * FROM sync_household_bindings WHERE localLedgerId = :ledgerId LIMIT 1")
    suspend fun binding(ledgerId: Long = SYNC_LOCAL_LEDGER_ID): SyncHouseholdBindingEntity?
    @Insert suspend fun insertBinding(value: SyncHouseholdBindingEntity)

    @Query("SELECT * FROM sync_identities WHERE householdId = :householdId")
    suspend fun identities(householdId: String): List<SyncIdentityEntity>
    @Query("SELECT * FROM sync_identities WHERE householdId = :householdId AND entityType = :type AND localKey = :localKey LIMIT 1")
    suspend fun identityByLocalKey(householdId: String, type: String, localKey: String): SyncIdentityEntity?
    @Query("SELECT * FROM sync_identities WHERE householdId = :householdId AND entityType = :type AND entityUuid = :uuid LIMIT 1")
    suspend fun identityByUuid(householdId: String, type: String, uuid: String): SyncIdentityEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIdentity(value: SyncIdentityEntity): Long
    @Update suspend fun updateIdentity(value: SyncIdentityEntity): Int

    @Query("""SELECT * FROM sync_outbox
        WHERE householdId = :householdId AND nextAttemptAtEpochMillis <= :now
        ORDER BY
          CASE operation WHEN 'DELETE' THEN 0 ELSE 1 END,
          CASE WHEN operation = 'DELETE' THEN
            CASE entityType
              WHEN 'SALARY_ALLOCATION_GRANDCHILD' THEN 0
              WHEN 'MONTHLY_PLAN_ITEM' THEN 1 WHEN 'EXPENSE_RECORD' THEN 1
              WHEN 'SALARY_ALLOCATION_DEDUCTION' THEN 1
              WHEN 'SALARY_ALLOCATION_CHILD' THEN 1 WHEN 'ASSET_VALUATION' THEN 1 WHEN 'ASSET_GROWTH_RULE' THEN 1
              ELSE 2 END
          ELSE
            CASE entityType
              WHEN 'MONTHLY_PLAN_ITEM' THEN 1 WHEN 'EXPENSE_RECORD' THEN 1
              WHEN 'SALARY_ALLOCATION_DEDUCTION' THEN 1
              WHEN 'SALARY_ALLOCATION_CHILD' THEN 1 WHEN 'ASSET_VALUATION' THEN 1 WHEN 'ASSET_GROWTH_RULE' THEN 1
              WHEN 'SALARY_ALLOCATION_GRANDCHILD' THEN 2
              ELSE 0 END
          END,
          createdAtEpochMillis, mutationId
        LIMIT :limit""")
    suspend fun readyOutbox(householdId: String, now: Long, limit: Int): List<SyncOutboxEntity>
    @Query("SELECT * FROM sync_outbox WHERE householdId = :householdId AND entityType = :type AND entityUuid = :uuid LIMIT 1")
    suspend fun pending(householdId: String, type: String, uuid: String): SyncOutboxEntity?
    @Query("SELECT * FROM sync_outbox WHERE mutationId = :mutationId LIMIT 1")
    suspend fun outboxById(mutationId: String): SyncOutboxEntity?
    @Insert suspend fun insertOutbox(value: SyncOutboxEntity)
    @Query("DELETE FROM sync_outbox WHERE mutationId = :mutationId") suspend fun deleteOutbox(mutationId: String): Int
    @Query("DELETE FROM sync_outbox") suspend fun deleteAllOutbox()
    @Query("UPDATE sync_outbox SET attemptCount = attemptCount + 1, nextAttemptAtEpochMillis = :nextAttemptAt, lastError = :error WHERE mutationId IN (:mutationIds)")
    suspend fun failOutbox(mutationIds: List<String>, nextAttemptAt: Long, error: String)
    @Query("UPDATE sync_outbox SET baseVersion = :baseVersion, nextAttemptAtEpochMillis = :retryAt, lastError = NULL WHERE mutationId = :mutationId")
    suspend fun rebaseOutbox(mutationId: String, baseVersion: Long, retryAt: Long): Int
    @Query("SELECT MIN(nextAttemptAtEpochMillis) FROM sync_outbox WHERE householdId = :householdId")
    suspend fun nextOutboxAttempt(householdId: String): Long?

    @Query("SELECT cursor FROM sync_cursors WHERE householdId = :householdId") suspend fun cursor(householdId: String): Long?
    @Upsert suspend fun upsertCursor(value: SyncCursorEntity)
    @Query("DELETE FROM sync_cursors") suspend fun deleteAllCursors()
    @Query("DELETE FROM sync_identities") suspend fun deleteAllIdentities()
    @Query("DELETE FROM sync_household_bindings") suspend fun deleteAllBindings()
    @Query("SELECT * FROM sync_pending_household_settings WHERE slot = 0 LIMIT 1")
    suspend fun pendingHouseholdSettings(): SyncPendingHouseholdSettingsEntity?
    @Upsert suspend fun upsertPendingHouseholdSettings(value: SyncPendingHouseholdSettingsEntity)
    @Query("DELETE FROM sync_pending_household_settings WHERE slot = 0 AND householdId = :householdId")
    suspend fun deletePendingHouseholdSettings(householdId: String): Int
    @Query("DELETE FROM sync_pending_household_settings") suspend fun deleteAllPendingHouseholdSettings()
    @Query("DELETE FROM ledgers") suspend fun deleteAllLedgers()

    @Query("SELECT * FROM expense_categories WHERE ledgerId = :ledgerId") suspend fun allExpenseCategories(ledgerId: Long = SYNC_LOCAL_LEDGER_ID): List<ExpenseCategoryEntity>
    @Query("SELECT * FROM expense_records WHERE ledgerId = :ledgerId") suspend fun allExpenseRecords(ledgerId: Long = SYNC_LOCAL_LEDGER_ID): List<ExpenseRecordEntity>
    @Query("SELECT * FROM plan_item_catalog WHERE ledgerId = :ledgerId") suspend fun allPlanCatalog(ledgerId: Long = SYNC_LOCAL_LEDGER_ID): List<PlanCatalogItemEntity>
    @Query("SELECT * FROM monthly_plan_items WHERE ledgerId = :ledgerId") suspend fun allMonthlyPlans(ledgerId: Long = SYNC_LOCAL_LEDGER_ID): List<MonthlyPlanItemEntity>
    @Query("SELECT * FROM salary_incomes WHERE ledgerId = :ledgerId") suspend fun allSalaryIncomes(ledgerId: Long = SYNC_LOCAL_LEDGER_ID): List<SalaryIncomeEntity>
    @Query("SELECT * FROM salary_allocation_categories WHERE ledgerId = :ledgerId") suspend fun allSalaryCategories(ledgerId: Long = SYNC_LOCAL_LEDGER_ID): List<SalaryAllocationCategoryEntity>
    @Query("SELECT * FROM salary_allocation_children") suspend fun allSalaryChildren(): List<SalaryAllocationChildEntity>
    @Query("SELECT * FROM salary_allocation_grandchildren") suspend fun allSalaryGrandchildren(): List<SalaryAllocationGrandchildEntity>
    @Query("SELECT * FROM fixed_cost_check_items WHERE ledgerId = :ledgerId") suspend fun allFixedCosts(ledgerId: Long = SYNC_LOCAL_LEDGER_ID): List<FixedCostCheckItemEntity>
    @Query("SELECT * FROM asset_items WHERE ledgerId = :ledgerId") suspend fun allAssets(ledgerId: Long = SYNC_LOCAL_LEDGER_ID): List<AssetItemEntity>
    @Query("SELECT * FROM asset_valuations") suspend fun allAssetValuations(): List<AssetValuationEntity>
    @Query("SELECT * FROM asset_growth_rules") suspend fun allAssetGrowthRules(): List<AssetGrowthRuleEntity>
    @Query("SELECT * FROM maintenance_fee_entries WHERE ledgerId = :ledgerId ORDER BY billYear, billMonth, itemKey") suspend fun allMaintenanceFees(ledgerId: Long = SYNC_LOCAL_LEDGER_ID): List<MaintenanceFeeEntryEntity>

    @Insert suspend fun insertRemoteExpenseCategory(value: ExpenseCategoryEntity)
    @Update suspend fun updateRemoteExpenseCategory(value: ExpenseCategoryEntity): Int
    @Query("DELETE FROM expense_categories WHERE id = :id AND ledgerId = :ledgerId") suspend fun deleteRemoteExpenseCategory(id: String, ledgerId: Long = SYNC_LOCAL_LEDGER_ID): Int
    @Insert suspend fun insertRemoteExpenseRecord(value: ExpenseRecordEntity): Long
    @Update suspend fun updateRemoteExpenseRecord(value: ExpenseRecordEntity): Int
    @Query("DELETE FROM expense_records WHERE id = :id AND ledgerId = :ledgerId") suspend fun deleteRemoteExpenseRecord(id: Long, ledgerId: Long = SYNC_LOCAL_LEDGER_ID): Int
    @Insert suspend fun insertRemotePlanCatalog(value: PlanCatalogItemEntity): Long
    @Update suspend fun updateRemotePlanCatalog(value: PlanCatalogItemEntity): Int
    @Query("DELETE FROM plan_item_catalog WHERE id = :id AND ledgerId = :ledgerId") suspend fun deleteRemotePlanCatalog(id: Long, ledgerId: Long = SYNC_LOCAL_LEDGER_ID): Int
    @Insert suspend fun insertRemoteMonthlyPlan(value: MonthlyPlanItemEntity): Long
    @Update suspend fun updateRemoteMonthlyPlan(value: MonthlyPlanItemEntity): Int
    @Query("DELETE FROM monthly_plan_items WHERE id = :id AND ledgerId = :ledgerId") suspend fun deleteRemoteMonthlyPlan(id: Long, ledgerId: Long = SYNC_LOCAL_LEDGER_ID): Int
    @Upsert suspend fun upsertRemoteSalaryIncome(value: SalaryIncomeEntity)
    @Query("DELETE FROM salary_incomes WHERE ledgerId = :ledgerId AND attributionYear = :year AND attributionMonth = :month AND memberOrder = :memberOrder") suspend fun deleteRemoteSalaryIncome(ledgerId: Long, year: Int, month: Int, memberOrder: Int): Int
    @Insert suspend fun insertRemoteSalaryCategory(value: SalaryAllocationCategoryEntity): Long
    @Update suspend fun updateRemoteSalaryCategory(value: SalaryAllocationCategoryEntity): Int
    @Query("DELETE FROM salary_allocation_categories WHERE id = :id") suspend fun deleteRemoteSalaryCategory(id: Long): Int
    @Query("SELECT deductedCategoryIds FROM salary_allocation_categories WHERE id = :id") suspend fun remoteDeductionIds(id: Long): String?
    @Query("UPDATE salary_allocation_categories SET deductedCategoryIds = :ids WHERE id = :id") suspend fun updateRemoteDeductionIds(id: Long, ids: String?): Int
    @Insert suspend fun insertRemoteSalaryChild(value: SalaryAllocationChildEntity): Long
    @Update suspend fun updateRemoteSalaryChild(value: SalaryAllocationChildEntity): Int
    @Query("DELETE FROM salary_allocation_children WHERE id = :id") suspend fun deleteRemoteSalaryChild(id: Long): Int
    @Insert suspend fun insertRemoteSalaryGrandchild(value: SalaryAllocationGrandchildEntity): Long
    @Update suspend fun updateRemoteSalaryGrandchild(value: SalaryAllocationGrandchildEntity): Int
    @Query("DELETE FROM salary_allocation_grandchildren WHERE id = :id") suspend fun deleteRemoteSalaryGrandchild(id: Long): Int
    @Insert suspend fun insertRemoteFixedCost(value: FixedCostCheckItemEntity): Long
    @Update suspend fun updateRemoteFixedCost(value: FixedCostCheckItemEntity): Int
    @Query("DELETE FROM fixed_cost_check_items WHERE id = :id AND ledgerId = :ledgerId") suspend fun deleteRemoteFixedCost(id: Long, ledgerId: Long = SYNC_LOCAL_LEDGER_ID): Int
    @Insert suspend fun insertRemoteAsset(value: AssetItemEntity): Long
    @Update suspend fun updateRemoteAsset(value: AssetItemEntity): Int
    @Query("DELETE FROM asset_items WHERE id = :id AND ledgerId = :ledgerId") suspend fun deleteRemoteAsset(id: Long, ledgerId: Long = SYNC_LOCAL_LEDGER_ID): Int
    @Upsert suspend fun upsertRemoteValuation(value: AssetValuationEntity)
    @Query("DELETE FROM asset_valuations WHERE assetId = :assetId AND valuationYear = :year AND valuationMonth = :month") suspend fun deleteRemoteValuation(assetId: Long, year: Int, month: Int): Int
    @Upsert suspend fun upsertRemoteGrowthRule(value: AssetGrowthRuleEntity)
    @Query("DELETE FROM asset_growth_rules WHERE assetId = :assetId") suspend fun deleteRemoteGrowthRule(assetId: Long): Int
    @Query("DELETE FROM maintenance_fee_entries WHERE ledgerId = :ledgerId AND billYear = :year AND billMonth = :month") suspend fun deleteRemoteMaintenanceMonth(ledgerId: Long, year: Int, month: Int): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRemoteMaintenanceEntries(values: List<MaintenanceFeeEntryEntity>)
}

private data class LocalSnapshot(val type: SyncEntityType, val localKey: String, val payload: JsonObject)

private class RoomSyncLocalStore(private val database: MoaLogDatabase, private val dao: SyncDao) : SyncLocalStore {
    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }

    override suspend fun persistentDeviceId(uuidFactory: SyncUuidFactory): UuidString = writeTransaction {
        dao.installation()?.let { return@writeTransaction UuidString(it.deviceId) }
        val created = uuidFactory.create()
        dao.insertInstallation(SyncInstallationEntity(deviceId = created.value))
        created
    }

    override suspend fun requirePersistentDeviceId(deviceId: UuidString) = writeTransaction {
        val existing = dao.installation()
        check(existing == null || existing.deviceId == deviceId.value) {
            "Authenticated session device does not match this installation"
        }
        if (existing == null) dao.insertInstallation(SyncInstallationEntity(deviceId = deviceId.value))
    }

    override suspend fun boundHouseholdId(): UuidString? = dao.binding()?.householdId?.let(::UuidString)

    override suspend fun bindHousehold(householdId: UuidString) = writeTransaction {
        val existing = dao.binding()
        check(existing == null || existing.householdId == householdId.value) {
            "This local ledger is already bound to a different household"
        }
        if (existing == null) dao.insertBinding(SyncHouseholdBindingEntity(SYNC_LOCAL_LEDGER_ID, householdId.value))
    }

    override suspend fun pendingHouseholdSettings(): PendingHouseholdSettings? =
        dao.pendingHouseholdSettings()?.let {
            PendingHouseholdSettings(UuidString(it.householdId), it.baseVersion)
        }

    override suspend fun markHouseholdSettingsPending(householdId: UuidString, baseVersion: Long) {
        require(baseVersion > 0)
        requireBound(householdId)
        dao.upsertPendingHouseholdSettings(
            SyncPendingHouseholdSettingsEntity(householdId = householdId.value, baseVersion = baseVersion),
        )
    }

    override suspend fun clearPendingHouseholdSettings(householdId: UuidString) {
        dao.deletePendingHouseholdSettings(householdId.value)
    }

    override suspend fun resetCloudBinding() = writeTransaction {
        // Every product table belongs to a ledger directly or through a cascading child relation.
        // Delete the ledger first while retaining sync_installation so the authenticated device ID
        // continues to match the server session.
        dao.deleteAllLedgers()
        dao.deleteAllOutbox()
        dao.deleteAllIdentities()
        dao.deleteAllCursors()
        dao.deleteAllPendingHouseholdSettings()
        dao.deleteAllBindings()
    }

    override fun observeLocalChanges(): Flow<Unit> = database.invalidationTracker.createFlow(
        "ledgers",
        "ledger_members",
        "annual_savings_targets",
        "expense_categories",
        "expense_records",
        "plan_item_catalog",
        "monthly_plan_items",
        "salary_incomes",
        "salary_allocation_categories",
        "salary_allocation_children",
        "salary_allocation_grandchildren",
        "fixed_cost_check_items",
        "asset_items",
        "asset_valuations",
        "asset_growth_rules",
        "maintenance_fee_entries",
        emitInitialState = false,
    ).map { Unit }

    override suspend fun captureLocalChanges(householdId: UuidString, deviceId: UuidString, nowEpochMillis: Long, uuidFactory: SyncUuidFactory) = writeTransaction {
        requireBound(householdId)
        val seeds = localIdentitySeeds()
        seeds.forEach { (type, localKey) -> ensureIdentity(householdId.value, type, localKey, uuidFactory) }
        val snapshots = localSnapshots(householdId.value)
        val present = snapshots.associateBy { it.type.name to it.localKey }
        snapshots.sortedWith(compareBy<LocalSnapshot> { typeRank(it.type) }.thenBy { it.localKey }).forEach { snapshot ->
            val identity = requireNotNull(dao.identityByLocalKey(householdId.value, snapshot.type.name, snapshot.localKey))
            if (dao.pending(householdId.value, snapshot.type.name, identity.entityUuid) != null) return@forEach
            val payloadJson = snapshot.payload.toString()
            if (identity.syncedPayload != payloadJson) {
                dao.insertOutbox(SyncOutboxEntity(
                    mutationId = uuidFactory.create().value,
                    householdId = householdId.value,
                    deviceId = deviceId.value,
                    entityType = snapshot.type.name,
                    entityUuid = identity.entityUuid,
                    operation = SyncOperation.UPSERT.name,
                    baseVersion = identity.serverVersion,
                    payloadJson = payloadJson,
                    attemptCount = 0,
                    nextAttemptAtEpochMillis = nowEpochMillis,
                    lastError = null,
                    createdAtEpochMillis = nowEpochMillis,
                ))
            }
        }
        dao.identities(householdId.value)
            .filter { !it.deleted && it.serverVersion != null && (it.entityType to it.localKey) !in present }
            .sortedWith(compareByDescending<SyncIdentityEntity> { typeRank(SyncEntityType.valueOf(it.entityType)) }.thenBy { it.localKey })
            .forEach { identity ->
                if (dao.pending(householdId.value, identity.entityType, identity.entityUuid) == null) {
                    dao.insertOutbox(SyncOutboxEntity(
                        mutationId = uuidFactory.create().value,
                        householdId = householdId.value,
                        deviceId = deviceId.value,
                        entityType = identity.entityType,
                        entityUuid = identity.entityUuid,
                        operation = SyncOperation.DELETE.name,
                        baseVersion = identity.serverVersion,
                        payloadJson = null,
                        attemptCount = 0,
                        nextAttemptAtEpochMillis = nowEpochMillis,
                        lastError = null,
                        createdAtEpochMillis = nowEpochMillis,
                    ))
                }
            }
    }

    override suspend fun readyMutations(householdId: UuidString, nowEpochMillis: Long, limit: Int): List<PersistentSyncMutation> {
        require(limit in 1..100)
        requireBound(householdId)
        return dao.readyOutbox(householdId.value, nowEpochMillis, limit).map { row ->
            val operation = SyncOperation.valueOf(row.operation)
            PersistentSyncMutation(
                UuidString(row.mutationId), householdId, UuidString(row.deviceId),
                SyncMutationDto(
                    UuidString(row.mutationId), SyncEntityType.valueOf(row.entityType), UuidString(row.entityUuid),
                    operation, row.baseVersion, row.payloadJson?.let { json.parseToJsonElement(it).jsonObject },
                ), row.attemptCount,
            )
        }
    }

    override suspend fun nextPendingRetryAt(householdId: UuidString): Long? {
        requireBound(householdId)
        return dao.nextOutboxAttempt(householdId.value)
    }

    override suspend fun acknowledgePush(householdId: UuidString, acknowledgements: List<AppliedMutationDto>) = writeTransaction {
        requireBound(householdId)
        acknowledgements.forEach { acknowledgement ->
            val row = requireNotNull(dao.outboxById(acknowledgement.mutationId.value)) { "Unknown sync acknowledgement" }
            check(row.householdId == householdId.value && row.entityUuid == acknowledgement.entityId.value)
            val identity = requireNotNull(dao.identityByUuid(row.householdId, row.entityType, row.entityUuid))
            check(dao.updateIdentity(identity.copy(
                serverVersion = acknowledgement.version,
                syncedPayload = if (row.operation == SyncOperation.UPSERT.name) row.payloadJson else null,
                deleted = row.operation == SyncOperation.DELETE.name,
            )) == 1)
            check(dao.deleteOutbox(row.mutationId) == 1)
        }
    }

    override suspend fun markPushFailed(mutationIds: List<UuidString>, nextAttemptAtEpochMillis: Long, error: String) {
        if (mutationIds.isNotEmpty()) dao.failOutbox(mutationIds.map { it.value }, nextAttemptAtEpochMillis, error.take(500))
    }

    override suspend fun pullCursor(householdId: UuidString): Long {
        requireBound(householdId)
        return dao.cursor(householdId.value) ?: 0L
    }

    override suspend fun applyPulledChanges(
        householdId: UuidString,
        changes: List<SyncChangeDto>,
        nextCursor: Long,
        resolvePendingConflicts: Boolean,
    ) = writeTransaction {
        requireBound(householdId)
        val previous = dao.cursor(householdId.value) ?: 0L
        require(changes.zipWithNext().all { (a, b) -> a.cursor < b.cursor })
        check(changes.all { it.cursor > previous && it.cursor <= nextCursor }) {
            "Pulled changes must advance the household cursor"
        }
        changes.forEach { applyChange(householdId.value, it, resolvePendingConflicts) }
        check(nextCursor >= previous) { "Sync cursor cannot move backwards" }
        dao.upsertCursor(SyncCursorEntity(householdId.value, nextCursor))
    }

    private suspend fun requireBound(householdId: UuidString) {
        check(dao.binding()?.householdId == householdId.value) { "Local ledger is not bound to this household" }
    }

    private suspend fun ensureIdentity(householdId: String, type: SyncEntityType, localKey: String, ids: SyncUuidFactory): SyncIdentityEntity {
        dao.identityByLocalKey(householdId, type.name, localKey)?.let { existing ->
            if (!existing.deleted) return existing
            val replacement = existing.copy(entityUuid = ids.create().value, serverVersion = null, syncedPayload = null, deleted = false)
            check(dao.updateIdentity(replacement) == 1)
            return replacement
        }
        dao.insertIdentity(SyncIdentityEntity(householdId, type.name, localKey, ids.create().value, null, null))
        return requireNotNull(dao.identityByLocalKey(householdId, type.name, localKey))
    }

    private suspend fun localIdentitySeeds(): List<Pair<SyncEntityType, String>> = buildList {
        dao.allPlanCatalog().forEach { add(SyncEntityType.PLAN_CATALOG_ITEM to it.id.toString()) }
        dao.allMonthlyPlans().forEach { add(SyncEntityType.MONTHLY_PLAN_ITEM to it.id.toString()) }
        dao.allExpenseCategories().forEach { add(SyncEntityType.EXPENSE_CATEGORY to it.id) }
        dao.allExpenseRecords().forEach { add(SyncEntityType.EXPENSE_RECORD to it.id.toString()) }
        dao.allSalaryIncomes().forEach { add(SyncEntityType.SALARY_INCOME to incomeKey(it)) }
        val salaryCategories = dao.allSalaryCategories()
        salaryCategories.forEach { add(SyncEntityType.SALARY_ALLOCATION_CATEGORY to it.id.toString()) }
        salaryCategories.forEach { source -> effectiveDeductions(source, salaryCategories).forEach { deducted -> add(SyncEntityType.SALARY_ALLOCATION_DEDUCTION to deductionKey(source.id, deducted)) } }
        dao.allSalaryChildren().forEach { add(SyncEntityType.SALARY_ALLOCATION_CHILD to it.id.toString()) }
        dao.allSalaryGrandchildren().forEach { add(SyncEntityType.SALARY_ALLOCATION_GRANDCHILD to it.id.toString()) }
        dao.allFixedCosts().forEach { add(SyncEntityType.FIXED_COST_ITEM to it.id.toString()) }
        dao.allAssets().forEach { add(SyncEntityType.ASSET to it.id.toString()) }
        dao.allAssetValuations().forEach { add(SyncEntityType.ASSET_VALUATION to valuationKey(it)) }
        dao.allAssetGrowthRules().forEach { add(SyncEntityType.ASSET_GROWTH_RULE to it.assetId.toString()) }
        dao.allMaintenanceFees().map { maintenanceKey(it.billYear, it.billMonth) }.distinct().forEach { add(SyncEntityType.MAINTENANCE_FEE_MONTH to it) }
    }

    private suspend fun localSnapshots(householdId: String): List<LocalSnapshot> = buildList {
        dao.allPlanCatalog().forEach { row -> add(snapshot(SyncEntityType.PLAN_CATALOG_ITEM, row.id.toString(), PlanCatalogItemSyncPayload(row.type.toWirePlanType(), row.classification, row.name, row.ownerMemberOrder, row.includePurposeAccount, row.includeNetSavings, row.displayOrder, row.archived), PlanCatalogItemSyncPayload.serializer())) }
        dao.allMonthlyPlans().forEach { row -> add(snapshot(SyncEntityType.MONTHLY_PLAN_ITEM, row.id.toString(), MonthlyPlanItemSyncPayload(identityUuid(householdId, SyncEntityType.PLAN_CATALOG_ITEM, row.catalogId.toString()), SyncYearMonthPayload(row.attributionYear, row.attributionMonth), row.amountWon, row.status.toWirePlanStatus(), row.memo), MonthlyPlanItemSyncPayload.serializer())) }
        val categories = dao.allExpenseCategories().associateBy { it.id }
        categories.values.forEach { row -> add(snapshot(SyncEntityType.EXPENSE_CATEGORY, row.id, ExpenseCategorySyncPayload(row.name, row.displayOrder, row.archived), ExpenseCategorySyncPayload.serializer())) }
        dao.allExpenseRecords().forEach { row -> add(snapshot(SyncEntityType.EXPENSE_RECORD, row.id.toString(), ExpenseRecordSyncPayload(identityUuid(householdId, SyncEntityType.EXPENSE_CATEGORY, row.categoryId), requireNotNull(categories[row.categoryId]).name, SyncYearMonthPayload(row.attributionYear, row.attributionMonth), row.actualDate?.toSyncDate(), row.detail, row.amountWon, row.overspent), ExpenseRecordSyncPayload.serializer())) }
        dao.allSalaryIncomes().forEach { row -> add(snapshot(SyncEntityType.SALARY_INCOME, incomeKey(row), SalaryIncomeSyncPayload(SyncYearMonthPayload(row.attributionYear, row.attributionMonth), row.memberOrder, row.amountWon), SalaryIncomeSyncPayload.serializer())) }
        val salaryCategories = dao.allSalaryCategories()
        salaryCategories.forEach { row -> add(snapshot(SyncEntityType.SALARY_ALLOCATION_CATEGORY, row.id.toString(), SalaryAllocationCategorySyncPayload(SyncYearMonthPayload(row.attributionYear, row.attributionMonth), row.name, row.sourceMemberOrder, row.method.toWireSalaryMethod(), row.amountWon, row.rateBasisPoints, row.memo, row.displayOrder), SalaryAllocationCategorySyncPayload.serializer())) }
        salaryCategories.forEach { source -> effectiveDeductions(source, salaryCategories).forEach { deducted -> add(snapshot(SyncEntityType.SALARY_ALLOCATION_DEDUCTION, deductionKey(source.id, deducted), SalaryAllocationDeductionSyncPayload(identityUuid(householdId, SyncEntityType.SALARY_ALLOCATION_CATEGORY, source.id.toString()), identityUuid(householdId, SyncEntityType.SALARY_ALLOCATION_CATEGORY, deducted.toString())), SalaryAllocationDeductionSyncPayload.serializer())) } }
        dao.allSalaryChildren().forEach { row -> add(snapshot(SyncEntityType.SALARY_ALLOCATION_CHILD, row.id.toString(), SalaryAllocationChildSyncPayload(identityUuid(householdId, SyncEntityType.SALARY_ALLOCATION_CATEGORY, row.categoryId.toString()), SyncYearMonthPayload(row.attributionYear, row.attributionMonth), row.name, row.amountWon, row.memo, row.displayOrder), SalaryAllocationChildSyncPayload.serializer())) }
        dao.allSalaryGrandchildren().forEach { row -> add(snapshot(SyncEntityType.SALARY_ALLOCATION_GRANDCHILD, row.id.toString(), SalaryAllocationGrandchildSyncPayload(identityUuid(householdId, SyncEntityType.SALARY_ALLOCATION_CHILD, row.childId.toString()), SyncYearMonthPayload(row.attributionYear, row.attributionMonth), row.name, row.amountWon, row.memo, row.displayOrder), SalaryAllocationGrandchildSyncPayload.serializer())) }
        dao.allFixedCosts().forEach { row -> add(snapshot(SyncEntityType.FIXED_COST_ITEM, row.id.toString(), FixedCostItemSyncPayload(SyncYearMonthPayload(row.attributionYear, row.attributionMonth), row.payerMemberOrder, row.name, row.amountWon, row.displayOrder), FixedCostItemSyncPayload.serializer())) }
        dao.allAssets().forEach { row -> add(snapshot(SyncEntityType.ASSET, row.id.toString(), AssetSyncPayload(row.name, row.type.toWireAssetType(), row.ownerMemberOrder, row.memo, row.kind.toWireAssetKind()), AssetSyncPayload.serializer())) }
        dao.allAssetValuations().forEach { row -> add(snapshot(SyncEntityType.ASSET_VALUATION, valuationKey(row), AssetValuationSyncPayload(identityUuid(householdId, SyncEntityType.ASSET, row.assetId.toString()), SyncYearMonthPayload(row.valuationYear, row.valuationMonth), row.amountWon), AssetValuationSyncPayload.serializer())) }
        dao.allAssetGrowthRules().forEach { row -> add(snapshot(SyncEntityType.ASSET_GROWTH_RULE, row.assetId.toString(), AssetGrowthRuleSyncPayload(identityUuid(householdId, SyncEntityType.ASSET, row.assetId.toString()), SyncYearMonthPayload(row.startYear, row.startMonth), row.durationMonths, row.baseAmountWon, row.monthlyIncreaseWon), AssetGrowthRuleSyncPayload.serializer())) }
        dao.allMaintenanceFees().groupBy { it.billYear to it.billMonth }.forEach { (month, rows) ->
            val amounts = rows.associate { it.itemKey to it.amountWon }
            val entries = SyncMaintenanceFeeItemKey.entries.map { key -> MaintenanceFeeEntrySyncPayload(key, amounts[key.localName()]) }
            add(snapshot(SyncEntityType.MAINTENANCE_FEE_MONTH, maintenanceKey(month.first, month.second), MaintenanceFeeMonthSyncPayload(SyncYearMonthPayload(month.first, month.second), entries), MaintenanceFeeMonthSyncPayload.serializer()))
        }
    }

    private fun <T> snapshot(type: SyncEntityType, localKey: String, value: T, serializer: KSerializer<T>): LocalSnapshot =
        LocalSnapshot(type, localKey, json.encodeToJsonElement(serializer, value).canonicalized().jsonObject)

    private suspend fun identityUuid(householdId: String, type: SyncEntityType, localKey: String): UuidString =
        UuidString(requireNotNull(dao.identityByLocalKey(householdId, type.name, localKey)) { "Missing sync identity for $type/$localKey" }.entityUuid)

    private suspend fun applyChange(
        householdId: String,
        change: SyncChangeDto,
        resolvePendingConflicts: Boolean,
    ) {
        require(change.entityType in SUPPORTED_SYNC_ENTITY_TYPES) { "Unsupported local sync entity ${change.entityType}" }
        val existingIdentity = dao.identityByUuid(householdId, change.entityType.name, change.entityId.value)
        // A push acknowledgement records this version before the same change appears in pull.
        // Skipping it also preserves an edit made locally between the push and pull phases.
        if (existingIdentity?.serverVersion?.let { it >= change.version } == true) return
        if (existingIdentity != null) {
            val pending = dao.pending(householdId, change.entityType.name, change.entityId.value)
            if (pending != null) {
                check(resolvePendingConflicts) { "Cannot apply a remote change over a pending local mutation" }
                check(dao.rebaseOutbox(pending.mutationId, change.version, 0L) == 1) {
                    "Pending conflict disappeared before rebasing the local change"
                }
                check(dao.updateIdentity(existingIdentity.copy(
                    serverVersion = change.version,
                    syncedPayload = change.payload?.canonicalized()?.toString(),
                )) == 1)
                return
            }
        }
        val localKey = if (change.operation == SyncOperation.DELETE) {
            existingIdentity?.localKey ?: return
        } else {
            applyUpsert(householdId, change, existingIdentity?.localKey)
        }
        if (change.operation == SyncOperation.DELETE) deleteLocal(change.entityType, localKey)
        val payload = change.payload?.canonicalized()?.toString()
        if (existingIdentity == null) {
            dao.insertIdentity(SyncIdentityEntity(householdId, change.entityType.name, localKey, change.entityId.value, change.version, payload, change.operation == SyncOperation.DELETE))
        } else {
            check(dao.updateIdentity(existingIdentity.copy(localKey = localKey, serverVersion = change.version, syncedPayload = payload, deleted = change.operation == SyncOperation.DELETE)) == 1)
        }
    }

    private suspend fun applyUpsert(householdId: String, change: SyncChangeDto, existingLocalKey: String?): String {
        val payload = requireNotNull(change.payload)
        return when (change.entityType) {
            SyncEntityType.PLAN_CATALOG_ITEM -> decode(payload, PlanCatalogItemSyncPayload.serializer()).let { value -> upsertLong(existingLocalKey, { id -> dao.insertRemotePlanCatalog(PlanCatalogItemEntity(id, SYNC_LOCAL_LEDGER_ID, value.type.localName(), value.classification, value.name, value.ownerMemberOrder, value.includePurposeAccount, value.includeNetSavings, value.displayOrder, value.archived)) }, { id -> dao.updateRemotePlanCatalog(PlanCatalogItemEntity(id, SYNC_LOCAL_LEDGER_ID, value.type.localName(), value.classification, value.name, value.ownerMemberOrder, value.includePurposeAccount, value.includeNetSavings, value.displayOrder, value.archived)) }) }
            SyncEntityType.MONTHLY_PLAN_ITEM -> decode(payload, MonthlyPlanItemSyncPayload.serializer()).let { value -> val catalogId = referencedLong(householdId, SyncEntityType.PLAN_CATALOG_ITEM, value.catalogId); upsertLong(existingLocalKey, { id -> dao.insertRemoteMonthlyPlan(MonthlyPlanItemEntity(id, SYNC_LOCAL_LEDGER_ID, catalogId, value.attributionMonth.year, value.attributionMonth.month, value.amountWon, value.status.localName(), value.memo)) }, { id -> dao.updateRemoteMonthlyPlan(MonthlyPlanItemEntity(id, SYNC_LOCAL_LEDGER_ID, catalogId, value.attributionMonth.year, value.attributionMonth.month, value.amountWon, value.status.localName(), value.memo)) }) }
            SyncEntityType.EXPENSE_CATEGORY -> decode(payload, ExpenseCategorySyncPayload.serializer()).let { value -> val id = existingLocalKey ?: change.entityId.value; val entity = ExpenseCategoryEntity(id, SYNC_LOCAL_LEDGER_ID, value.name, value.displayOrder, value.archived); if (existingLocalKey == null) dao.insertRemoteExpenseCategory(entity) else check(dao.updateRemoteExpenseCategory(entity) == 1); id }
            SyncEntityType.EXPENSE_RECORD -> decode(payload, ExpenseRecordSyncPayload.serializer()).let { value -> val categoryId = referencedKey(householdId, SyncEntityType.EXPENSE_CATEGORY, value.categoryId); upsertLong(existingLocalKey, { id -> dao.insertRemoteExpenseRecord(ExpenseRecordEntity(id, SYNC_LOCAL_LEDGER_ID, categoryId, value.attributionMonth.year, value.attributionMonth.month, value.actualDate?.localString(), value.detail, value.amountWon, value.overspent)) }, { id -> dao.updateRemoteExpenseRecord(ExpenseRecordEntity(id, SYNC_LOCAL_LEDGER_ID, categoryId, value.attributionMonth.year, value.attributionMonth.month, value.actualDate?.localString(), value.detail, value.amountWon, value.overspent)) }) }
            SyncEntityType.SALARY_INCOME -> decode(payload, SalaryIncomeSyncPayload.serializer()).let { value -> val key = incomeKey(value.attributionMonth.year, value.attributionMonth.month, value.memberOrder); check(existingLocalKey == null || existingLocalKey == key); dao.upsertRemoteSalaryIncome(SalaryIncomeEntity(SYNC_LOCAL_LEDGER_ID, value.attributionMonth.year, value.attributionMonth.month, value.memberOrder, value.amountWon)); key }
            SyncEntityType.SALARY_ALLOCATION_CATEGORY -> decode(payload, SalaryAllocationCategorySyncPayload.serializer()).let { value ->
                upsertLong(
                    existingLocalKey,
                    { id -> dao.insertRemoteSalaryCategory(SalaryAllocationCategoryEntity(id, SYNC_LOCAL_LEDGER_ID, value.attributionMonth.year, value.attributionMonth.month, value.name, value.sourceMemberOrder, value.method.localName(), value.amountWon, value.rateBasisPoints, value.memo, value.displayOrder, "")) },
                    { id -> dao.updateRemoteSalaryCategory(SalaryAllocationCategoryEntity(id, SYNC_LOCAL_LEDGER_ID, value.attributionMonth.year, value.attributionMonth.month, value.name, value.sourceMemberOrder, value.method.localName(), value.amountWon, value.rateBasisPoints, value.memo, value.displayOrder, dao.remoteDeductionIds(id))) },
                )
            }
            SyncEntityType.SALARY_ALLOCATION_DEDUCTION -> decode(payload, SalaryAllocationDeductionSyncPayload.serializer()).let { value ->
                val sourceId = referencedLong(householdId, SyncEntityType.SALARY_ALLOCATION_CATEGORY, value.categoryId)
                val deductedId = referencedLong(householdId, SyncEntityType.SALARY_ALLOCATION_CATEGORY, value.deductedCategoryId)
                val key = deductionKey(sourceId, deductedId)
                check(existingLocalKey == null || existingLocalKey == key)
                val updated = dao.remoteDeductionIds(sourceId).toLocalIdSet() + deductedId
                check(dao.updateRemoteDeductionIds(sourceId, updated.sorted().joinToString(",")) == 1)
                key
            }
            SyncEntityType.SALARY_ALLOCATION_CHILD -> decode(payload, SalaryAllocationChildSyncPayload.serializer()).let { value -> val parentId = referencedLong(householdId, SyncEntityType.SALARY_ALLOCATION_CATEGORY, value.categoryId); upsertLong(existingLocalKey, { id -> dao.insertRemoteSalaryChild(SalaryAllocationChildEntity(id, parentId, value.attributionMonth.year, value.attributionMonth.month, value.name, value.amountWon, value.memo, value.displayOrder)) }, { id -> dao.updateRemoteSalaryChild(SalaryAllocationChildEntity(id, parentId, value.attributionMonth.year, value.attributionMonth.month, value.name, value.amountWon, value.memo, value.displayOrder)) }) }
            SyncEntityType.SALARY_ALLOCATION_GRANDCHILD -> decode(payload, SalaryAllocationGrandchildSyncPayload.serializer()).let { value -> val parentId = referencedLong(householdId, SyncEntityType.SALARY_ALLOCATION_CHILD, value.childId); upsertLong(existingLocalKey, { id -> dao.insertRemoteSalaryGrandchild(SalaryAllocationGrandchildEntity(id, parentId, value.attributionMonth.year, value.attributionMonth.month, value.name, value.amountWon, value.memo, value.displayOrder)) }, { id -> dao.updateRemoteSalaryGrandchild(SalaryAllocationGrandchildEntity(id, parentId, value.attributionMonth.year, value.attributionMonth.month, value.name, value.amountWon, value.memo, value.displayOrder)) }) }
            SyncEntityType.FIXED_COST_ITEM -> decode(payload, FixedCostItemSyncPayload.serializer()).let { value -> upsertLong(existingLocalKey, { id -> dao.insertRemoteFixedCost(FixedCostCheckItemEntity(id, SYNC_LOCAL_LEDGER_ID, value.attributionMonth.year, value.attributionMonth.month, value.payerMemberOrder, value.name, value.amountWon, value.displayOrder)) }, { id -> dao.updateRemoteFixedCost(FixedCostCheckItemEntity(id, SYNC_LOCAL_LEDGER_ID, value.attributionMonth.year, value.attributionMonth.month, value.payerMemberOrder, value.name, value.amountWon, value.displayOrder)) }) }
            SyncEntityType.ASSET -> decode(payload, AssetSyncPayload.serializer()).let { value -> upsertLong(existingLocalKey, { id -> dao.insertRemoteAsset(AssetItemEntity(id, SYNC_LOCAL_LEDGER_ID, value.name, value.type.localName(), value.ownerMemberOrder, value.memo, value.kind.localName())) }, { id -> dao.updateRemoteAsset(AssetItemEntity(id, SYNC_LOCAL_LEDGER_ID, value.name, value.type.localName(), value.ownerMemberOrder, value.memo, value.kind.localName())) }) }
            SyncEntityType.ASSET_VALUATION -> decode(payload, AssetValuationSyncPayload.serializer()).let { value -> val assetId = referencedLong(householdId, SyncEntityType.ASSET, value.assetId); val key = valuationKey(assetId, value.valuationMonth.year, value.valuationMonth.month); check(existingLocalKey == null || existingLocalKey == key); dao.upsertRemoteValuation(AssetValuationEntity(assetId, value.valuationMonth.year, value.valuationMonth.month, value.amountWon)); key }
            SyncEntityType.ASSET_GROWTH_RULE -> decode(payload, AssetGrowthRuleSyncPayload.serializer()).let { value -> val assetId = referencedLong(householdId, SyncEntityType.ASSET, value.assetId); val key = assetId.toString(); check(existingLocalKey == null || existingLocalKey == key); dao.upsertRemoteGrowthRule(AssetGrowthRuleEntity(assetId, value.startMonth.year, value.startMonth.month, value.durationMonths, value.baseAmountWon, value.monthlyIncreaseWon)); key }
            SyncEntityType.MAINTENANCE_FEE_MONTH -> decode(payload, MaintenanceFeeMonthSyncPayload.serializer()).let { value ->
                val key = maintenanceKey(value.billMonth.year, value.billMonth.month)
                check(existingLocalKey == null || existingLocalKey == key)
                dao.deleteRemoteMaintenanceMonth(SYNC_LOCAL_LEDGER_ID, value.billMonth.year, value.billMonth.month)
                dao.insertRemoteMaintenanceEntries(value.entries.map { entry -> MaintenanceFeeEntryEntity(SYNC_LOCAL_LEDGER_ID, value.billMonth.year, value.billMonth.month, entry.key.localName(), entry.amountWon) })
                key
            }
            else -> error("Unsupported local sync entity ${change.entityType}")
        }
    }

    private suspend fun deleteLocal(type: SyncEntityType, localKey: String) {
        when (type) {
            SyncEntityType.PLAN_CATALOG_ITEM -> dao.deleteRemotePlanCatalog(localKey.toLong())
            SyncEntityType.MONTHLY_PLAN_ITEM -> dao.deleteRemoteMonthlyPlan(localKey.toLong())
            SyncEntityType.EXPENSE_CATEGORY -> dao.deleteRemoteExpenseCategory(localKey)
            SyncEntityType.EXPENSE_RECORD -> dao.deleteRemoteExpenseRecord(localKey.toLong())
            SyncEntityType.SALARY_INCOME -> localKey.split('/').let { dao.deleteRemoteSalaryIncome(SYNC_LOCAL_LEDGER_ID, it[0].toInt(), it[1].toInt(), it[2].toInt()) }
            SyncEntityType.SALARY_ALLOCATION_CATEGORY -> dao.deleteRemoteSalaryCategory(localKey.toLong())
            SyncEntityType.SALARY_ALLOCATION_DEDUCTION -> localKey.split('/').let { parts ->
                val sourceId = parts[0].toLong()
                val updated = dao.remoteDeductionIds(sourceId).toLocalIdSet() - parts[1].toLong()
                dao.updateRemoteDeductionIds(sourceId, updated.sorted().joinToString(",").ifEmpty { null })
            }
            SyncEntityType.SALARY_ALLOCATION_CHILD -> dao.deleteRemoteSalaryChild(localKey.toLong())
            SyncEntityType.SALARY_ALLOCATION_GRANDCHILD -> dao.deleteRemoteSalaryGrandchild(localKey.toLong())
            SyncEntityType.FIXED_COST_ITEM -> dao.deleteRemoteFixedCost(localKey.toLong())
            SyncEntityType.ASSET -> dao.deleteRemoteAsset(localKey.toLong())
            SyncEntityType.ASSET_VALUATION -> localKey.split('/').let { dao.deleteRemoteValuation(it[0].toLong(), it[1].toInt(), it[2].toInt()) }
            SyncEntityType.ASSET_GROWTH_RULE -> dao.deleteRemoteGrowthRule(localKey.toLong())
            SyncEntityType.MAINTENANCE_FEE_MONTH -> localKey.split('/').let { dao.deleteRemoteMaintenanceMonth(SYNC_LOCAL_LEDGER_ID, it[0].toInt(), it[1].toInt()) }
            else -> error("Unsupported local sync entity $type")
        }
    }

    private suspend fun upsertLong(
        existingLocalKey: String?,
        insert: suspend (Long) -> Long,
        update: suspend (Long) -> Int,
    ): String {
        if (existingLocalKey == null) return insert(0L).toString()
        val id = existingLocalKey.toLong()
        check(update(id) == 1) { "Mapped local entity $id no longer exists" }
        return existingLocalKey
    }

    private suspend inline fun <reified T> decode(payload: JsonObject, serializer: KSerializer<T>): T = json.decodeFromJsonElement(serializer, payload)

    private suspend fun referencedKey(householdId: String, type: SyncEntityType, uuid: UuidString): String =
        requireNotNull(dao.identityByUuid(householdId, type.name, uuid.value)) { "Missing referenced $type ${uuid.value}" }.localKey
    private suspend fun referencedLong(householdId: String, type: SyncEntityType, uuid: UuidString): Long = referencedKey(householdId, type, uuid).toLong()

    private suspend fun <T> writeTransaction(block: suspend () -> T): T =
        database.useWriterConnection { transactor ->
            transactor.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() }
        }
}

fun createSyncLocalStore(database: MoaLogDatabase): SyncLocalStore = RoomSyncLocalStore(database, database.syncDao())

private val SUPPORTED_SYNC_ENTITY_TYPES = setOf(
    SyncEntityType.PLAN_CATALOG_ITEM, SyncEntityType.MONTHLY_PLAN_ITEM,
    SyncEntityType.EXPENSE_CATEGORY, SyncEntityType.EXPENSE_RECORD,
    SyncEntityType.SALARY_INCOME, SyncEntityType.SALARY_ALLOCATION_CATEGORY,
    SyncEntityType.SALARY_ALLOCATION_DEDUCTION,
    SyncEntityType.SALARY_ALLOCATION_CHILD, SyncEntityType.SALARY_ALLOCATION_GRANDCHILD,
    SyncEntityType.FIXED_COST_ITEM, SyncEntityType.ASSET,
    SyncEntityType.ASSET_VALUATION, SyncEntityType.ASSET_GROWTH_RULE,
    SyncEntityType.MAINTENANCE_FEE_MONTH,
)

private fun typeRank(type: SyncEntityType): Int = when (type) {
    SyncEntityType.PLAN_CATALOG_ITEM, SyncEntityType.EXPENSE_CATEGORY, SyncEntityType.SALARY_INCOME,
    SyncEntityType.SALARY_ALLOCATION_CATEGORY, SyncEntityType.FIXED_COST_ITEM, SyncEntityType.ASSET,
    SyncEntityType.MAINTENANCE_FEE_MONTH -> 0
    SyncEntityType.MONTHLY_PLAN_ITEM, SyncEntityType.EXPENSE_RECORD,
    SyncEntityType.SALARY_ALLOCATION_DEDUCTION, SyncEntityType.SALARY_ALLOCATION_CHILD,
    SyncEntityType.ASSET_VALUATION, SyncEntityType.ASSET_GROWTH_RULE -> 1
    SyncEntityType.SALARY_ALLOCATION_GRANDCHILD -> 2
    else -> 3
}

private fun incomeKey(value: SalaryIncomeEntity) = incomeKey(value.attributionYear, value.attributionMonth, value.memberOrder)
private fun incomeKey(year: Int, month: Int, memberOrder: Int) = "$year/$month/$memberOrder"
private fun valuationKey(value: AssetValuationEntity) = valuationKey(value.assetId, value.valuationYear, value.valuationMonth)
private fun valuationKey(assetId: Long, year: Int, month: Int) = "$assetId/$year/$month"
private fun maintenanceKey(year: Int, month: Int) = "$year/$month"
private fun deductionKey(sourceId: Long, deductedId: Long) = "$sourceId/$deductedId"
private fun effectiveDeductions(source: SalaryAllocationCategoryEntity, all: List<SalaryAllocationCategoryEntity>): Set<Long> =
    source.deductedCategoryIds?.toLocalIdSet() ?: all.asSequence()
        .filter { it.id != source.id && it.attributionYear == source.attributionYear && it.attributionMonth == source.attributionMonth }
        .map { it.id }
        .toSet()
private fun String?.toLocalIdSet(): Set<Long> = this?.takeIf { it.isNotBlank() }?.split(',')?.mapTo(linkedSetOf()) { it.toLong() } ?: emptySet()
private fun String.toSyncDate(): SyncDatePayload {
    val parts = split('-')
    return SyncDatePayload(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
}
private fun SyncDatePayload.localString() = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

private fun String.toWirePlanType() = when (this) { "Income" -> SyncPlanItemType.INCOME; "FixedExpense" -> SyncPlanItemType.FIXED_EXPENSE; "Savings" -> SyncPlanItemType.SAVINGS; else -> error("Unknown plan type $this") }
private fun String.toWirePlanStatus() = when (this) { "Estimated" -> SyncPlanItemStatus.ESTIMATED; "Confirmed" -> SyncPlanItemStatus.CONFIRMED; else -> error("Unknown plan status $this") }
private fun String.toWireSalaryMethod() = when (this) { "FixedAmount" -> WireSalaryMethod.FIXED_AMOUNT; "SalaryRatio" -> WireSalaryMethod.SALARY_RATIO; "ChildTotal" -> WireSalaryMethod.CHILD_TOTAL; "RemainingFromSource" -> WireSalaryMethod.REMAINING_FROM_SOURCE; else -> error("Unknown salary method $this") }
private fun String.toWireAssetType() = when (this) { "Cash" -> WireAssetType.CASH; "Deposit" -> WireAssetType.DEPOSIT; "Investment" -> WireAssetType.INVESTMENT; "Housing" -> WireAssetType.HOUSING; "Other" -> WireAssetType.OTHER; else -> error("Unknown asset type $this") }
private fun String.toWireAssetKind() = when (this) { "Ordinary" -> WireAssetKind.ORDINARY; "PurposeAccount" -> WireAssetKind.PURPOSE_ACCOUNT; else -> error("Unknown asset kind $this") }
private fun SyncPlanItemType.localName() = when (this) { SyncPlanItemType.INCOME -> "Income"; SyncPlanItemType.FIXED_EXPENSE -> "FixedExpense"; SyncPlanItemType.SAVINGS -> "Savings" }
private fun SyncPlanItemStatus.localName() = when (this) { SyncPlanItemStatus.ESTIMATED -> "Estimated"; SyncPlanItemStatus.CONFIRMED -> "Confirmed" }
private fun WireSalaryMethod.localName() = when (this) { WireSalaryMethod.FIXED_AMOUNT -> "FixedAmount"; WireSalaryMethod.SALARY_RATIO -> "SalaryRatio"; WireSalaryMethod.CHILD_TOTAL -> "ChildTotal"; WireSalaryMethod.REMAINING_FROM_SOURCE -> "RemainingFromSource" }
private fun WireAssetType.localName() = when (this) { WireAssetType.CASH -> "Cash"; WireAssetType.DEPOSIT -> "Deposit"; WireAssetType.INVESTMENT -> "Investment"; WireAssetType.HOUSING -> "Housing"; WireAssetType.OTHER -> "Other" }
private fun WireAssetKind.localName() = when (this) { WireAssetKind.ORDINARY -> "Ordinary"; WireAssetKind.PURPOSE_ACCOUNT -> "PurposeAccount" }
private fun SyncMaintenanceFeeItemKey.localName(): String = when (this) {
    SyncMaintenanceFeeItemKey.GENERAL_MANAGEMENT -> "GeneralManagement"
    SyncMaintenanceFeeItemKey.CLEANING -> "Cleaning"
    SyncMaintenanceFeeItemKey.DISINFECTION -> "Disinfection"
    SyncMaintenanceFeeItemKey.ELEVATOR_MAINTENANCE -> "ElevatorMaintenance"
    SyncMaintenanceFeeItemKey.REPAIR_MAINTENANCE -> "RepairMaintenance"
    SyncMaintenanceFeeItemKey.LONG_TERM_REPAIR_RESERVE -> "LongTermRepairReserve"
    SyncMaintenanceFeeItemKey.BUILDING_INSURANCE -> "BuildingInsurance"
    SyncMaintenanceFeeItemKey.SECURITY_SERVICE -> "SecurityService"
    SyncMaintenanceFeeItemKey.MANAGEMENT_COMMISSION -> "ManagementCommission"
    SyncMaintenanceFeeItemKey.RESIDENTS_COMMITTEE -> "ResidentsCommittee"
    SyncMaintenanceFeeItemKey.ELECTION_COMMITTEE -> "ElectionCommittee"
    SyncMaintenanceFeeItemKey.HOUSEHOLD_ELECTRICITY -> "HouseholdElectricity"
    SyncMaintenanceFeeItemKey.COMMON_ELECTRICITY -> "CommonElectricity"
    SyncMaintenanceFeeItemKey.ELEVATOR_ELECTRICITY -> "ElevatorElectricity"
    SyncMaintenanceFeeItemKey.TV_LICENSE -> "TvLicense"
    SyncMaintenanceFeeItemKey.HOUSEHOLD_WATER -> "HouseholdWater"
    SyncMaintenanceFeeItemKey.HOUSEHOLD_HEATING -> "HouseholdHeating"
    SyncMaintenanceFeeItemKey.BASIC_HEATING -> "BasicHeating"
    SyncMaintenanceFeeItemKey.HOUSEHOLD_HOT_WATER -> "HouseholdHotWater"
    SyncMaintenanceFeeItemKey.DEDUCTION -> "Deduction"
    SyncMaintenanceFeeItemKey.HOUSEHOLD_WASTE -> "HouseholdWaste"
}

private fun JsonElement.canonicalized(): JsonElement = when (this) {
    is JsonObject -> JsonObject(entries.sortedBy { it.key }.associate { it.key to it.value.canonicalized() })
    is JsonArray -> JsonArray(map { it.canonicalized() })
    else -> this
}
