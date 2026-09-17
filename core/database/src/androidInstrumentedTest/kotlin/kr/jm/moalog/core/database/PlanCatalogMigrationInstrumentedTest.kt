package kr.jm.moalog.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class PlanCatalogMigrationInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MoaLogDatabase::class.java)

    @Test
    fun migration11To12BackfillsCanonicalSeriesAndSeparatesSameMonthDuplicates() {
        val name = "plan-catalog-migration-${System.nanoTime()}"
        helper.createDatabase(name, 11).use { db ->
            db.execSQL("INSERT INTO ledgers(id, name, baseYear, annualSavingsTargetWon) VALUES (1, '테스트', 2026, NULL)")
            fun insert(month: Int, amount: Long) {
                db.execSQL(
                    """INSERT INTO monthly_plan_items(
                        ledgerId,type,attributionYear,attributionMonth,name,amountWon,category,status,
                        ownerMemberOrder,memo,includePurposeAccount,includeNetSavings
                    ) VALUES(1,'Savings',2026,$month,'적금',$amount,'예적금','Estimated',NULL,NULL,0,1)""",
                )
            }
            insert(1, 100)
            insert(1, 200)
            insert(2, 300)
            insert(2, 400)
        }

        helper.runMigrationsAndValidate(name, 12, true, MIGRATION_11_12).use { db ->
            val byMonth = mutableMapOf<Int, MutableList<Long>>()
            db.query("SELECT attributionMonth, catalogId FROM monthly_plan_items ORDER BY attributionMonth, id").use { cursor ->
                while (cursor.moveToNext()) byMonth.getOrPut(cursor.getInt(0)) { mutableListOf() } += cursor.getLong(1)
            }
            assertEquals(2, byMonth.getValue(1).size)
            assertEquals(2, byMonth.getValue(2).size)
            assertNotEquals(byMonth.getValue(1)[0], byMonth.getValue(1)[1])
            assertEquals(byMonth.getValue(1)[0], byMonth.getValue(2)[0])
            assertEquals(byMonth.getValue(1)[1], byMonth.getValue(2)[1])
            db.query("SELECT COUNT(*) FROM plan_item_catalog").use {
                it.moveToFirst()
                assertEquals(2, it.getInt(0))
            }
            db.query("SELECT archived FROM expense_categories LIMIT 1").use {
                if (it.moveToFirst()) assertEquals(0, it.getInt(0))
            }
        }
    }
}
