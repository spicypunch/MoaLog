package kr.jm.moalog.core.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabase.Builder
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.CoroutineDispatcher

@Database(
    entities = [LedgerEntity::class, LedgerMemberEntity::class, AnnualSavingsTargetEntity::class, ExpenseCategoryEntity::class, ExpenseRecordEntity::class, PlanCatalogItemEntity::class, MonthlyPlanItemEntity::class, SalaryIncomeEntity::class, SalaryAllocationCategoryEntity::class, SalaryAllocationChildEntity::class, SalaryAllocationGrandchildEntity::class, FixedCostCheckItemEntity::class, AssetItemEntity::class, AssetValuationEntity::class, AssetGrowthRuleEntity::class, MaintenanceFeeEntryEntity::class, SyncInstallationEntity::class, SyncHouseholdBindingEntity::class, SyncIdentityEntity::class, SyncOutboxEntity::class, SyncCursorEntity::class, SyncPendingHouseholdSettingsEntity::class],
    version = 14,
    exportSchema = true,
)
@ConstructedBy(MoaLogDatabaseConstructor::class)
abstract class MoaLogDatabase : RoomDatabase() {
    internal abstract fun ledgerSetupDao(): LedgerSetupDao
    internal abstract fun expenseDao(): ExpenseDao
    internal abstract fun planDao(): PlanDao
    internal abstract fun salaryAllocationDao(): SalaryAllocationDao
    internal abstract fun fixedCostCheckDao(): FixedCostCheckDao
    internal abstract fun assetDao(): AssetDao
    internal abstract fun maintenanceFeeDao(): MaintenanceFeeDao
    internal abstract fun syncDao(): SyncDao
}

fun buildMoaLogDatabase(builder: Builder<MoaLogDatabase>): MoaLogDatabase = builder
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(databaseQueryDispatcher)
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
    .build()

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object MoaLogDatabaseConstructor : RoomDatabaseConstructor<MoaLogDatabase> {
    override fun initialize(): MoaLogDatabase
}

internal expect val databaseQueryDispatcher: CoroutineDispatcher

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `sync_installation` (`slot` INTEGER NOT NULL, `deviceId` TEXT NOT NULL, PRIMARY KEY(`slot`))")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_installation_deviceId` ON `sync_installation` (`deviceId`)")
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `monthly_plan_items` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `ledgerId` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `attributionYear` INTEGER NOT NULL,
                `attributionMonth` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `amountWon` INTEGER,
                `category` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `ownerMemberOrder` INTEGER,
                `memo` TEXT,
                `includePurposeAccount` INTEGER NOT NULL,
                `includeNetSavings` INTEGER NOT NULL,
                FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_monthly_plan_items_ledgerId_attributionYear_attributionMonth` ON `monthly_plan_items` (`ledgerId`, `attributionYear`, `attributionMonth`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_monthly_plan_items_ledgerId` ON `monthly_plan_items` (`ledgerId`)")
    }
}

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `salary_incomes` (`ledgerId` INTEGER NOT NULL, `attributionYear` INTEGER NOT NULL, `attributionMonth` INTEGER NOT NULL, `memberOrder` INTEGER NOT NULL, `amountWon` INTEGER, PRIMARY KEY(`ledgerId`, `attributionYear`, `attributionMonth`, `memberOrder`), FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_salary_incomes_ledgerId_attributionYear_attributionMonth` ON `salary_incomes` (`ledgerId`, `attributionYear`, `attributionMonth`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `salary_allocation_categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ledgerId` INTEGER NOT NULL, `attributionYear` INTEGER NOT NULL, `attributionMonth` INTEGER NOT NULL, `name` TEXT NOT NULL, `sourceMemberOrder` INTEGER, `method` TEXT NOT NULL, `amountWon` INTEGER, `rateBasisPoints` INTEGER, `memo` TEXT, `displayOrder` INTEGER NOT NULL, FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_salary_allocation_categories_ledgerId_attributionYear_attributionMonth` ON `salary_allocation_categories` (`ledgerId`, `attributionYear`, `attributionMonth`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `salary_allocation_children` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `categoryId` INTEGER NOT NULL, `attributionYear` INTEGER NOT NULL, `attributionMonth` INTEGER NOT NULL, `name` TEXT NOT NULL, `amountWon` INTEGER, `memo` TEXT, `displayOrder` INTEGER NOT NULL, FOREIGN KEY(`categoryId`) REFERENCES `salary_allocation_categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_salary_allocation_children_categoryId` ON `salary_allocation_children` (`categoryId`)")
    }
}

