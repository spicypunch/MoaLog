package kr.jm.moalog.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kr.jm.moalog.core.model.MaintenanceFeeItemKey
import kr.jm.moalog.core.model.MaintenanceFeeMonth
import kr.jm.moalog.core.model.YearMonthKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File

class MaintenanceFeeMigrationInstrumentedTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MoaLogDatabase::class.java)

    @Test
    fun migration8To9CreatesMaintenanceFeeTable() {
        val name = "maintenance-migration-${System.nanoTime()}"
        helper.createDatabase(name, 8).close()
        helper.runMigrationsAndValidate(name, 9, true, MIGRATION_8_9).use { database ->
            database.query("SELECT name FROM sqlite_master WHERE type='table' AND name='maintenance_fee_entries'").use {
                assertEquals(true, it.moveToFirst())
            }
        }
    }

    @Test
    fun replaceMonthIsAtomicAndRangeCrossesYearWhileKeepingMissingAndZero() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getDir("instrumentation-db", Context.MODE_PRIVATE), "maintenance-${System.nanoTime()}.db")
        val database = buildMoaLogDatabase(Room.databaseBuilder(context, MoaLogDatabase::class.java, file.absolutePath))
        try {
            database.ledgerSetupDao().upsertLedger(LedgerEntity(1, "테스트", 2025, null))
            val source = createMaintenanceFeeLocalDataSource(database)
            val december = YearMonthKey(2025, 12)
            val january = YearMonthKey(2026, 1)
            source.saveMonth(MaintenanceFeeMonth.fromAmounts(december, mapOf(
                MaintenanceFeeItemKey.Cleaning to 0L,
                MaintenanceFeeItemKey.Deduction to -1_000L,
            )))
            source.saveMonth(MaintenanceFeeMonth.fromAmounts(january, mapOf(
                MaintenanceFeeItemKey.GeneralManagement to 200_000L,
            )))

            val range = source.observeRange(december, january).first()
            assertEquals(listOf(december, january), range.map { it.billMonth })
            assertEquals(-1_000L, range[0].totalWon)
            assertEquals(200_000L, range[1].totalWon)
            assertEquals(0L, range[0].amountOf(MaintenanceFeeItemKey.Cleaning))
            assertNull(range[0].amountOf(MaintenanceFeeItemKey.Disinfection))

            source.saveMonth(MaintenanceFeeMonth.empty(december))
            val replaced = source.observeMonth(december).first()
            assertEquals(21, replaced.entries.size)
            assertNull(replaced.totalWon)
        } finally {
            database.close()
            listOf(file, File(file.path + "-wal"), File(file.path + "-shm")).forEach { it.delete() }
        }
    }
}
