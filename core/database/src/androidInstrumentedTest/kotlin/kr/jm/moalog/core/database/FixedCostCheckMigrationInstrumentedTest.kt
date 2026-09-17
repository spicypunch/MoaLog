package kr.jm.moalog.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FixedCostCheckMigrationInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MoaLogDatabase::class.java)

    @Test fun migration4To5CreatesMonthScopedFixedCostTable() {
        val name = "fixed-cost-migration-${System.nanoTime()}"
        helper.createDatabase(name, 4).close()
        helper.runMigrationsAndValidate(name, 5, true, MIGRATION_4_5).use { database ->
            assertEquals(listOf("fixed_cost_check_items"), database.tableNames().filter { it.startsWith("fixed_cost") })
        }
    }

    private fun SupportSQLiteDatabase.tableNames(): List<String> = query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
}