val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `fixed_cost_check_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ledgerId` INTEGER NOT NULL, `attributionYear` INTEGER NOT NULL, `attributionMonth` INTEGER NOT NULL, `payerMemberOrder` INTEGER, `name` TEXT NOT NULL, `amountWon` INTEGER, `displayOrder` INTEGER NOT NULL, FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_fixed_cost_check_items_ledgerId_attributionYear_attributionMonth` ON `fixed_cost_check_items` (`ledgerId`, `attributionYear`, `attributionMonth`)")
    }
}

val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `asset_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ledgerId` INTEGER NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `ownerMemberOrder` INTEGER, `memo` TEXT, FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_asset_items_ledgerId` ON `asset_items` (`ledgerId`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `asset_valuations` (`assetId` INTEGER NOT NULL, `valuationYear` INTEGER NOT NULL, `valuationMonth` INTEGER NOT NULL, `amountWon` INTEGER NOT NULL, PRIMARY KEY(`assetId`, `valuationYear`, `valuationMonth`), FOREIGN KEY(`assetId`) REFERENCES `asset_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_asset_valuations_assetId` ON `asset_valuations` (`assetId`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_asset_valuations_valuationYear_valuationMonth` ON `asset_valuations` (`valuationYear`, `valuationMonth`)")
    }
}

val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `asset_growth_rules` (`assetId` INTEGER NOT NULL, `startYear` INTEGER NOT NULL, `startMonth` INTEGER NOT NULL, `durationMonths` INTEGER NOT NULL, `baseAmountWon` INTEGER NOT NULL, `monthlyIncreaseWon` INTEGER NOT NULL, PRIMARY KEY(`assetId`), FOREIGN KEY(`assetId`) REFERENCES `asset_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_asset_growth_rules_assetId` ON `asset_growth_rules` (`assetId`)")
    }
}

val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL("ALTER TABLE `asset_items` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'Ordinary'")
    }
}

val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `maintenance_fee_entries` (`ledgerId` INTEGER NOT NULL, `billYear` INTEGER NOT NULL, `billMonth` INTEGER NOT NULL, `itemKey` TEXT NOT NULL, `amountWon` INTEGER, PRIMARY KEY(`ledgerId`, `billYear`, `billMonth`, `itemKey`), FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_maintenance_fee_entries_ledgerId_billYear_billMonth` ON `maintenance_fee_entries` (`ledgerId`, `billYear`, `billMonth`)")
    }
}

val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `annual_savings_targets` (`ledgerId` INTEGER NOT NULL, `year` INTEGER NOT NULL, `amountWon` INTEGER NOT NULL, PRIMARY KEY(`ledgerId`, `year`), FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_annual_savings_targets_ledgerId` ON `annual_savings_targets` (`ledgerId`)")
        connection.execSQL("INSERT OR IGNORE INTO `annual_savings_targets` (`ledgerId`, `year`, `amountWon`) SELECT `id`, `baseYear`, `annualSavingsTargetWon` FROM `ledgers` WHERE `annualSavingsTargetWon` IS NOT NULL")
    }
}

val MIGRATION_10_11 = object : androidx.room.migration.Migration(10,11){
    override fun migrate(connection:androidx.sqlite.SQLiteConnection){
        connection.execSQL("ALTER TABLE `salary_allocation_categories` ADD COLUMN `deductedCategoryIds` TEXT")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `salary_allocation_grandchildren` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `childId` INTEGER NOT NULL, `attributionYear` INTEGER NOT NULL, `attributionMonth` INTEGER NOT NULL, `name` TEXT NOT NULL, `amountWon` INTEGER, `memo` TEXT, `displayOrder` INTEGER NOT NULL, FOREIGN KEY(`childId`) REFERENCES `salary_allocation_children`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_salary_allocation_grandchildren_childId` ON `salary_allocation_grandchildren` (`childId`)")
    }
}

