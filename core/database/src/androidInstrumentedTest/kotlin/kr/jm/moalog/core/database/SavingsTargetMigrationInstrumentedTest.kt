package kr.jm.moalog.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SavingsTargetMigrationInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MoaLogDatabase::class.java)

    @Test
    fun migration9To10CreatesTargetTableAndCopiesBaseYearValue() {
        val name = "annual-savings-target-migration-${System.nanoTime()}"

        helper.createDatabase(name, 9).use { db ->
            db.execSQL("INSERT INTO ledgers(id, name, baseYear, annualSavingsTargetWon) VALUES (1, '테스트-2026', 2026, 12000000)")
            db.execSQL("INSERT INTO ledgers(id, name, baseYear, annualSavingsTargetWon) VALUES (2, '테스트-2027', 2027, 0)")
        }

        helper.runMigrationsAndValidate(name, 10, true, MIGRATION_9_10).use { db ->
            db.query("SELECT amountWon FROM annual_savings_targets WHERE ledgerId = 1 AND year = 2026").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(12_000_000L, it.getLong(0))
            }
            db.query("SELECT amountWon FROM annual_savings_targets WHERE ledgerId = 2 AND year = 2027").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
            db.query("SELECT annualSavingsTargetWon FROM ledgers WHERE id = 1").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(12_000_000L, it.getLong(0))
            }
            db.query("SELECT annualSavingsTargetWon FROM ledgers WHERE id = 2").use {
                assertEquals(true, it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
        }
    }
}
