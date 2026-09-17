package kr.jm.moalog.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AssetMigrationInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MoaLogDatabase::class.java)

    @Test
    fun migration5To6CreatesAssetAndMonthlyValuationTables() {
        val name = "asset-migration-${System.nanoTime()}"
        helper.createDatabase(name, 5).close()
        helper.runMigrationsAndValidate(name, 6, true, MIGRATION_5_6).use { database ->
            assertEquals(listOf("asset_items", "asset_valuations"), database.tableNames().filter { it.startsWith("asset_") })
        }
    }

    @Test
    fun migration6To7CreatesGrowthRuleTable() {
        val name = "asset-rule-migration-${System.nanoTime()}"
        helper.createDatabase(name, 6).close()
        helper.runMigrationsAndValidate(name, 7, true, MIGRATION_6_7).use { database ->
            assertEquals(listOf("asset_growth_rules"), database.tableNames().filter { it == "asset_growth_rules" })
        }
    }

    @Test
    fun migration7To8MarksExistingAssetsAsOrdinary() {
        val name = "asset-kind-migration-${System.nanoTime()}"
        helper.createDatabase(name, 7).use { database ->
            database.execSQL(
                "INSERT INTO ledgers (id, name, baseYear, annualSavingsTargetWon) " +
                    "VALUES (1, '모아로그', 2026, NULL)",
            )
            database.execSQL(
                "INSERT INTO asset_items (id, ledgerId, name, type, ownerMemberOrder, memo) " +
                    "VALUES (1, 1, '기존 자산', 'Deposit', NULL, NULL)",
            )
        }

        helper.runMigrationsAndValidate(name, 8, true, MIGRATION_7_8).use { database ->
            database.query("SELECT kind FROM asset_items WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Ordinary", cursor.getString(0))
            }
        }
    }

    private fun SupportSQLiteDatabase.tableNames(): List<String> =
        query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
}
