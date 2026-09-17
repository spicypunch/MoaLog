package kr.jm.moalog.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SalaryAllocationMigrationInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MoaLogDatabase::class.java,
    )

    @Test
    fun migration3To4CreatesMonthScopedSalaryTables() {
        val name = "salary-migration-${System.nanoTime()}"
        helper.createDatabase(name, 3).close()
        helper.runMigrationsAndValidate(name, 4, true, MIGRATION_3_4).use { db ->
            assertEquals(
                listOf("salary_allocation_categories", "salary_allocation_children", "salary_incomes"),
                db.tableNames().filter { it.startsWith("salary_") }.sorted(),
            )
        }
    }

    @Test
    fun migration10To11KeepsCategoriesAndAddsHierarchyAndSelectionStorage() {
        val name="salary-migration-${System.nanoTime()}"
        helper.createDatabase(name,10).apply{
            execSQL("INSERT INTO `ledgers` (`id`,`name`,`baseYear`,`annualSavingsTargetWon`) VALUES (1,'test',2026,NULL)")
            execSQL("INSERT INTO `salary_allocation_categories` (`id`,`ledgerId`,`attributionYear`,`attributionMonth`,`name`,`sourceMemberOrder`,`method`,`amountWon`,`rateBasisPoints`,`memo`,`displayOrder`) VALUES (1,1,2026,9,'legacy',NULL,'RemainingFromSource',NULL,NULL,NULL,0)")
            close()
        }
        helper.runMigrationsAndValidate(name,11,true,MIGRATION_10_11).use{db->
            assertEquals(true,"salary_allocation_grandchildren" in db.tableNames())
            db.query("SELECT deductedCategoryIds FROM salary_allocation_categories WHERE id=1").use{cursor->cursor.moveToFirst();assertEquals(true,cursor.isNull(0))}
        }
    }

    private fun SupportSQLiteDatabase.tableNames(): List<String> = query(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
}