val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL("ALTER TABLE `expense_categories` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `plan_item_catalog` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `ledgerId` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `classification` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `ownerMemberOrder` INTEGER,
                `includePurposeAccount` INTEGER NOT NULL,
                `includeNetSavings` INTEGER NOT NULL,
                `displayOrder` INTEGER NOT NULL,
                `archived` INTEGER NOT NULL,
                FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_item_catalog_ledgerId` ON `plan_item_catalog` (`ledgerId`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_item_catalog_ledgerId_type_archived_displayOrder` ON `plan_item_catalog` (`ledgerId`, `type`, `archived`, `displayOrder`)")

        connection.execSQL("""
            CREATE TEMP TABLE `_plan_series` AS
            SELECT p.*,
                   ROW_NUMBER() OVER (
                       PARTITION BY ledgerId, type, trim(name), category, ownerMemberOrder,
                                    includePurposeAccount, includeNetSavings, attributionYear, attributionMonth
                       ORDER BY id
                   ) AS occurrence
            FROM monthly_plan_items p
        """.trimIndent())
        connection.execSQL("""
            CREATE TEMP TABLE `_plan_groups` AS
            SELECT MIN(id) AS catalogId, ledgerId, type, trim(name) AS normalizedName,
                   category, ownerMemberOrder, includePurposeAccount, includeNetSavings, occurrence
            FROM _plan_series
            GROUP BY ledgerId, type, trim(name), category, ownerMemberOrder,
                     includePurposeAccount, includeNetSavings, occurrence
        """.trimIndent())
        connection.execSQL("""
            INSERT INTO plan_item_catalog (
                id, ledgerId, type, classification, name, ownerMemberOrder,
                includePurposeAccount, includeNetSavings, displayOrder, archived
            )
            SELECT catalogId, ledgerId, type, category, normalizedName, ownerMemberOrder,
                   includePurposeAccount, includeNetSavings, catalogId, 0
            FROM _plan_groups
        """.trimIndent())
        connection.execSQL("""
            CREATE TABLE `monthly_plan_items_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `ledgerId` INTEGER NOT NULL,
                `catalogId` INTEGER NOT NULL,
                `attributionYear` INTEGER NOT NULL,
                `attributionMonth` INTEGER NOT NULL,
                `amountWon` INTEGER,
                `status` TEXT NOT NULL,
                `memo` TEXT,
                FOREIGN KEY(`ledgerId`) REFERENCES `ledgers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`catalogId`) REFERENCES `plan_item_catalog`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
        """.trimIndent())
        connection.execSQL("""
            INSERT INTO monthly_plan_items_new (
                id, ledgerId, catalogId, attributionYear, attributionMonth, amountWon, status, memo
            )
            SELECT s.id, s.ledgerId, g.catalogId, s.attributionYear, s.attributionMonth,
                   s.amountWon, s.status, s.memo
            FROM _plan_series s
            INNER JOIN _plan_groups g
              ON g.ledgerId = s.ledgerId AND g.type = s.type
             AND g.normalizedName = trim(s.name) AND g.category = s.category
             AND ((g.ownerMemberOrder IS NULL AND s.ownerMemberOrder IS NULL) OR g.ownerMemberOrder = s.ownerMemberOrder)
             AND g.includePurposeAccount = s.includePurposeAccount
             AND g.includeNetSavings = s.includeNetSavings
             AND g.occurrence = s.occurrence
        """.trimIndent())
        connection.execSQL("DROP TABLE `monthly_plan_items`")
        connection.execSQL("ALTER TABLE `monthly_plan_items_new` RENAME TO `monthly_plan_items`")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_monthly_plan_items_ledgerId_attributionYear_attributionMonth` ON `monthly_plan_items` (`ledgerId`, `attributionYear`, `attributionMonth`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_monthly_plan_items_ledgerId` ON `monthly_plan_items` (`ledgerId`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_monthly_plan_items_catalogId` ON `monthly_plan_items` (`catalogId`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_monthly_plan_items_catalogId_attributionYear_attributionMonth` ON `monthly_plan_items` (`catalogId`, `attributionYear`, `attributionMonth`)")
        connection.execSQL("DROP TABLE `_plan_series`")
        connection.execSQL("DROP TABLE `_plan_groups`")
    }
}

val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `sync_installation` (`slot` INTEGER NOT NULL, `deviceId` TEXT NOT NULL, PRIMARY KEY(`slot`))")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_installation_deviceId` ON `sync_installation` (`deviceId`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `sync_household_bindings` (`localLedgerId` INTEGER NOT NULL, `householdId` TEXT NOT NULL, PRIMARY KEY(`localLedgerId`))")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_household_bindings_householdId` ON `sync_household_bindings` (`householdId`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `sync_identities` (`householdId` TEXT NOT NULL, `entityType` TEXT NOT NULL, `localKey` TEXT NOT NULL, `entityUuid` TEXT NOT NULL, `serverVersion` INTEGER, `syncedPayload` TEXT, `deleted` INTEGER NOT NULL, PRIMARY KEY(`householdId`, `entityType`, `localKey`))")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_identities_householdId_entityType_entityUuid` ON `sync_identities` (`householdId`, `entityType`, `entityUuid`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `sync_outbox` (`mutationId` TEXT NOT NULL, `householdId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, `entityType` TEXT NOT NULL, `entityUuid` TEXT NOT NULL, `operation` TEXT NOT NULL, `baseVersion` INTEGER, `payloadJson` TEXT, `attemptCount` INTEGER NOT NULL, `nextAttemptAtEpochMillis` INTEGER NOT NULL, `lastError` TEXT, `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`mutationId`))")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_householdId_nextAttemptAtEpochMillis_createdAtEpochMillis` ON `sync_outbox` (`householdId`, `nextAttemptAtEpochMillis`, `createdAtEpochMillis`)")
        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_householdId_entityType_entityUuid` ON `sync_outbox` (`householdId`, `entityType`, `entityUuid`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `sync_cursors` (`householdId` TEXT NOT NULL, `cursor` INTEGER NOT NULL, PRIMARY KEY(`householdId`))")
    }
}

val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `sync_pending_household_settings` (`slot` INTEGER NOT NULL, `householdId` TEXT NOT NULL, `baseVersion` INTEGER NOT NULL, PRIMARY KEY(`slot`))")
    }
}
